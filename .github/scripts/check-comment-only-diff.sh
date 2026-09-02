#!/usr/bin/env bash
#
# check-comment-only-diff.sh — D-21's substitute for per-PR real-machine UAT on GATE-02's four
# comment-conversion batches (see 08-CONTEXT.md D-08 through D-11 and D-21).
#
# This check does NOT replace real-machine UAT in general. It replaces it for exactly the four
# comment-conversion batch PRs, because the two hazards a real-machine pass would normally catch
# in this repository — an accidental edit inside one of the 98 `i18n("...")` functional keys, and
# a CRLF/LF normalisation across a 90-CRLF / 210-LF tree — are both mechanically decidable and are
# exactly what a human tester detects worst (a broken i18n key surfaces only if the tester happens
# to trigger that specific message). Every other lane in this phase (GATE-03, the gate lane,
# GATE-04, GATE-05/06) keeps full per-PR real-machine UAT.
#
# Three invariants are asserted over a commit range, restricted to --scope:
#
#   A. Comments-only.   Every added/removed line in the diff is comment-shaped (after stripping
#                        leading whitespace it starts with `//`, `/*`, or `*` — which also covers
#                        the closing `*/`), or is blank.
#   B. i18n set stable.  The multiset of `i18n("...")` string literals found anywhere in each
#                        scope's files (comment or code — this is a blind text extraction, not an
#                        AST) is identical at <base> and at <head>.
#   C. Line endings stable. For every file touched by the diff, whether the blob at <base> and the
#                        blob at <head> contains a carriage return is identical.
#
# Known limitation of assertion A (deliberate, not a bug): the classifier is line-shaped, not a
# Java tokenizer. A changed line that does not itself start with a comment marker but sits inside
# a `/* ... */` block that was opened on an earlier, UNCHANGED line cannot be told apart from a
# genuine non-comment change by looking at that one line alone. Rather than guess, this script
# scans each candidate file's blob up to the changed line to see whether a block comment is
# already open at that point; when it is, the line is reported as a REFUSAL (distinct from a
# VIOLATION) asking the author to re-split the commit so the change lands on an isolated,
# self-evidently-comment-shaped line. A refusal still fails the overall check — it is a "cannot
# decide", never a silent pass.
#
# Usage:
#   check-comment-only-diff.sh --base <ref> [--head <ref>] [--scope <path> ...]
#   check-comment-only-diff.sh --self-test
#
#   --base <ref>    Required (unless --self-test). The ref the diff is measured from (the
#                    pre-batch state).
#   --head <ref>    Optional, default HEAD. The ref the diff is measured to.
#   --scope <path>  Repeatable. Restricts every assertion to these pathspecs.
#                    Default: src/main/java
#   --self-test     Run the fixture self-test for assertion C's is_crlf() (see below) and exit.
#                    Ignores --base/--head/--scope. Follows the same --self-test convention as
#                    check-cjk-scope.sh.
#
# Exit codes: 0 = all three invariants hold (including the empty-range case), or --self-test
#             passed; 1 = at least one invariant violated, a line could not be classified, or
#             --self-test failed; 64 = usage error (unknown flag, or --base missing without
#             --self-test).
#
# assertion C / is_crlf() pipefail note (WINDOWS.md #28, 08-CONTROL-DOC-CORRECTIONS.md C-06):
# `is_crlf` used to be `git show REF:PATH | grep -qa $'\r'`. Under `set -o pipefail`, `grep -q`
# exits at its FIRST match — line 1 of a CRLF file — while `git show` may still be writing a
# large blob. If `git show` then takes SIGPIPE it exits 141, pipefail propagates that as the
# pipeline's status, and the old `is_crlf` returned false: a genuinely-CRLF file was reported as
# LF. The dangerous direction is a false NEGATIVE — if the same misread hits both the base and
# head blobs they compare equal and no violation is reported, so a real CRLF->LF regression can
# pass silently. Fixed by using `grep -c`, which drains the whole stream and therefore never lets
# grep exit before `git show` finishes writing. `--self-test` proves both directions on a
# synthetic blob larger than a 64 KiB pipe buffer: a genuinely-CRLF blob is still reported as
# CRLF, and a genuine CRLF->LF change is still caught as a violation.
#
set -euo pipefail

BASE_REF=""
HEAD_REF="HEAD"
SCOPES=()
SELF_TEST=0

