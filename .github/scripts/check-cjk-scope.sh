#!/usr/bin/env bash
#
# check-cjk-scope.sh — D-11's CJK gate (GATE-02's machine-checkable acceptance criterion).
#
# GATE-02's original wording — "no Chinese-only comment without English remains" — cannot be
# checked by a machine. "Zero CJK inside the covered scope, outside a three-item allowlist" can.
# This script IS that check.
#
# Detection contract (do not widen this without also updating the allowlist and this comment):
#   The range checked is Unicode U+4E00 through U+9FFF (the CJK Unified Ideographs block),
#   matched with `grep -P '[\x{4e00}-\x{9fff}]'`. This is deliberately the SAME range
#   CONTEXT.md's 5,210-line measurement used (`grep -lP '[\x{4e00}-\x{9fff}]'` over src/main/**/*.java),
#   so this gate's count and that measurement can never silently diverge.
#
#   Explicitly OUT of the contract:
#     - Full-width punctuation and forms      U+FF00-U+FFEF (e.g. full-width '！', '？', '（', '）')
#     - CJK Symbols and Punctuation           U+3000-U+303F (e.g. '。', '、')
#     - Hiragana / Katakana (kana)            U+3040-U+30FF
#     - CJK Unified Ideographs Extension A    U+3400-U+4DBF
#     - CJK Unified Ideographs Extension B+   U+20000 and upward (supplementary plane)
#   Widening the range is a deliberate edit, not a silent drift — --self-test pins this boundary
#   (assertion 4: a kana-only or full-width-punctuation-only line must report zero violations).
#
#   The script forces a UTF-8 locale before any matching so the range behaves identically on a
#   runner whose default locale is C/POSIX, where grep's byte-oriented matching would otherwise
#   split multi-byte UTF-8 characters instead of matching whole code points.
#
# Scope enumeration:
#   Candidate files come from `git ls-files -- <scope>`, never a bare `find` or a recursive `grep`
#   over the working tree — this keeps untracked and gitignored paths out of the scan and makes
#   the file list deterministic. Default scope is three areas: src/main/java, .github/workflows,
#   and the buildtools test package (src/test/java/com/ultikits/ultitools/buildtools).
#
#   Scope correction (08-17, "08-SAME-LINE-CJK-RESIDUE.md" DECISIVE MEASUREMENT section): the
#   default scope used to be src/main (which also pulls in src/main/resources). GATE-02 and
#   ROADMAP criterion 2 are about "src/main comments and javadoc ... and the published
#   sources/javadoc jars carry that form" — an argument about the published Java artifacts.
#   src/main/resources/config.yml and plugin.yml ship in neither jar, and config.yml specifically
#   mixes genuinely-legacy Chinese-only comments with comment lines the framework itself writes
#   at runtime from Capability.getCommentLines() (deliberately bilingual, matching the three
#   already-allowlisted UltiTools.java constants' pattern) — converting the whole file would
#   either miss that runtime-generated portion or contradict it. Narrowed to src/main/java, which
#   is unambiguously in criterion 2's scope and contains no such runtime-generated content.
#   This is a deliberate scope narrowing, not a silent one — see the commit that made this change.
#
#   Two structural exclusions apply regardless of --scope:
#     - lang/*.json catalogue files (src/main/resources/lang/{en,zh}.json) are never scanned.
#       These are i18n message catalogues, not documentation or comments — the root monorepo
#       CLAUDE.md names them as a hard, project-wide exemption category alongside contributor
#       names and target-language link labels. D-08/D-11's three-item allowlist covers the
#       *i18n("...") call-site literals* in Java source; the catalogue files themselves are a
#       different thing and are excluded here at the enumeration level rather than via an
#       allowlist entry, so the "exactly three allowlist entries" invariant stays intact.
#     - .github/scripts/testdata/ (this script's own fixtures) is always excluded. The fixtures
#       under that directory contain CJK by design, to prove the gate fires and to prove it
#       exempts — if they were in scope they would permanently fail the gate they exist to test.
#
# String-literal exclusion for .java files (08-17, "DECISIVE MEASUREMENT" section of
# "08-SAME-LINE-CJK-RESIDUE.md"): GATE-02 and ROADMAP criterion 2 are about *comments and
# javadoc*: "src/main comments and javadoc are English-first ... and the published sources/javadoc
# jars carry that form." A CJK string literal is neither a comment nor javadoc, is not in either
# published jar, and this phase's own rule R2/C-03 (08-BATCH-CONVERSION-RULES.md,
# 08-CONTROL-DOC-CORRECTIONS.md) already treats translating one as a behaviour change out of
# scope, since it may be user-facing output that belongs in lang/*.json instead. The scanner
# previously counted string-literal CJK anyway, because it never distinguished a comment line from
# a string line — this measured 158 such violations across the repository before this fix, which
# is the gate measuring a superset of what GATE-02 actually requires.
#
# is_comment_or_javadoc_line() (below) makes that distinction for .java files only (YAML has no
# comparable code/string split worth making — a workflow's echoed step-summary text is still
# genuinely in scope, per 08-17's own conversion of maven-ci.yml). A CJK-containing line counts as
# a violation when EITHER: the line, stripped of leading whitespace, starts with `//`, `/*`, or
# `*` (a whole comment/javadoc line); OR the line contains a `//` that is not inside a
# double-quoted string literal, with CJK appearing at or after that position (a same-line trailing
# comment). Otherwise the CJK is inside a string literal only, and is excluded. This is the same
# quote-tracking approach as check-comment-only-diff.sh's find_code_half() — kept as an
# independent implementation here rather than sourcing that script, since the two tools have
# different processes, different failure semantics, and no shared runtime today.
#
# Allowlist format: .github/cjk-allowlist.txt, one `<path-glob>:<extended-regex>` pair per
# non-comment line, split on the FIRST colon. An entry exempts a LINE, not a file — the path glob
# still lets every other line in that file fail the gate. See that file's own header for the
# three authorised entries.
#
# Usage:
#   check-cjk-scope.sh [--allowlist <path>] [--scope <path> ...]
#                       [--report-only] [--max-violations <n>] [--self-test]
#
#   --allowlist <path>     Path to the allowlist file (default: .github/cjk-allowlist.txt)
#   --scope <path>         Repeatable. Overrides the three default scopes entirely when given.
#   --report-only          Print violations, always exit 0 (for measurement/inspection).
#   --max-violations <n>   Exit 0 while the violation count is at or below n (migration aid;
#                           lets the gate be introduced before the conversion finishes). Default 0.
#   --self-test             Run the fixture self-test (see .github/scripts/testdata/) and exit.
#
# Exit codes: 0 = at or under the threshold (or --report-only); 1 = over threshold;
#             64 = usage error (unknown flag).
#
# NOT wired into maven-ci.yml yet — see D-20. The gate turns on only after GATE-02 batch 4 lands,
# because a gate that is red for the whole conversion trains everyone to ignore it.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Locale: force UTF-8 so the Unicode range in CJK_RANGE matches whole code
# points rather than raw bytes, regardless of the runner's ambient locale.
# ---------------------------------------------------------------------------
if locale -a 2>/dev/null | grep -qiE '^C\.utf-?8$'; then
    export LC_ALL=C.UTF-8
