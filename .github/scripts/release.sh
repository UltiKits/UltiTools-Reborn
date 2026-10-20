#!/usr/bin/env bash
#
# Release entrypoint for UltiTools-API. Run locally. It builds a release candidate once,
# keeps the exact bytes it built, and later publishes those same bytes after independent
# acceptance, without building again.
#
# Known limitations:
# A failed `gh release view`, even for an error other than "not found", is treated as an
# absent release. At worst this creates a second draft for the operator to delete; the
# retained-byte checks and readback still prevent publishing the wrong bytes.
#
# Usage:
#   .github/scripts/release.sh --prepare <new-retention-dir> [--branch <name>]
#   .github/scripts/release.sh --publish-approved <manifest> --authority-receipt <file> \
#       --uat-receipt <file> [--notes <text> | --notes-file <path>] [--prerelease] [--yes] [--dry-run]
#   .github/scripts/release.sh --dry-run [--branch <name>]
#
#   --prepare <dir>           Verify a clean checkout, run `mvn -B clean verify`, and copy the
#                             primary JAR, flattened POM, sources JAR and javadoc JAR, plus the
#                             GitHub Packages POM derived from the flattened POM, into <dir> (which
#                             must not exist yet) together with a public release manifest.
#                             Creates no tag and no release.
#   --publish-approved <file> Publish the retained files named by that manifest. Requires the
#                             receipts below, re-checks source, remote, version, tag and bytes,
#                             and never runs Maven.
#   --authority-receipt <f>   The retained human publication decision for this exact manifest.
#   --uat-receipt <f>         The gates record whose UAT accepted this exact manifest.
#   --dry-run                 Alone: run every check and the build, create nothing. With
#                             --publish-approved: run every re-check, create nothing.
#                             Dry-run output is not acceptance evidence.
#   --prerelease              Mark the release as a prerelease.
#   --notes <text>            Release notes.
#   --notes-file <path>       Read release notes from a file.
#   --branch <name>           Release branch (default main).
#   --yes                     Skip the interactive confirmation.
#   -h, --help                Show this help.
#
# 发布入口。在本机跑：--prepare 只构建一次并保留当次产物，独立验收之后
# --publish-approved 发布同一份字节，不再重新构建。
#
# 为什么是本地脚本而不是 workflow：发布是低频、需要人在环判断的动作
# （changelog 措辞、要不要 prerelease、文档站同不同步发）。仓库里曾经有一个
# 更完整的 workflow_dispatch 发布流程，正因为「填表单」比敲命令难用，
# 一次都没被跑过——一份写得对但没人走的流程，比没有它更危险，它会持续
# 制造「已经有门禁了」的错觉。见 issue #230 / #231。
#
# Why the handoff exists: the accepted artifact must be the published artifact. A release that
# rebuilds after acceptance publishes bytes nobody tested, because ZIP timestamps make a
# semantically identical rebuild hash differently. So the build happens once, in --prepare;
# independent UAT and the human decision judge those retained bytes; --publish-approved
# uploads exactly those files. Running the script with no mode no longer builds and
# publishes in one step.
#
# 校验必须在打 tag 之前。tag 一旦推上去，release 一旦建出来，
# publish-packages.yml 就会被触发并往 Maven Central 推——Central 不允许
# 覆盖或删除已发布的坐标，那一步是不可撤销的。
#
set -euo pipefail

MODE=""
DRY_RUN=0
PRERELEASE=0
ASSUME_YES=0
NOTES=""
NOTES_FILE=""
RELEASE_BRANCH="main"
RETAIN_DIR=""
MANIFEST=""
AUTHORITY_RECEIPT=""
UAT_RECEIPT=""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="${SCRIPT_DIR}/release_artifacts.py"

usage() {
    sed -n '3,30p' "${SCRIPT_DIR}/release.sh" | sed 's/^# \{0,1\}//'
}

die() { echo "❌ $*" >&2; exit 1; }
note() { echo "   $*"; }

