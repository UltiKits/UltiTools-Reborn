#!/usr/bin/env bash
#
# 发布入口。在本机跑，负责打 tag 之前的全部校验，以及带 JAR 附件建 release。
#
# 为什么是本地脚本而不是 workflow：发布是低频、需要人在环判断的动作
# （changelog 措辞、要不要 prerelease、文档站同不同步发）。仓库里曾经有一个
# 更完整的 workflow_dispatch 发布流程，正因为「填表单」比敲命令难用，
# 一次都没被跑过——一份写得对但没人走的流程，比没有它更危险，它会持续
# 制造「已经有门禁了」的错觉。见 issue #230 / #231。
#
# 校验必须在打 tag 之前。tag 一旦推上去，release 一旦建出来，
# publish-packages.yml 就会被触发并往 Maven Central 推——Central 不允许
# 覆盖或删除已发布的坐标，那一步是不可撤销的。
#
# 用法:
#   .github/scripts/release.sh [选项]
#
#   --dry-run             跑完所有校验与构建，但不建 release
#   --prerelease          标记为预发布
#   --notes <文本>        release 说明
#   --notes-file <路径>   从文件读 release 说明
#   --branch <分支名>     允许从该分支发布（默认 main）
#   --yes                 跳过交互确认（无人值守时用，慎用）
#   -h, --help            显示本说明
#
set -euo pipefail

DRY_RUN=0
PRERELEASE=0
ASSUME_YES=0
NOTES=""
NOTES_FILE=""
RELEASE_BRANCH="main"

usage() {
    sed -n '3,25p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)    DRY_RUN=1; shift ;;
        --prerelease) PRERELEASE=1; shift ;;
        --yes|-y)     ASSUME_YES=1; shift ;;
        --notes)      NOTES="${2:-}"; shift 2 ;;
        --notes-file) NOTES_FILE="${2:-}"; shift 2 ;;
        --branch)     RELEASE_BRANCH="${2:-}"; shift 2 ;;
        -h|--help)    usage; exit 0 ;;
        *)            echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
    esac
done

die() { echo "❌ $*" >&2; exit 1; }
note() { echo "   $*"; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "不在 git 仓库里。"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- 环境

command -v mvn >/dev/null 2>&1 || die "找不到 mvn。"
command -v gh  >/dev/null 2>&1 || die "找不到 gh（GitHub CLI）。"
gh auth status >/dev/null 2>&1 || die "gh 未登录，先跑 gh auth login。"

# ---------------------------------------------------------------- 工作树与分支

if [ -n "$(git status --porcelain)" ]; then
    die "工作树不干净。发布必须从干净的工作树进行，否则产出的 JAR 与 tag 指向的提交对不上。"
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" != "$RELEASE_BRANCH" ]; then
    die "当前在 ${CURRENT_BRANCH}，发布分支是 ${RELEASE_BRANCH}。
        历史上每一个 release 的 target 都是 main。若确实要从别的分支发，传 --branch ${CURRENT_BRANCH}。"
fi

git fetch --quiet origin "$RELEASE_BRANCH" --tags
LOCAL_SHA="$(git rev-parse HEAD)"
REMOTE_SHA="$(git rev-parse "origin/${RELEASE_BRANCH}")"
if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
    die "本地 ${RELEASE_BRANCH} 与 origin/${RELEASE_BRANCH} 不一致。
        本地 ${LOCAL_SHA:0:12} / 远端 ${REMOTE_SHA:0:12}
        release 会建在远端的提交上，先把两边对齐。"
fi

# ---------------------------------------------------------------- 版本号

echo "▶ 读取版本号"
VERSION="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1 | tr -d '[:space:]')"
[ -n "$VERSION" ] || die "从 pom.xml 读不出版本号。"
note "pom.xml: ${VERSION}"

# 这一条同时挡掉 SNAPSHOT、带后缀的版本号、以及打错的字符串。
# 放在这里而不是构建之后：越早失败，浪费的时间越少，且离打 tag 越远。
# 用 bash 内建的 [[ =~ ]] 和 case，不走管道 —— 理由见下面 JAR 断言处的注释，
# `producer | grep -q` 在 set -o pipefail 下会因为「匹配得太早」而假失败。
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    if [[ "$VERSION" == *SNAPSHOT* ]]; then
        die "版本号是 ${VERSION}，还带着 SNAPSHOT。
        先把 pom.xml 的 <version> 改成正式版本号再发布。
        注意：pom.xml 里出现 -SNAPSHOT 只代表「在朝下一个版本推进」，不代表该发版了。"
    fi
    die "版本号 ${VERSION} 不符合 MAJOR.MINOR.PATCH 格式。"
fi

TAG="v${VERSION}"

# ---------------------------------------------------------------- tag 冲突

# 三道查，覆盖面不同：
#   本地 tag —— 上面的 git fetch --tags 已经把远端 tag 拉成本地的了，
#               所以实际命中的基本都是这一条，它同时覆盖了远端已有 tag 的情况。
#   ls-remote —— 兜底。防的是 fetch 没报错但也没更新（比如引用被拒绝）的情形。
#   gh release view —— 覆盖前两条查不到的一类：**草稿 release**。
#               草稿的 tag 还没推上去，git 侧完全看不见它，但正式发布时会撞名。
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1; then
    die "本地已存在 tag ${TAG}。发布同一个版本号两次会让下游拿到两份不同的产物。"
fi
if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null 2>&1; then
    die "远端已存在 tag ${TAG}。"
fi
if gh release view "$TAG" >/dev/null 2>&1; then
    die "GitHub 上已存在 release ${TAG}。"
fi

echo "▶ 校验通过：${TAG} 未被占用"

# ---------------------------------------------------------------- 构建

echo "▶ 构建（mvn -B clean package，含测试）"
mvn -B clean package

# 必须写全文件名。target/ 下 mvn package 会留下四个 jar：
#   UltiTools-API-<v>.jar            ← 要的，shade 之后的
#   original-UltiTools-API-<v>.jar   ← shade 之前的原始包
#   UltiTools-API-<v>-sources.jar
#   UltiTools-API-<v>-javadoc.jar
# 用 target/*.jar 通配的话，字典序会先命中 original-，那是个没经过 shade 的包，
# 看起来正常但缺少被重定位的依赖。
JAR="target/UltiTools-API-${VERSION}.jar"
[ -f "$JAR" ] || die "构建完成但找不到 ${JAR}。"

# 断言挑中的确实是主 JAR，而不是 sources / javadoc。
# 只比文件名不够——万一 shade 配置变了，名字对而内容不对同样要拦下来。
#
# 列表一次性读进变量再用 case 匹配，不走管道。
# `unzip -l "$JAR" | grep -q ...` 在 set -o pipefail 下是错的：grep -q 一命中就
# 退出并关闭管道，unzip 随即吃到 SIGPIPE 返回 141，pipefail 把它当成整条管道的
# 状态，于是断言「因为成功得太快」而失败。plugin.yml 恰好排在 jar 很靠前的位置，
# 所以那种写法几乎必然触发；而匹配不到时反倒不会（grep 读完全部输入，无 SIGPIPE），
# 用不匹配的样本去测会误判为通过。
JAR_ENTRIES="$(unzip -Z1 "$JAR")"
case "$JAR_ENTRIES" in
    *plugin.yml*) ;;
    *) die "${JAR} 里没有 plugin.yml，这不像是可用的插件 JAR。" ;;