elif locale -a 2>/dev/null | grep -qiE '^en_US\.utf-?8$'; then
    export LC_ALL=en_US.UTF-8
else
    # Neither is listed by `locale -a` on this runner. Force C.UTF-8 anyway as the best-effort
    # choice — most modern glibc systems honor it even when locale -a does not enumerate it.
    export LC_ALL=C.UTF-8
fi

# The sole detection contract. See the header above before touching this.
CJK_RANGE='[\x{4e00}-\x{9fff}]'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"

ALLOWLIST=".github/cjk-allowlist.txt"
SCOPES=()
REPORT_ONLY=0
MAX_VIOLATIONS=0
SELF_TEST=0

usage() {
    cat >&2 <<'USAGE'
Usage: check-cjk-scope.sh [--allowlist <path>] [--scope <path> ...]
                           [--report-only] [--max-violations <n>] [--self-test]
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --allowlist)      ALLOWLIST="$2"; shift 2 ;;
        --scope)          SCOPES+=("$2"); shift 2 ;;
        --report-only)    REPORT_ONLY=1; shift ;;
        --max-violations) MAX_VIOLATIONS="$2"; shift 2 ;;
        --self-test)      SELF_TEST=1; shift ;;
        -h|--help)        usage; exit 0 ;;
        *)
            echo "Unknown flag: $1" >&2
            usage
            exit 64
            ;;
    esac
