#!/usr/bin/env bash
#
# 检查 GPG 签名密钥的剩余有效期。
#
# 为什么需要它：密钥过期是静默的。日常 mvn test / mvn package 都不签名，
# 只有发版当天真正执行 gpg:sign 时才会失败，而那时报的是
#     gpg: no default secret key: No secret key
# ——说的是「找不到私钥」，不是「密钥已过期」。私钥其实好端端在钥匙串里，
# 只是 GPG 挑默认密钥时会跳过已过期的。照那条报错排查很容易误判为密钥丢失
# 而去重新生成，正确处置是延长有效期。
#
# 用法：
#   check-gpg-expiry.sh --mode secret [--warn-days N] [--fail-on-warn]
#   check-gpg-expiry.sh --mode public [--warn-days N] [--fail-on-warn]
#
# 退出码：0 = 有效期充足；1 = 已过期 / 钥匙串里没有可签名的密钥 /
#         剩余不足且指定了 --fail-on-warn
#
set -euo pipefail

MODE=secret
WARN_DAYS=30
FAIL_ON_WARN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --mode)         MODE="$2"; shift 2 ;;
        --warn-days)    WARN_DAYS="$2"; shift 2 ;;
        --fail-on-warn) FAIL_ON_WARN=1; shift ;;
        *) echo "未知参数：$1" >&2; exit 64 ;;
    esac
done

case "$MODE" in
    secret) LIST_CMD=(gpg --list-secret-keys --with-colons); WANT="sec|ssb" ;;
    public) LIST_CMD=(gpg --list-keys        --with-colons); WANT="pub|sub" ;;
    *) echo "--mode 只能是 secret 或 public" >&2; exit 64 ;;
esac

# --with-colons 的字段：1=记录类型 5=key id 7=到期时间戳 12=能力
# 能力字段里小写字母描述「这一把钥匙自己」的能力，大写描述「整把密钥」的。
# 所以要找真正能签名的，看的是小写 s。
# 到期时间戳为空 = 永不过期。
RECORDS=$("${LIST_CMD[@]}" 2>/dev/null | awk -F: -v want="$WANT" '
    $1 ~ "^(" want ")$" && $12 ~ /s/ { print $5 ":" $7 }
') || true

if [ -z "$RECORDS" ]; then
    echo "::error::钥匙串里没有任何可签名的 GPG 密钥（mode=$MODE）。"
    echo "        如果这是发布流程，说明密钥根本没导入成功，而不是密钥过期。"
    exit 1
fi

NOW=$(date -u +%s)
BEST_TS=-1      # 所有可签名密钥里最晚的到期时间；-1 = 还没找到
BEST_ID=""
NEVER=0

while IFS=: read -r kid expire; do
    if [ -z "$expire" ]; then
        NEVER=1; BEST_ID="$kid"; break
    fi
    if [ "$expire" -gt "$BEST_TS" ]; then
        BEST_TS="$expire"; BEST_ID="$kid"
    fi
done <<< "$RECORDS"

SHORT_ID="${BEST_ID: -16}"

if [ "$NEVER" = 1 ]; then
    echo "密钥 ${SHORT_ID} 未设置到期时间，无需预警。"
    exit 0
fi

EXPIRE_DATE=$(date -u -d "@${BEST_TS}" +%Y-%m-%d)
DAYS_LEFT=$(( (BEST_TS - NOW) / 86400 ))

# 这里刻意只打印 key id 与日期，不打印 uid——uid 里有维护者邮箱，
# 而这是公开仓库的公开日志。
RENEW_HINT="延期办法：gpg --quick-set-expire <指纹> 2y，然后 gpg --armor --export-secret-keys <指纹>
        重新写入 MAVEN_GPG_PRIVATE_KEY secret，并把公钥重新上传到 keyserver
        （gpg --keyserver keyserver.ubuntu.com --send-keys <指纹>）。不要重新生成密钥。"

if [ "$BEST_TS" -le "$NOW" ]; then
    echo "::error::GPG 签名密钥 ${SHORT_ID} 已于 ${EXPIRE_DATE} 过期。"
    echo "        注意：这不是密钥丢失。GPG 挑默认密钥时会跳过已过期的，所以真正签名时"
    echo "        报的是 no default secret key，容易被误判成密钥没导入。"
    echo "        ${RENEW_HINT}"
    exit 1
fi

if [ "$DAYS_LEFT" -lt "$WARN_DAYS" ]; then
    echo "::warning::GPG 签名密钥 ${SHORT_ID} 将于 ${EXPIRE_DATE} 过期，仅剩 ${DAYS_LEFT} 天。"
    echo "        ${RENEW_HINT}"
    if [ "$FAIL_ON_WARN" = 1 ]; then
        exit 1
    fi
    exit 0
fi

echo "GPG 签名密钥 ${SHORT_ID} 有效期至 ${EXPIRE_DATE}，剩余 ${DAYS_LEFT} 天。"
exit 0