usage() {
    cat >&2 <<'USAGE'
Usage: check-comment-only-diff.sh --base <ref> [--head <ref>] [--scope <path> ...]
       check-comment-only-diff.sh --self-test
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --base)      BASE_REF="$2"; shift 2 ;;
        --head)      HEAD_REF="$2"; shift 2 ;;
        --scope)     SCOPES+=("$2"); shift 2 ;;
        --self-test) SELF_TEST=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "Unknown flag: $1" >&2
            usage
            exit 64
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if [ "$SELF_TEST" -eq 0 ]; then
    if [ -z "$BASE_REF" ]; then
        echo "Missing required flag: --base" >&2
        usage
        exit 64
    fi

    if [ ${#SCOPES[@]} -eq 0 ]; then
        SCOPES=("src/main/java")
    fi
fi

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

# blob_exists REF PATH — true (0) when PATH exists as a blob at REF.
blob_exists() {
    git cat-file -e "${1}:${2}" 2>/dev/null
}

# is_comment_shaped TEXT — true (0) when TEXT, after stripping leading whitespace, is empty or
# begins with a line-comment marker, a block-comment opener, or a `*`/`*/` continuation line.
is_comment_shaped() {
    local text="$1" stripped
    stripped="${text#"${text%%[![:space:]]*}"}"
    if [ -z "$stripped" ]; then
        return 0
    fi
    case "$stripped" in
        '//'* | '/*'* | '*'*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---------------------------------------------------------------------------
# Assertion A support: per-file block-comment-open state cache.
#
# get_state_cache_file REF PATH prints the path to a temp file with one line per source line,
# each holding 0 or 1 for whether a block comment is already open ENTERING that line (i.e. before
# that line's own content is scanned). Built once per (ref, path) pair and memoized.
# ---------------------------------------------------------------------------

declare -A STATE_CACHE_FILE
STATE_CACHE_TMP_FILES=()

get_state_cache_file() {
    local ref="$1" path="$2" key
    key="${ref}:::${path}"
    if [ -n "${STATE_CACHE_FILE[$key]:-}" ]; then
        printf '%s' "${STATE_CACHE_FILE[$key]}"
        return 0
    fi
    local tmpf
    tmpf="$(mktemp)"
    STATE_CACHE_TMP_FILES+=("$tmpf")
    if ! blob_exists "$ref" "$path"; then
        STATE_CACHE_FILE[$key]="$tmpf"
        printf '%s' "$tmpf"
        return 0
    fi
    local state=0 remaining
    while IFS= read -r srcline || [ -n "$srcline" ]; do
        # Record the state ENTERING this line, before scanning its own content.
        printf '%s\n' "$state" >> "$tmpf"
        remaining="$srcline"
        while true; do
            if [ "$state" -eq 0 ]; then
                case "$remaining" in
                    *'/*'*) remaining="${remaining#*/\*}"; state=1 ;;
                    *) break ;;
                esac
            else
                case "$remaining" in
                    *'*/'*) remaining="${remaining#*\*/}"; state=0 ;;
                    *) break ;;
                esac
            fi
        done
    done < <(git show "${ref}:${path}" 2>/dev/null)
    STATE_CACHE_FILE[$key]="$tmpf"
    printf '%s' "$tmpf"
}

# is_inside_open_block_comment REF PATH LINE_NO — true (0) when a block comment opened before
# LINE_NO (per the cache above) and has not yet closed.
is_inside_open_block_comment() {
    local ref="$1" path="$2" line_no="$3" cache_file state
    cache_file="$(get_state_cache_file "$ref" "$path")"
    state="$(sed -n "${line_no}p" "$cache_file" 2>/dev/null || true)"
    [ "$state" = "1" ]
}

cleanup_state_cache() {
    local exit_code=$?
    local f
    for f in "${STATE_CACHE_TMP_FILES[@]:-}"; do
        [ -n "$f" ] && rm -f "$f"
    done
    exit "$exit_code"
}
trap cleanup_state_cache EXIT

# ---------------------------------------------------------------------------
# Assertion A: comments-only over the diff.
# ---------------------------------------------------------------------------

ASSERTION_A_VIOLATIONS=()
ASSERTION_A_REFUSALS=()