done

if [ ${#SCOPES[@]} -eq 0 ]; then
    SCOPES=(
        "src/main/java"
        ".github/workflows"
        "src/test/java/com/ultikits/ultitools/buildtools"
    )
fi

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# Allowlist
# ---------------------------------------------------------------------------
ALLOW_GLOBS=()
ALLOW_REGEXES=()

load_allowlist() {
    local file="$1"
    ALLOW_GLOBS=()
    ALLOW_REGEXES=()
    if [ ! -f "$file" ]; then
        # No allowlist file at all: zero exemptions, fail-closed. Not an error — every found
        # CJK line simply becomes a violation.
        return 0
    fi
    local line trimmed glob regex
    while IFS= read -r line || [ -n "$line" ]; do
        trimmed="${line#"${line%%[![:space:]]*}"}"
        [ -z "$trimmed" ] && continue
        case "$trimmed" in
            '#'*) continue ;;
        esac
        glob="${trimmed%%:*}"
        regex="${trimmed#*:}"
        ALLOW_GLOBS+=("$glob")
        ALLOW_REGEXES+=("$regex")
    done < "$file"
    return 0
}

# is_allowlisted PATH TEXT — true (0) when TEXT at PATH matches an allowlist entry.
is_allowlisted() {
    local path="$1" text="$2" i
    for i in "${!ALLOW_GLOBS[@]}"; do
        case "$path" in
            ${ALLOW_GLOBS[$i]})
                if grep -Eq -- "${ALLOW_REGEXES[$i]}" <<< "$text"; then
                    return 0
                fi
                ;;
        esac
    done
    return 1
}

# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------

# is_comment_or_javadoc_line TEXT — true (0) when TEXT (already known to contain CJK) is
# comment/javadoc content per GATE-02's actual criterion; false (1) when the CJK is inside a
# string literal only. See the header note above ("String-literal exclusion for .java files").
is_comment_or_javadoc_line() {
    local text="$1"
    local stripped="${text#"${text%%[![:space:]]*}"}"
    case "$stripped" in
        '//'* | '/*'* | '*'*) return 0 ;;
    esac

    # Find the first `//` that is not inside a double-quoted string literal (simple
    # backslash-escape aware, same approach as check-comment-only-diff.sh's find_code_half()).
    local i=0 len=${#text} ch nextch in_string=0 escape=0 comment_start=-1
    while [ "$i" -lt "$len" ]; do
        ch="${text:$i:1}"
        if [ "$in_string" -eq 1 ]; then
            if [ "$escape" -eq 1 ]; then
                escape=0
            elif [ "$ch" = '\' ]; then
                escape=1
            elif [ "$ch" = '"' ]; then
                in_string=0
            fi
        else
            if [ "$ch" = '"' ]; then
                in_string=1
            elif [ "$ch" = '/' ]; then
                nextch="${text:$((i + 1)):1}"
                if [ "$nextch" = '/' ]; then
                    comment_start=$i
                    break
                fi
            fi
        fi
        i=$((i + 1))
    done

    if [ "$comment_start" -ge 0 ]; then
        local after="${text:$comment_start}"
        if printf '%s' "$after" | grep -qP "$CJK_RANGE"; then
            return 0
        fi
    fi
    return 1
}

# scan_file PATH — prints "PATH:LINENO:TEXT" for every CJK line at PATH that is not allowlisted
# and (for .java files) is comment/javadoc content rather than a pure string-literal occurrence.
scan_file() {
    local path="$1"
    grep -nP "$CJK_RANGE" -- "$path" 2>/dev/null | while IFS=: read -r lineno text; do
        is_allowlisted "$path" "$text" && continue
        case "$path" in
            *.java)
                is_comment_or_javadoc_line "$text" || continue
                ;;
        esac
        printf '%s:%s:%s\n' "$path" "$lineno" "$text"
    done || true
    return 0
}