esac
case "$JAR_ENTRIES" in
    *com/ultikits/ultitools/UltiTools.class*) ;;
    *) die "${JAR} 里没有 UltiTools.class，可能挑到了 sources jar。" ;;
esac

JAR_SIZE="$(stat -c%s "$JAR" 2>/dev/null || stat -f%z "$JAR")"
JAR_SHA="$(sha256sum "$JAR" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$JAR" | cut -d' ' -f1)"

# ---------------------------------------------------------------- release 说明

if [ -n "$NOTES_FILE" ]; then
    [ -f "$NOTES_FILE" ] || die "找不到 ${NOTES_FILE}。"
    NOTES="$(cat "$NOTES_FILE")"
fi
if [ -z "$NOTES" ]; then
    LAST_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
    if [ -n "$LAST_TAG" ]; then
        # 用 git 自己的 -n 限制条数，不要 `git log | head -50`：
        # head 读满就退出并关闭管道，git log 吃到 SIGPIPE 返回 141，
        # set -o pipefail 会把它当成整条管道失败，于是提交多于 50 条时脚本必炸。
        NOTES="$(git log "${LAST_TAG}..HEAD" -n 50 --pretty=format:'- %s (%h)')"
    else
        NOTES="$(git log --pretty=format:'- %s (%h)' -10)"
    fi
fi

# ---------------------------------------------------------------- 确认

echo
echo "────────────────────────────────────────────────────────"
echo "  版本       ${VERSION}"
echo "  tag        ${TAG}"
echo "  分支       ${RELEASE_BRANCH} @ ${LOCAL_SHA:0:12}"
echo "  预发布     $([ "$PRERELEASE" = 1 ] && echo 是 || echo 否)"
echo "  附件       ${JAR}"
echo "             ${JAR_SIZE} 字节"
echo "             sha256 ${JAR_SHA}"
echo "────────────────────────────────────────────────────────"
echo

if [ "$DRY_RUN" = 1 ]; then
    echo "✅ 试运行：全部校验通过，未创建 release。"
    exit 0
fi

echo "⚠ 建 release 会触发 publish-packages.yml，它会把 ${VERSION} 推到"
echo "  GitHub Packages，并在 PUBLISH_TO_CENTRAL 为 true 时推到 Maven Central。"
echo "  Maven Central 不允许覆盖或删除已发布的坐标——这一步不可撤销。"
echo

if [ "$ASSUME_YES" != 1 ]; then
    printf '输入版本号 %s 以确认发布: ' "$VERSION"
    read -r CONFIRM
    [ "$CONFIRM" = "$VERSION" ] || die "未确认，已中止。"
fi

# ---------------------------------------------------------------- 发布

GH_ARGS=(release create "$TAG"
         --target "$RELEASE_BRANCH"
         --title "Release ${TAG}"
         --notes "$NOTES")
[ "$PRERELEASE" = 1 ] && GH_ARGS+=(--prerelease)

echo "▶ 创建 release 并上传附件"
gh "${GH_ARGS[@]}" "$JAR"

echo
echo "✅ ${TAG} 已发布"
echo "   https://github.com/UltiKits/UltiTools-Reborn/releases/tag/${TAG}"
echo
echo "   附件 sha256 ${JAR_SHA}"
echo "   送真机 UAT 时需要这个值，且必须是本次构建当场取的——"
echo "   ZIP 时间戳会让语义相同的重新构建产生不同 hash。"