# Resolve a caller-supplied path before the script changes directory.
abspath() {
    case "$1" in
        /*) printf '%s' "$1" ;;
        *)  printf '%s/%s' "$PWD" "$1" ;;
    esac
}

set_mode() {
    if [ -n "$MODE" ] && [ "$MODE" != "$1" ]; then
        echo "--prepare and --publish-approved are separate steps; pass only one." >&2
        exit 2
    fi
    MODE="$1"
}

need_value() {
    if [ $# -lt 2 ] || [ -z "$2" ]; then
        echo "$1 needs a value." >&2
        exit 2
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --prepare)           need_value "$@"; set_mode prepare; RETAIN_DIR="$(abspath "$2")"; shift 2 ;;
        --publish-approved)  need_value "$@"; set_mode publish; MANIFEST="$(abspath "$2")"; shift 2 ;;
        --authority-receipt) need_value "$@"; AUTHORITY_RECEIPT="$(abspath "$2")"; shift 2 ;;
        --uat-receipt)       need_value "$@"; UAT_RECEIPT="$(abspath "$2")"; shift 2 ;;
        --dry-run)    DRY_RUN=1; shift ;;
        --prerelease) PRERELEASE=1; shift ;;
        --yes|-y)     ASSUME_YES=1; shift ;;
        --notes)      need_value "$@"; NOTES="$2"; shift 2 ;;
        --notes-file) need_value "$@"; NOTES_FILE="$(abspath "$2")"; shift 2 ;;
        --branch)     need_value "$@"; RELEASE_BRANCH="$2"; shift 2 ;;
        -h|--help)    usage; exit 0 ;;
        *)            echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "$MODE" ] && [ "$DRY_RUN" != 1 ]; then
    cat >&2 <<'EOF'
❌ release.sh no longer builds and publishes in one step.
   1. --prepare <new-dir>        build once and retain the exact files (no tag, no release)
   2. hand the retained files and manifest to independent acceptance
   3. --publish-approved <manifest> --authority-receipt <file> --uat-receipt <file>
EOF
    exit 2
fi
if [ "$MODE" = prepare ]; then
    [ "$DRY_RUN" != 1 ] || { echo "--prepare never publishes; --dry-run adds nothing to it." >&2; exit 2; }
    if [ -n "$AUTHORITY_RECEIPT$UAT_RECEIPT" ]; then
        echo "Receipts belong to --publish-approved, not --prepare." >&2
        exit 2
    fi
fi
if [ "$MODE" = publish ] && { [ -z "$AUTHORITY_RECEIPT" ] || [ -z "$UAT_RECEIPT" ]; }; then
    echo "--publish-approved needs both --authority-receipt and --uat-receipt." >&2
    exit 2
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Not inside a git repository."
cd "$REPO_ROOT"

WORK_DIR="$(mktemp -d)"
LOCK_DIR=""
LOCK_HELD=0
cleanup() {
    rm -rf "$WORK_DIR"
    if [ "$LOCK_HELD" = 1 ]; then
        rm -rf "$LOCK_DIR"
    fi
}
trap cleanup EXIT

# One release operation per repository and version at a time. mkdir is atomic, so two local
# runs cannot both take the lock; the lock lives in the git common directory, so every
# worktree of this repository shares it. It does not coordinate separate machines: that is
# what the remote recheck immediately before every write is for.
acquire_lock() {
    local common
    common="$(cd "$(git rev-parse --git-common-dir)" && pwd)"
    LOCK_DIR="${common}/ultitools-release-${VERSION}.lock"
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        die "Another release operation for ${VERSION} is in progress (${LOCK_DIR}).
        If no such run exists, remove that directory and start again."
    fi
    LOCK_HELD=1
    printf 'pid %s\nstarted %s\n' "$$" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "${LOCK_DIR}/owner"
}

# ---------------------------------------------------------------- environment

if [ "$MODE" != publish ]; then
    command -v mvn >/dev/null 2>&1 || die "mvn not found."
fi
if [ -n "$MODE" ]; then
    command -v python3 >/dev/null 2>&1 || die "python3 not found."
    [ -f "$HELPER" ] || die "Missing ${HELPER}."
fi
command -v gh  >/dev/null 2>&1 || die "gh (GitHub CLI) not found."
gh auth status >/dev/null 2>&1 || die "gh is not logged in; run gh auth login first."

helper() { python3 -I "$HELPER" "$@"; }

# ---------------------------------------------------------------- checkout

check_checkout() {
    if [ -n "$(git status --porcelain)" ]; then
        die "The working tree is not clean. A release must come from a clean tree, otherwise the JAR does not match the commit the tag points at."
    fi

    CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
    if [ "$CURRENT_BRANCH" != "$RELEASE_BRANCH" ]; then
        die "On ${CURRENT_BRANCH}, but the release branch is ${RELEASE_BRANCH}.
        Every release so far targeted main. To release from another branch, pass --branch ${CURRENT_BRANCH}."
    fi

    git fetch --quiet origin "$RELEASE_BRANCH" --tags
    LOCAL_SHA="$(git rev-parse HEAD)"
    REMOTE_SHA="$(git rev-parse "origin/${RELEASE_BRANCH}")"
    if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
        die "Local ${RELEASE_BRANCH} and origin/${RELEASE_BRANCH} differ.
        local ${LOCAL_SHA:0:12} / remote ${REMOTE_SHA:0:12}
        The release is created on the remote commit; align both first."
    fi
}

# ---------------------------------------------------------------- version

read_pom_version() {
    echo "▶ Reading the version"
    VERSION="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1 | tr -d '[:space:]')"
    [ -n "$VERSION" ] || die "Could not read the version from pom.xml."
    note "pom.xml: ${VERSION}"
}

# 这一条同时挡掉 SNAPSHOT、带后缀的版本号、以及打错的字符串。
# 放在这里而不是构建之后：越早失败，浪费的时间越少，且离打 tag 越远。
# 用 bash 内建的 [[ =~ ]] 和 case，不走管道 —— `producer | grep -q` 在
# set -o pipefail 下会因为「匹配得太早」而假失败。
check_formal_version() {
    if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        if [[ "$VERSION" == *SNAPSHOT* ]]; then
            die "The version is ${VERSION}, still a SNAPSHOT.
        Change pom.xml's <version> to the formal version before releasing.
        A -SNAPSHOT in pom.xml means work toward the next version, not that a release is due."
        fi
        die "Version ${VERSION} is not MAJOR.MINOR.PATCH."
    fi
    TAG="v${VERSION}"
}

# ---------------------------------------------------------------- tag collision

# 三道查，覆盖面不同：
#   本地 tag —— 上面的 git fetch --tags 已经把远端 tag 拉成本地的了，
#               所以实际命中的基本都是这一条，它同时覆盖了远端已有 tag 的情况。
#   ls-remote —— 兜底。防的是 fetch 没报错但也没更新（比如引用被拒绝）的情形。
#   gh release view —— 覆盖前两条查不到的一类：**草稿 release**。
#               草稿的 tag 还没推上去，git 侧完全看不见它，但正式发布时会撞名。
check_tag_free() {
    if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1; then
        die "Tag ${TAG} already exists locally. Releasing one version twice gives consumers two different artifacts."
    fi
    if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null 2>&1; then
        die "Tag ${TAG} already exists on the remote."
    fi
    if gh release view "$TAG" >/dev/null 2>&1; then
        die "A GitHub release ${TAG} already exists (possibly a draft)."
    fi
    echo "▶ Checks passed: ${TAG} is not taken"
}

# ---------------------------------------------------------------- build

build_candidate() {
    echo "▶ Verifying and building (mvn -B clean verify, including tests)"
    mvn -B clean verify
    # A build that rewrites a tracked file no longer matches the commit it claims to come from.
    if [ -n "$(git status --porcelain)" ]; then
        die "The build changed tracked files; the bytes no longer match ${LOCAL_SHA:0:12}.
$(git status --porcelain)"
    fi
    [ "$(git rev-parse HEAD)" = "$LOCAL_SHA" ] || die "HEAD moved during the build."
}

# ---------------------------------------------------------------- release notes

resolve_notes() {
    if [ -n "$NOTES_FILE" ]; then
        [ -f "$NOTES_FILE" ] || die "${NOTES_FILE} not found."
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
}

confirm() {
    if [ "$ASSUME_YES" != 1 ]; then
        printf 'Type the version %s to confirm publication: ' "$VERSION"
        read -r CONFIRM
        [ "$CONFIRM" = "$VERSION" ] || die "Not confirmed; aborted."
    fi
}

# ================================================================ --dry-run alone

if [ -z "$MODE" ]; then
    check_checkout
    read_pom_version
    check_formal_version
    check_tag_free
    build_candidate

    # 必须写全文件名。target/ 下 mvn package 会留下四个 jar：
    #   UltiTools-API-<v>.jar            ← 要的，shade 之后的
    #   original-UltiTools-API-<v>.jar   ← shade 之前的原始包
    #   UltiTools-API-<v>-sources.jar
    #   UltiTools-API-<v>-javadoc.jar
    # 用 target/*.jar 通配的话，字典序会先命中 original-，那是个没经过 shade 的包，
    # 看起来正常但缺少被重定位的依赖。
    JAR="target/UltiTools-API-${VERSION}.jar"
    [ -f "$JAR" ] || die "The build finished but ${JAR} is missing."
    # 列表一次性读进变量再用 case 匹配，不走管道（理由同上：pipefail 下 grep -q 会假失败）。
    JAR_ENTRIES="$(unzip -Z1 "$JAR")"
    case "$JAR_ENTRIES" in
        *plugin.yml*) ;;
        *) die "${JAR} has no plugin.yml; this is not a usable plugin JAR." ;;
    esac
    case "$JAR_ENTRIES" in
        *com/ultikits/ultitools/UltiTools.class*) ;;
        *) die "${JAR} has no UltiTools.class; a sources JAR may have been picked." ;;
    esac
    JAR_SIZE="$(stat -c%s "$JAR" 2>/dev/null || stat -f%z "$JAR")"
    JAR_SHA="$(sha256sum "$JAR" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$JAR" | cut -d' ' -f1)"
    echo
    echo "  version    ${VERSION}"
    echo "  tag        ${TAG}"
    echo "  branch     ${RELEASE_BRANCH} @ ${LOCAL_SHA:0:12}"
    echo "  JAR        ${JAR} (${JAR_SIZE} bytes, sha256 ${JAR_SHA})"
    echo
    echo "✅ Dry run: every check passed; no release was created."
    echo "   Dry-run output is not acceptance evidence: these bytes are not retained, and a"
    echo "   rebuild hashes differently. Use --prepare to produce the candidate for UAT."
    exit 0
fi

# ================================================================ --prepare

if [ "$MODE" = prepare ]; then
    if [ -e "$RETAIN_DIR" ] || [ -L "$RETAIN_DIR" ]; then
        die "${RETAIN_DIR} already exists; --prepare writes into a new directory only."
    fi
    check_checkout
    read_pom_version
    check_formal_version
    acquire_lock
    check_tag_free
    build_candidate

    echo "▶ Retaining the exact build output"
    MANIFEST_PATH="$(helper retain --source-checkout "$REPO_ROOT" --source-head "$LOCAL_SHA" \
        --version "$VERSION" --retain-dir "$RETAIN_DIR")" || die "The build output was refused; nothing is publishable."
    helper validate --manifest "$MANIFEST_PATH" --source-checkout "$REPO_ROOT" >/dev/null \
        || die "The retained candidate failed its own re-validation."

    echo
    echo "✅ Prepared ${TAG} from ${LOCAL_SHA}. No tag and no release were created."
    echo "   manifest   ${MANIFEST_PATH}"
    echo "   Hand the retained files and this manifest to independent acceptance. After the"
    echo "   decision, publish exactly these bytes with:"
    echo "   .github/scripts/release.sh --publish-approved <manifest> --authority-receipt <file> --uat-receipt <file>"
    exit 0
fi

# ================================================================ --publish-approved

check_checkout

FIELDS="$(helper validate --manifest "$MANIFEST" --source-checkout "$REPO_ROOT" \
    --authority-receipt "$AUTHORITY_RECEIPT" --uat-receipt "$UAT_RECEIPT" --shell)" \
    || die "The manifest, the retained bytes or the receipts were refused; nothing was published."
while IFS='=' read -r key value; do
    case "$key" in
        VERSION)          VERSION="$value" ;;
        TAG)              TAG="$value" ;;
        SOURCE_HEAD)      SOURCE_HEAD="$value" ;;
        MANIFEST_SHA256)  MANIFEST_SHA256="$value" ;;
        PRIMARY_FILENAME) PRIMARY_FILENAME="$value" ;;
        PRIMARY_BYTES)    PRIMARY_BYTES="$value" ;;
        PRIMARY_SHA256)   PRIMARY_SHA256="$value" ;;
    esac
done <<< "$FIELDS"

if [ "$LOCAL_SHA" != "$SOURCE_HEAD" ] || [ "$REMOTE_SHA" != "$SOURCE_HEAD" ]; then
    die "The accepted source is ${SOURCE_HEAD:0:12}, but ${RELEASE_BRANCH} is local ${LOCAL_SHA:0:12} / remote ${REMOTE_SHA:0:12}.
        The release must point at the accepted source; prepare and accept a new candidate instead."
fi

acquire_lock

# The commit a tag names on the remote, peeled through an annotated tag; empty when absent.
remote_tag_commit() {
    local sha ref listing plain="" peeled=""
    # Captured first, not read through a process substitution: an unreachable remote must
    # stop the run instead of looking like "no such tag".
    listing="$(git ls-remote --tags origin "refs/tags/${TAG}" "refs/tags/${TAG}^{}")" \
        || die "Could not read the remote tags; nothing was published."
    while read -r sha ref; do
        case "$ref" in
            "refs/tags/${TAG}^{}") peeled="$sha" ;;
            "refs/tags/${TAG}")    plain="$sha" ;;
        esac
    done <<< "$listing"
    printf '%s' "${peeled:-$plain}"
}

check_tags_point_at_source() {
    local local_tag remote_tag
    if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null 2>&1; then
        local_tag="$(git rev-parse "refs/tags/${TAG}^{commit}")"
        [ "$local_tag" = "$SOURCE_HEAD" ] || die "Tag ${TAG} already exists at ${local_tag:0:12}, not at the accepted ${SOURCE_HEAD:0:12}.
        One version never names two sources; nothing was published."
    fi
    remote_tag="$(remote_tag_commit)"
    if [ -n "$remote_tag" ] && [ "$remote_tag" != "$SOURCE_HEAD" ]; then
        die "The remote tag ${TAG} points at ${remote_tag:0:12}, not at the accepted ${SOURCE_HEAD:0:12}; nothing was published."
    fi
}

# Download whatever a release already holds and compare it byte for byte with the manifest.
# Sets RELEASE_EXISTS, COMPARE_STATUS, RELEASE_DRAFT and MISSING.
inspect_release() {
    local dir="$1" stage="${2:-check}" out rc
    RELEASE_EXISTS=0
    COMPARE_STATUS="absent"
    RELEASE_DRAFT="false"
    MISSING=()
    if ! gh release view "$TAG" --json tagName,targetCommitish,isDraft,assets > "${dir}.json" 2>/dev/null; then
        return 0
    fi
    RELEASE_EXISTS=1
    mkdir "$dir"
    # A release with no assets has nothing to download; compare reports any listed asset that
    # did not arrive, so a failed download can never pass as an empty release.
    gh release download "$TAG" --dir "$dir" >/dev/null 2>&1 || true
    if out="$(helper compare --manifest "$MANIFEST" --remote-dir "$dir" --release-json "${dir}.json" --shell)"; then
        rc=0
    else
        rc=$?
    fi
    if [ "$rc" != 0 ] && [ "$rc" != 3 ]; then
        [ "$stage" != readback ] || die "readback failed: the published ${TAG} differs from the accepted candidate (see the refusals above)."
        die "The existing release ${TAG} does not match the accepted candidate (see the refusals above).
        It is never replaced or clobbered; resolve it by hand. Nothing was published."
    fi
    while IFS='=' read -r key value; do
        case "$key" in
            STATUS)  COMPARE_STATUS="$value" ;;
            DRAFT)   RELEASE_DRAFT="$value" ;;
            MISSING) MISSING+=("$value") ;;
        esac
    done <<< "$out"
}

check_tags_point_at_source
inspect_release "${WORK_DIR}/existing"
resolve_notes

STAGED_LIST="$(helper stage --manifest "$MANIFEST")" || die "The retained files could not be staged."
STAGED=()
while IFS= read -r line; do
    STAGED+=("$line")
done <<< "$STAGED_LIST"

echo
echo "────────────────────────────────────────────────────────"
echo "  version     ${VERSION}"
echo "  tag         ${TAG} → ${SOURCE_HEAD}"
echo "  prerelease  $([ "$PRERELEASE" = 1 ] && echo yes || echo no)"
echo "  primary     ${PRIMARY_FILENAME} (${PRIMARY_BYTES} bytes, sha256 ${PRIMARY_SHA256})"
echo "  manifest    sha256 ${MANIFEST_SHA256}"
for path in "${STAGED[@]}"; do
    echo "  asset       $(basename "$path")"
done
echo "────────────────────────────────────────────────────────"
echo

if [ "$RELEASE_EXISTS" = 1 ]; then
    echo "  existing release ${TAG}: ${COMPARE_STATUS}, draft ${RELEASE_DRAFT}, missing ${#MISSING[@]} file(s)"
    echo "  Only identical files are kept; missing ones are uploaded; nothing is replaced."
fi

if [ "$DRY_RUN" = 1 ]; then
    echo "✅ Dry run: every re-check passed; no release was created or changed."
    echo "   Dry-run output is not acceptance evidence."
    exit 0
fi

# Re-check everything a concurrent actor could have changed, right before the first write.
# The checks above ran minutes ago (and a confirmation prompt may sit in between); a local
# lock cannot stop another machine, so the remote itself is asked again.
recheck_before_write() {
    local head
    head="$(git ls-remote origin "refs/heads/${RELEASE_BRANCH}" | cut -f1)"
    if [ "$head" != "$SOURCE_HEAD" ]; then
        die "origin/${RELEASE_BRANCH} moved to ${head:0:12} after the checks; the accepted source is ${SOURCE_HEAD:0:12}.
        Nothing was published."
    fi
    check_tags_point_at_source
    local before="$RELEASE_EXISTS:$COMPARE_STATUS:$RELEASE_DRAFT:${MISSING[*]:-}"
    inspect_release "${WORK_DIR}/recheck"
    if [ "$before" != "$RELEASE_EXISTS:$COMPARE_STATUS:$RELEASE_DRAFT:${MISSING[*]:-}" ]; then
        die "The release ${TAG} changed while this run was checking it; nothing was published. Run again."
    fi
    helper validate --manifest "$MANIFEST" --source-checkout "$REPO_ROOT" \
        --authority-receipt "$AUTHORITY_RECEIPT" --uat-receipt "$UAT_RECEIPT" >/dev/null \
        || die "The retained files or receipts changed after the checks; nothing was published."
}

# Download the published release again and require it to equal the accepted candidate.
read_back() {
    inspect_release "${WORK_DIR}/readback" readback
    if [ "$RELEASE_EXISTS" != 1 ] || [ "$COMPARE_STATUS" != identical ] || [ "$RELEASE_DRAFT" != false ]; then
        die "readback failed: ${TAG} is ${COMPARE_STATUS} (draft ${RELEASE_DRAFT}) instead of identical and published."
    fi
    [ "$(remote_tag_commit)" = "$SOURCE_HEAD" ] || die "readback failed: the remote tag ${TAG} does not point at ${SOURCE_HEAD:0:12}."
}

if [ "$RELEASE_EXISTS" = 1 ] && [ "$COMPARE_STATUS" = identical ] && [ "$RELEASE_DRAFT" = false ]; then
    read_back
    echo "✅ ${TAG} is already published from ${SOURCE_HEAD} with exactly the accepted bytes; nothing to do."
    exit 0
fi

echo "⚠ Publishing ${TAG} triggers publish-packages.yml for ${VERSION}."
echo "  Maven Central does not allow overwriting or deleting a published coordinate."
echo
confirm
recheck_before_write

if [ "$RELEASE_EXISTS" = 0 ]; then
    GH_ARGS=(release create "$TAG"
             --target "$SOURCE_HEAD"
             --title "Release ${TAG}"
             --notes "$NOTES")
    [ "$PRERELEASE" = 1 ] && GH_ARGS+=(--prerelease)
    echo "▶ Creating the release with the retained files"
    gh "${GH_ARGS[@]}" "${STAGED[@]}" || die "Creating ${TAG} failed or was interrupted.
        Run the same --publish-approved command again: it keeps identical assets, uploads the
        missing ones and never replaces a different file."
else
    echo "▶ Completing the interrupted release ${TAG}"
    UPLOAD=()
    for path in "${STAGED[@]}"; do
        for name in "${MISSING[@]:-}"; do
            [ "$(basename "$path")" = "$name" ] && UPLOAD+=("$path")
        done
    done
    if [ "${#UPLOAD[@]}" -gt 0 ]; then
        gh release upload "$TAG" "${UPLOAD[@]}" || die "Uploading the missing files failed; run the same command again."
    fi
    if [ "$RELEASE_DRAFT" = true ]; then
        gh release edit "$TAG" --draft=false || die "Publishing the draft failed; run the same command again."
    fi
fi

read_back

echo
echo "✅ ${TAG} published from ${SOURCE_HEAD} with the accepted bytes (read back and compared)"
echo "   primary sha256 ${PRIMARY_SHA256}"