run_assertion_a() {
    local diff_out cur_old_path="" cur_new_path="" old_line=0 new_line=0
    local hunk_info old_spec new_spec content

    diff_out="$(git diff --no-color --unified=0 "${BASE_REF}..${HEAD_REF}" -- "${SCOPES[@]}" 2>/dev/null || true)"

    while IFS= read -r dline; do
        case "$dline" in
            'diff --git '*)
                cur_old_path=""
                cur_new_path=""
                ;;
            '--- a/'*)
                cur_old_path="${dline#--- a/}"
                ;;
            '--- /dev/null')
                cur_old_path="/dev/null"
                ;;
            '+++ b/'*)
                cur_new_path="${dline#+++ b/}"
                ;;
            '+++ /dev/null')
                cur_new_path="/dev/null"
                ;;
            '@@ '*)
                hunk_info="${dline#@@ }"
                hunk_info="${hunk_info%% @@*}"
                old_spec="${hunk_info%% *}"
                new_spec="${hunk_info#* }"
                old_line="${old_spec#-}"
                old_line="${old_line%%,*}"
                new_line="${new_spec#+}"
                new_line="${new_line%%,*}"
                ;;
            '+'*)
                content="${dline#+}"
                if [ "$cur_new_path" != "/dev/null" ] && [ -n "$cur_new_path" ]; then
                    classify_a_line "$cur_new_path" "$new_line" "$content" "$HEAD_REF"
                fi
                new_line=$((new_line + 1))
                ;;
            '-'*)
                content="${dline#-}"
                if [ "$cur_old_path" != "/dev/null" ] && [ -n "$cur_old_path" ]; then
                    classify_a_line "$cur_old_path" "$old_line" "$content" "$BASE_REF"
                fi
                old_line=$((old_line + 1))
                ;;
            *) ;;
        esac
    done <<< "$diff_out"
}

# classify_a_line PATH LINE_NO CONTENT REF — records a violation or a refusal for one changed
# line that is not comment-shaped.
classify_a_line() {
    local path="$1" line_no="$2" content="$3" ref="$4"
    if is_comment_shaped "$content"; then
        return 0
    fi
    if is_inside_open_block_comment "$ref" "$path" "$line_no"; then
        ASSERTION_A_REFUSALS+=("${path}:${line_no}: change lands inside a block comment opened on an earlier unchanged line — classifier cannot decide; re-split this commit so the change is isolated to a self-evidently comment-shaped line.")
    else
        ASSERTION_A_VIOLATIONS+=("${path}:${line_no}: non-comment change")
    fi
}

# ---------------------------------------------------------------------------
# Assertion B: i18n("...") literal multiset unchanged across each scope.
# ---------------------------------------------------------------------------

ASSERTION_B_VIOLATIONS=()

extract_i18n_literals() {
    local ref="$1" path
    while IFS= read -r path; do
        [ -z "$path" ] && continue
        git show "${ref}:${path}" 2>/dev/null | grep -oP 'i18n\("[^"]*"\)' || true
    done < <(git ls-tree -r --name-only "$ref" -- "${SCOPES[@]}" 2>/dev/null || true)
}

run_assertion_b() {
    local base_list head_list diff_out
    base_list="$(mktemp)"
    head_list="$(mktemp)"
    extract_i18n_literals "$BASE_REF" | sort > "$base_list"
    extract_i18n_literals "$HEAD_REF" | sort > "$head_list"
    diff_out="$(diff "$base_list" "$head_list" || true)"
    rm -f "$base_list" "$head_list"
    if [ -z "$diff_out" ]; then
        return 0
    fi
    while IFS= read -r dl; do
        case "$dl" in
            '< '*) ASSERTION_B_VIOLATIONS+=("removed: ${dl#< }") ;;
            '> '*) ASSERTION_B_VIOLATIONS+=("added:   ${dl#> }") ;;
        esac
    done <<< "$diff_out"
}

# ---------------------------------------------------------------------------
# Assertion C: per-file line-ending type (CRLF vs LF) unchanged.
# ---------------------------------------------------------------------------

ASSERTION_C_VIOLATIONS=()
ASSERTION_C_NOTES=()

is_crlf() {
    # true (0) when REF:PATH contains at least one carriage return.
    #
    # Deliberately `grep -c`, not `grep -q`: `-q` exits at the first match, which on a large CRLF
    # blob can SIGPIPE `git show` before it finishes writing under `set -o pipefail`, silently
    # misreporting a genuinely-CRLF file as LF (see the header note above, WINDOWS.md #28,
    # 08-CONTROL-DOC-CORRECTIONS.md C-06). `-c` drains the whole stream, so there is no early
    # exit and therefore no SIGPIPE race — correctness does not depend on scheduling.
    local count
    count="$(git show "${1}:${2}" 2>/dev/null | grep -ca $'\r' || true)"
    [ "${count:-0}" != "0" ]
}