# run_scan — enumerates the current SCOPES via `git ls-files` and prints violations for every
# candidate file, applying the two structural exclusions (lang/*.json catalogues, this script's
# own testdata fixtures). Prints an explicit note to stderr for any scope matching zero tracked
# files — an empty scope is never a silent pass.
run_scan() {
    local scope f files
    for scope in "${SCOPES[@]}"; do
        files=()
        mapfile -t files < <(git ls-files -- "$scope" 2>/dev/null || true)
        if [ ${#files[@]} -eq 0 ]; then
            echo "NOTE: scope '${scope}' matched zero tracked files." >&2
            continue
        fi
        for f in "${files[@]}"; do
            case "$f" in
                */lang/en.json | */lang/zh.json) continue ;;
                .github/scripts/testdata/*) continue ;;
            esac
            scan_file "$f"
        done
    done
    return 0
}

# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------
run_self_test() {
    local failures=0
    local violating_fixture=".github/scripts/testdata/cjk-fixture-violating.txt"
    local allowlisted_fixture=".github/scripts/testdata/cjk-fixture-allowlisted.txt"

    # Assertion 1: the violating fixture yields exactly one violation, with no allowlist active.
    ALLOW_GLOBS=()
    ALLOW_REGEXES=()
    local n1
    n1=$(scan_file "$violating_fixture" | grep -c . || true)
    if [ "$n1" -eq 1 ]; then
        echo "PASS: assertion 1 — violating fixture yields exactly 1 violation."
    else
        echo "FAIL: assertion 1 — violating fixture yields exactly 1 violation (got ${n1})."
        failures=1
    fi

    # Assertion 2: a temporary in-memory allowlist exempting only the allowlisted fixture's first
    # CJK line leaves exactly one violation (the unmatched line, not the exempted one).
    ALLOW_GLOBS=("$allowlisted_fixture")
    ALLOW_REGEXES=('会被本次自测的临时白名单豁免')
    local n2
    n2=$(scan_file "$allowlisted_fixture" | grep -c . || true)
    ALLOW_GLOBS=()
    ALLOW_REGEXES=()
    if [ "$n2" -eq 1 ]; then
        echo "PASS: assertion 2 — allowlist exempts the matched line, leaves the other as a violation."
    else
        echo "FAIL: assertion 2 — allowlist exempts the matched line, leaves the other as a violation (got ${n2})."
        failures=1
    fi

    # Assertion 3: a scope matching zero tracked files yields zero violations, with the explicit
    # empty-scope message printed — never a silent pass.
    local saved_scopes=("${SCOPES[@]}")
    SCOPES=("no/such/scope/at/all/for/self/test")
    local stderr_capture
    stderr_capture="$(mktemp)"
    local n3
    n3=$(run_scan 2>"$stderr_capture" | grep -c . || true)
    local msg3
    msg3="$(cat "$stderr_capture")"
    rm -f "$stderr_capture"
    SCOPES=("${saved_scopes[@]}")
    if [ "$n3" -eq 0 ] && printf '%s' "$msg3" | grep -q "matched zero tracked files"; then
        echo "PASS: assertion 3 — empty scope yields zero violations with an explicit message."
    else
        echo "FAIL: assertion 3 — empty scope yields zero violations with an explicit message."
        failures=1
    fi

    # Assertion 4: the encoding boundary itself — kana and full-width punctuation are OUTSIDE the
    # U+4E00-U+9FFF contract and must not match, pinning the range against silent future widening.
    local kana='ひらがなカタカナ'
    local fullwidth='。、！？（）'
    if printf '%s\n%s\n' "$kana" "$fullwidth" | grep -P "$CJK_RANGE" > /dev/null; then
        echo "FAIL: assertion 4 — kana/full-width punctuation must not match U+4E00-U+9FFF."
        failures=1
    else
        echo "PASS: assertion 4 — kana/full-width punctuation must not match U+4E00-U+9FFF."
    fi

    # Assertions 5-7 (08-17): is_comment_or_javadoc_line()'s three directions. A whole comment
    # line and a same-line trailing comment must still be counted (the string-literal exclusion
    # must not over-correct into silently passing real comment debt); a CJK string literal alone
    # must be excluded (the actual fix).
    if is_comment_or_javadoc_line '     * 中文注释'; then
        echo "PASS: assertion 5 — a whole javadoc/comment line is still counted."
    else
        echo "FAIL: assertion 5 — a whole javadoc/comment line is still counted."
        failures=1
    fi

    if is_comment_or_javadoc_line '        return 5; // 中文尾注'; then
        echo "PASS: assertion 6 — a same-line trailing comment is still counted."
    else
        echo "FAIL: assertion 6 — a same-line trailing comment is still counted."
        failures=1
    fi

    if is_comment_or_javadoc_line '        String s = "中文字符串";'; then
        echo "FAIL: assertion 7 — a CJK string literal (no comment on the line) is excluded."
        failures=1
    else
        echo "PASS: assertion 7 — a CJK string literal (no comment on the line) is excluded."
    fi

    return "$failures"
}

if [ "$SELF_TEST" -eq 1 ]; then
    run_self_test
    exit $?
fi

# ---------------------------------------------------------------------------
# Main scan
# ---------------------------------------------------------------------------
load_allowlist "$ALLOWLIST"

VIOLATIONS_RAW="$(mktemp)"
trap 'rm -f "$VIOLATIONS_RAW"' EXIT

run_scan > "$VIOLATIONS_RAW"

VIOLATIONS_SORTED="$(mktemp)"
sort -t: -k1,1 -k2,2n "$VIOLATIONS_RAW" > "$VIOLATIONS_SORTED" 2>/dev/null || true

TOTAL=$(wc -l < "$VIOLATIONS_SORTED" | tr -d ' ')
FILES=$(cut -d: -f1 "$VIOLATIONS_SORTED" | sort -u | wc -l | tr -d ' ')

if [ "$TOTAL" -gt 0 ]; then
    # One GitHub Actions error annotation per violating FILE (not per line) so a large batch stays
    # readable, then the full sorted list to stdout for anyone reading the raw log.
    cut -d: -f1 "$VIOLATIONS_SORTED" | sort -u | while IFS= read -r vf; do
        echo "::error file=${vf}::CJK character(s) found outside the allowed scope (see .github/cjk-allowlist.txt)"
    done
    cat "$VIOLATIONS_SORTED"
fi

echo "SUMMARY: ${TOTAL} CJK violation(s) across ${FILES} file(s)."

rm -f "$VIOLATIONS_RAW" "$VIOLATIONS_SORTED"
trap - EXIT

if [ "$REPORT_ONLY" -eq 1 ]; then
    exit 0
fi

if [ "$TOTAL" -gt "$MAX_VIOLATIONS" ]; then
    exit 1
fi

exit 0