run_assertion_c() {
    local changed_files path base_crlf head_crlf base_desc head_desc
    changed_files="$(git diff --name-only "${BASE_REF}..${HEAD_REF}" -- "${SCOPES[@]}" 2>/dev/null || true)"

    while IFS= read -r path; do
        [ -z "$path" ] && continue

        if ! blob_exists "$BASE_REF" "$path"; then
            ASSERTION_C_NOTES+=("${path}: added at head (no base blob) — skipped")
            continue
        fi
        if ! blob_exists "$HEAD_REF" "$path"; then
            ASSERTION_C_NOTES+=("${path}: removed at head (no head blob) — skipped")
            continue
        fi

        if is_crlf "$BASE_REF" "$path"; then base_crlf=1; else base_crlf=0; fi
        if is_crlf "$HEAD_REF" "$path"; then head_crlf=1; else head_crlf=0; fi

        if [ "$base_crlf" != "$head_crlf" ]; then
            [ "$base_crlf" = 1 ] && base_desc="CRLF" || base_desc="LF"
            [ "$head_crlf" = 1 ] && head_desc="CRLF" || head_desc="LF"
            ASSERTION_C_VIOLATIONS+=("${path}: line-ending type changed ${base_desc} -> ${head_desc}")
        fi
    done <<< "$changed_files"
}

# ---------------------------------------------------------------------------
# Self-test for assertion C / is_crlf() (C-06 / WINDOWS.md #28).
#
# Builds two synthetic blobs — one genuinely CRLF, one its LF sibling with every carriage return
# stripped and nothing else changed — each larger than a 64 KiB pipe buffer, so the SIGPIPE race
# the old `grep -qa` implementation was vulnerable to is actually reachable. The blobs are wired
# into throwaway commit objects via `git hash-object -w` / `git mktree` / `git commit-tree`
# (plumbing only — no branch, ref, or working-tree change; the resulting objects are unreachable
# from any ref and are ordinary GC-eligible loose objects), then run through the real is_crlf()
# and run_assertion_c() exactly as the normal code path would.
# ---------------------------------------------------------------------------

# self_test_fixture_content FD — writes >64 KiB of comment-shaped CRLF text to the given fd.
self_test_fixture_content() {
    local i
    for i in $(seq 1 3000); do
        printf '// self-test fixture line %04d padding padding padding padding\r\n' "$i"
    done
}

run_self_test() {
    local failures=0
    local fixture_path="crlf-fixture-self-test.txt"
    local crlf_file lf_file size
    local blob_crlf blob_lf tree_crlf tree_lf
    local commit_base commit_head_unchanged commit_head_converted

    crlf_file="$(mktemp)"
    lf_file="$(mktemp)"
    self_test_fixture_content > "$crlf_file"
    tr -d '\r' < "$crlf_file" > "$lf_file"

    size=$(wc -c < "$crlf_file")
    if [ "$size" -le 65536 ]; then
        echo "FAIL: self-test setup — fixture is only ${size} bytes, must exceed the 64 KiB pipe buffer."
        rm -f "$crlf_file" "$lf_file"
        return 1
    fi

    blob_crlf="$(git hash-object -w "$crlf_file")"
    blob_lf="$(git hash-object -w "$lf_file")"
    rm -f "$crlf_file" "$lf_file"

    tree_crlf="$(printf '100644 blob %s\t%s\n' "$blob_crlf" "$fixture_path" | git mktree)"
    tree_lf="$(printf '100644 blob %s\t%s\n' "$blob_lf" "$fixture_path" | git mktree)"

    commit_base="$(git commit-tree "$tree_crlf" -m 'check-comment-only-diff.sh self-test: base (CRLF)')"
    commit_head_unchanged="$(git commit-tree "$tree_crlf" -m 'check-comment-only-diff.sh self-test: head, unchanged (CRLF)')"
    commit_head_converted="$(git commit-tree "$tree_lf" -m 'check-comment-only-diff.sh self-test: head, converted (LF)')"

    # Assertion 1: a large genuinely-CRLF blob is reported as CRLF, not LF — the direction the
    # SIGPIPE race used to get wrong.
    if is_crlf "$commit_base" "$fixture_path"; then
        echo "PASS: assertion 1 — large genuinely-CRLF blob (${size} bytes) reported as CRLF."
    else
        echo "FAIL: assertion 1 — large genuinely-CRLF blob (${size} bytes) reported as CRLF (is_crlf returned false)."
        failures=1
    fi

    # Assertion 2: a genuine CRLF -> LF change on a large file is still caught as a violation.
    # This is the direction that matters — a fix that made assertion C always pass would be worse
    # than the bug it replaces.
    ASSERTION_C_VIOLATIONS=()
    ASSERTION_C_NOTES=()
    BASE_REF="$commit_base"
    HEAD_REF="$commit_head_converted"
    SCOPES=("$fixture_path")
    run_assertion_c
    if [ ${#ASSERTION_C_VIOLATIONS[@]} -eq 1 ]; then
        echo "PASS: assertion 2 — genuine CRLF -> LF change on a large file is still caught."
    else
        echo "FAIL: assertion 2 — genuine CRLF -> LF change on a large file is still caught (got ${#ASSERTION_C_VIOLATIONS[@]} violation(s))."
        failures=1
    fi

    # Assertion 3 (control): an unchanged large CRLF file across base/head reports no violation —
    # rules out a fix that over-corrects into always flagging large files.
    ASSERTION_C_VIOLATIONS=()
    ASSERTION_C_NOTES=()
    BASE_REF="$commit_base"
    HEAD_REF="$commit_head_unchanged"
    SCOPES=("$fixture_path")
    run_assertion_c
    if [ ${#ASSERTION_C_VIOLATIONS[@]} -eq 0 ]; then
        echo "PASS: assertion 3 — unchanged large CRLF file reports no violation (control)."
    else
        echo "FAIL: assertion 3 — unchanged large CRLF file reports no violation (control, got ${#ASSERTION_C_VIOLATIONS[@]} violation(s))."
        failures=1
    fi

    return "$failures"
}

if [ "$SELF_TEST" -eq 1 ]; then
    run_self_test
    exit $?
fi

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

CHANGED_FILES="$(git diff --name-only "${BASE_REF}..${HEAD_REF}" -- "${SCOPES[@]}" 2>/dev/null || true)"

if [ -z "$CHANGED_FILES" ]; then
    echo "Empty range: no files changed between '${BASE_REF}' and '${HEAD_REF}' within scope(s): ${SCOPES[*]}."
    exit 0
fi

run_assertion_a
run_assertion_b
run_assertion_c

FAILED=0

if [ ${#ASSERTION_A_VIOLATIONS[@]} -gt 0 ] || [ ${#ASSERTION_A_REFUSALS[@]} -gt 0 ]; then
    FAILED=1
    for v in "${ASSERTION_A_VIOLATIONS[@]}"; do
        echo "::error::assertion A (comments-only): ${v}"
    done
    for r in "${ASSERTION_A_REFUSALS[@]}"; do
        echo "::error::assertion A (comments-only) REFUSED: ${r}"
    done
else
    echo "assertion A (comments-only): PASS"
fi

if [ ${#ASSERTION_B_VIOLATIONS[@]} -gt 0 ]; then
    FAILED=1
    echo "::error::assertion B (i18n literal set unchanged): literal set changed"
    for v in "${ASSERTION_B_VIOLATIONS[@]}"; do
        echo "  ${v}"
    done
else
    echo "assertion B (i18n literal set unchanged): PASS"
fi

if [ ${#ASSERTION_C_VIOLATIONS[@]} -gt 0 ]; then
    FAILED=1
    for v in "${ASSERTION_C_VIOLATIONS[@]}"; do
        echo "::error::assertion C (line-ending type unchanged): ${v}"
    done
else
    echo "assertion C (line-ending type unchanged): PASS"
fi

for n in "${ASSERTION_C_NOTES[@]:-}"; do
    [ -n "$n" ] && echo "NOTE: ${n}"
done

echo "SUMMARY: assertion A $([ ${#ASSERTION_A_VIOLATIONS[@]} -eq 0 ] && [ ${#ASSERTION_A_REFUSALS[@]} -eq 0 ] && echo PASS || echo FAIL), assertion B $([ ${#ASSERTION_B_VIOLATIONS[@]} -eq 0 ] && echo PASS || echo FAIL), assertion C $([ ${#ASSERTION_C_VIOLATIONS[@]} -eq 0 ] && echo PASS || echo FAIL)."

exit $FAILED
