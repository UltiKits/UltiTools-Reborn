#!/usr/bin/env bash
#
# Rehearses ROADMAP criterion 1 (CONTEXT.md D-04) end to end, on the thinnest possible path:
# builds a candidate jar and installs it to the local repository, then on a scratch pom.xml
# empties the japicmp <excludes> list with the baseline advanced to that candidate version and
# shows the build goes green; then injects one deliberate binary-incompatible change and shows
# the same build turns red, naming the break.
#
# Why this exists: "zero tolerance" (D-05) is a build-invariant claim about what happens once the
# exclusion list is empty and the baseline has advanced at a release boundary -- not a narrative
# that it was checked once by hand. Every later gate-lane plan in this phase is built on the
# assumption that this actually works. A script rather than a hand-run transcript, because the
# next MINOR release that does removals inherits the tool (D-04), not a one-off record.
#
# What it does NOT prove: branch-protection configuration (that ROADMAP criterion 1 needs a
# required status check on both alpha and main) is confirmed separately, by hand, in
# 08-VALIDATION.md -- this script only exercises the build side.
#
# Usage:
#   .github/scripts/japicmp-rehearsal.sh [options]
#
#   --candidate-version <ver>  Version installed locally and diffed against (default: 6.3.0)
#   --break-target <FQN>       Public, non-final, subclass-free class made `final` for the RED
#                               leg (default: com.ultikits.ultitools.manager.UltiPanelLogTransmitter)
#   --keep-artifacts           Do not delete the locally-installed candidate jar afterwards
#   --dry-run                  Validate flags and preconditions, print the plan, mutate nothing
#   -h, --help                 Show this help
#
# Exit codes:
#   0  = the rehearsal proved the pair: emptied-exclusion build is green, injected break is red
#   1  = the rehearsal ran but did NOT prove the pair (see the ::error:: line for which leg)
#   64 = usage error -- unknown flag, missing tool, or a precondition failed before any mutation
#
set -euo pipefail

CANDIDATE_VERSION="6.3.0"
BREAK_TARGET="com.ultikits.ultitools.manager.UltiPanelLogTransmitter"
KEEP_ARTIFACTS=0
DRY_RUN=0

usage() {
    sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --candidate-version) CANDIDATE_VERSION="${2:-}"; shift 2 ;;
        --break-target)      BREAK_TARGET="${2:-}"; shift 2 ;;
        --keep-artifacts)    KEEP_ARTIFACTS=1; shift ;;
        --dry-run)            DRY_RUN=1; shift ;;
        -h|--help)            usage; exit 0 ;;
        *)
            echo "::error::Unknown flag: $1" >&2
            usage >&2
            exit 64
            ;;
    esac
done

die_usage() { echo "::error::$*" >&2; exit 64; }
die_fail()  { echo "::error::$*" >&2; exit 1; }
note()      { echo "-- $*"; }
notice()    { echo "::notice::$*"; }

[[ "$CANDIDATE_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die_usage "--candidate-version needs a Maven version, not a path."
[[ "$BREAK_TARGET" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]] || die_usage "--break-target needs a top-level class FQN."
# Read-only Git probes must not refresh/rewrite the maintainer's index.
export GIT_OPTIONAL_LOCKS=0

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die_usage "Not inside a git repository."
cd "$REPO_ROOT"

command -v mvn  >/dev/null 2>&1 || die_usage "mvn not found on PATH."
command -v perl >/dev/null 2>&1 || die_usage "perl not found on PATH."
[ -f pom.xml ] || die_usage "pom.xml not found at the repository root (${REPO_ROOT})."

BREAK_TARGET_FILE="src/main/java/$(echo "$BREAK_TARGET" | tr '.' '/').java"
[ -f "$BREAK_TARGET_FILE" ] || die_usage "--break-target ${BREAK_TARGET} has no source file at ${BREAK_TARGET_FILE}."

BREAK_TARGET_SIMPLE_NAME="${BREAK_TARGET##*.}"

# A subclass in-tree would stop `final` from compiling, failing the RED leg for the wrong
# reason (a compile error, not a japicmp finding) -- verified for the default target at
# authoring time (08-01-PLAN.md flagged assumption A4); re-checked here for any override too.
if grep -rlE "extends[[:space:]]+${BREAK_TARGET_SIMPLE_NAME}\\b" src/main src/test >/dev/null 2>&1; then
    die_usage "--break-target ${BREAK_TARGET} has an in-tree subclass; making it final would not compile. Pick a different class."
fi

# ---------------------------------------------------------------- preconditions

POM_STATUS=$(git status --porcelain -- pom.xml) || die_usage "Cannot check pom.xml precondition."
[ -z "$POM_STATUS" ] || die_usage "pom.xml already carries uncommitted changes; run the rehearsal only with a clean POM."
SOURCE_STATUS=$(git status --porcelain -- "$BREAK_TARGET_FILE") || die_usage "Cannot check break-target precondition."
[ -z "$SOURCE_STATUS" ] || die_usage "${BREAK_TARGET_FILE} already carries uncommitted changes; run with a clean break target."
# Compatibility edits (including partial staging) are allowed: their actual bytes are saved.
for path in pom.xml "$BREAK_TARGET_FILE" compatibility/deprecations.json compatibility/DEPRECATIONS.md .flattened-pom.xml; do
    [ ! -L "$path" ] && { [ ! -e "$path" ] || [ -f "$path" ]; } \
        || die_usage "Protected path must be an ordinary file or absent: ${path}"
done

if [ "$DRY_RUN" = 1 ]; then
    note "dry-run: candidate-version=${CANDIDATE_VERSION} break-target=${BREAK_TARGET}"
    note "dry-run: would install a ${CANDIDATE_VERSION} candidate, empty <excludes> with japicmp.baseline.version advanced to ${CANDIDATE_VERSION} and ignoreMissingOldVersion flipped to false, run 'mvn verify' (expect green), add 'final' to ${BREAK_TARGET_SIMPLE_NAME}, re-run 'mvn verify' (expect red naming ${BREAK_TARGET}), then restore the saved original bytes/existence/modes of the POM, break source, two compatibility outputs, .flattened-pom.xml and local coordinate family; the index stays unchanged (target is disposable)."
    note "dry-run: nothing was mutated. Exiting 0."
    exit 0
fi

# Resolve settings/command-line local-repository selection without dumping settings or secrets.
# The help goal is read-only with respect to product outputs; missing offline cache is a failure.
LOCAL_REPOSITORY=$(mvn -B -o help:evaluate -Dexpression=settings.localRepository -q -DforceStdout) \
    || die_usage "Cannot resolve the effective offline Maven local repository."
LOCAL_REPOSITORY=$(printf '%s' "$LOCAL_REPOSITORY" | perl -pe 's/\e\[[0-9;]*m//g')
[[ "$LOCAL_REPOSITORY" = /* && "$LOCAL_REPOSITORY" != *$'\n'* ]] \
    || die_usage "Effective local repository is not one absolute path."
[ -d "$LOCAL_REPOSITORY" ] || die_usage "Effective local repository does not exist."
LOCAL_REPOSITORY=$(cd -- "$LOCAL_REPOSITORY" && pwd -P)
LOCAL_FAMILY="${LOCAL_REPOSITORY}/com/ultikits/UltiTools-API"
[ ! -L "$LOCAL_FAMILY" ] && { [ ! -e "$LOCAL_FAMILY" ] || [ -d "$LOCAL_FAMILY" ]; } \
    || die_usage "Local coordinate family must be a directory or absent."

# Finite write set, established before the first mutation:
# - protected: POM, chosen source, both registry outputs, root flatten output, coordinate family;
# - read-only: index (snapshot + equality check, never Git checkout/restore);
# - run-owned: this unique snapshot/log directory;
# - disposable: target (Maven clean); no other maintained paths are owned by the rehearsal.
# Serialize Maven users of this coordinate family for the duration of the rehearsal.
SNAPSHOT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/japicmp-rehearsal.XXXXXX") \
    || die_fail "Cannot allocate original snapshots; no mutation performed."
CLEANUP_DONE=0
SNAPSHOT_READY=0
MUTATION_STARTED=0
PROTECTED=(pom.xml "$BREAK_TARGET_FILE" compatibility/deprecations.json compatibility/DEPRECATIONS.md .flattened-pom.xml "$LOCAL_FAMILY")
EXISTED=()
MISSING_PARENTS=()
for path in "$LOCAL_REPOSITORY/com" "$LOCAL_REPOSITORY/com/ultikits" compatibility; do
    [ -e "$path" ] || MISSING_PARENTS+=("$path")
done
INDEX_PATH=$(git rev-parse --git-path index) || die_fail "Cannot locate index."
GREEN_LOG="$SNAPSHOT_DIR/green.log"
RED_LOG="$SNAPSHOT_DIR/red.log"

restore_original() {
    local n=$1 path=${PROTECTED[$1]}
    if [ "${EXISTED[$n]}" = 1 ]; then
        # Only this enumerated family is recursive; ordinary files are never broad Git resets.
        if [ "$n" = 5 ]; then
            rm -rf -- "$path" || return 1
        else
            rm -f -- "$path" || return 1
        fi
        cp -a -- "$SNAPSHOT_DIR/original-$n" "$path" || return 1
    else
        if [ "$n" = 5 ]; then rm -rf -- "$path"; else rm -f -- "$path"; fi
    fi
}

cleanup() {
    local status=$? failed=0 n
    [ "$CLEANUP_DONE" = 0 ] || return
    CLEANUP_DONE=1
    trap '' INT TERM
    if [ "$MUTATION_STARTED" = 1 ]; then
        note "restoring saved pre-run bytes, existence and modes (index is read-only)"
        for n in "${!PROTECTED[@]}"; do
            if [ "$n" = 5 ] && [ "$KEEP_ARTIFACTS" = 1 ]; then continue; fi
            restore_original "$n" || failed=1
        done
        # Remove only empty ancestors that did not exist before this run, innermost first.
        if [ "$KEEP_ARTIFACTS" != 1 ]; then
            for ((n=${#MISSING_PARENTS[@]}-1; n>=0; n--)); do
                path=${MISSING_PARENTS[$n]}
                if [ -d "$path" ]; then rmdir -- "$path" || failed=1; fi
            done
        fi
    fi
    if [ "$SNAPSHOT_READY" = 1 ]; then
        cmp -s -- "$SNAPSHOT_DIR/index" "$INDEX_PATH" || failed=1
    fi
    if [ "$failed" != 0 ]; then
        echo "::error::Restoration failed; recoverable originals and manifest retained at ${SNAPSHOT_DIR}" >&2
        status=1
    else
        rm -rf -- "$SNAPSHOT_DIR" || { echo "::error::Cannot remove owned snapshots: ${SNAPSHOT_DIR}" >&2; status=1; }
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for n in "${!PROTECTED[@]}"; do
    path=${PROTECTED[$n]}
    if [ -e "$path" ]; then
        EXISTED+=(1)
        cp -a -- "$path" "$SNAPSHOT_DIR/original-$n" \
            || die_fail "Snapshot failed for ${path}; no mutation performed."
    else
        EXISTED+=(0)
    fi
    printf '%s\t%s\t%s\n' "$n" "${EXISTED[$n]}" "$path" >> "$SNAPSHOT_DIR/manifest.tsv" \
        || die_fail "Cannot record finite snapshot manifest; no mutation performed."
done
cp -a -- "$INDEX_PATH" "$SNAPSHOT_DIR/index" || die_fail "Cannot snapshot index; no mutation performed."
SNAPSHOT_READY=1
MUTATION_STARTED=1

# ---------------------------------------------------------------- install the candidate

CURRENT_VERSION="$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' pom.xml || true)"
[ -n "$CURRENT_VERSION" ] || die_fail "Could not read the project's current <version> from pom.xml."

note "installing candidate ${CANDIDATE_VERSION} (currently ${CURRENT_VERSION}) to the local repository"
perl -i -pe "s/<version>\\Q${CURRENT_VERSION}\\E<\\/version>/<version>${CANDIDATE_VERSION}<\\/version>/" pom.xml

# Skip japicmp and the registry generator here (flagged assumption A1, 08-01-PLAN.md) -- this
# install exists only to place the candidate coordinate in the local repository; japicmp's real
# comparison happens below, once the scratch pom points ignoreMissingOldVersion/excludes/baseline
# at it. If either property name below is wrong this install fails loudly and immediately.
if ! mvn -B -o "-Dmaven.repo.local=$LOCAL_REPOSITORY" clean install -DskipTests -Djapicmp.skip=true -Dexec.skip=true; then
    die_fail "Installing the ${CANDIDATE_VERSION} candidate failed -- see the mvn output above. If the failure names 'japicmp.skip' or 'exec.skip' as unknown, the property name is wrong (flagged assumption A1); fix the property, do not remove the skip."
fi

restore_original 0 || die_fail "Mid-script POM restoration failed; final recovery will run from saved originals."
note "candidate installed; original pom.xml restored to ${CURRENT_VERSION}"

# ---------------------------------------------------------------- GREEN leg

CURRENT_BASELINE="$(grep -m1 -oP '(?<=<japicmp\.baseline\.version>)[^<]+(?=</japicmp\.baseline\.version>)' pom.xml || true)"
[ -n "$CURRENT_BASELINE" ] || die_fail "Could not read japicmp.baseline.version from pom.xml."

note "advancing japicmp.baseline.version ${CURRENT_BASELINE} -> ${CANDIDATE_VERSION}, flipping ignoreMissingOldVersion to false, emptying <excludes>"
perl -i -pe "s/<japicmp\\.baseline\\.version>\\Q${CURRENT_BASELINE}\\E<\\/japicmp\\.baseline\\.version>/<japicmp.baseline.version>${CANDIDATE_VERSION}<\\/japicmp.baseline.version>/" pom.xml
perl -i -pe "s/<ignoreMissingOldVersion>true<\\/ignoreMissingOldVersion>/<ignoreMissingOldVersion>false<\\/ignoreMissingOldVersion>/" pom.xml
# #477: the earlier one-line plain-text regex above (s/(<artifactId>...).*?<excludes>.*?<\/excludes>/.../)
# matched a LITERAL "<excludes>" mentioned in prose inside the japicmp block's own explanatory
# XML comment ("the <excludes> block below is the source of truth for the live count"), not the
# real opening tag - its non-greedy search stops at the first textual occurrence, comment or not.
# That swallowed everything from the comment sentence through the real closing </excludes> (285+
# lines), leaving an unterminated XML comment and a pom.xml Maven's parser rejected outright. Fix:
# mask every XML comment's content to same-length whitespace (preserving line/byte positions, the
# same masking technique this repository's own build-tooling test helpers already use) before
# searching, then splice the real, now-unambiguous <excludes>...</excludes> span out of the
# ORIGINAL (unmasked) text at the position the masked search found. Captures $1/$2/$3 into plain
# lexical copies before the nested substitution below runs - a nested `=~ s///` on $2 resets
# Perl's numbered match variables ($1/$2/$3) to reflect ITS OWN (capture-group-less) match, so
# reading $1/$3 from the outer match AFTER that nested op silently returns empty strings instead
# of "<!--"/"-->".
perl -0777 -i -e '
my $content = do { local $/; <> };
(my $masked = $content) =~ s{(<!--)(.*?)(-->)}{
    my ($open, $body, $close) = ($1, $2, $3);
    $body =~ s/[^\n]/ /g;
    $open . $body . $close;
}gse;
if ($masked =~ /<artifactId>japicmp-maven-plugin<\/artifactId>.*?(<excludes>.*?<\/excludes>)/s) {
    my $start = $-[1];
    my $end = $+[1];
    substr($content, $start, $end - $start) = "<excludes></excludes>";
} else {
    die "excludes block for japicmp-maven-plugin not found (comment-aware match failed)\n";
}
print $content;
' pom.xml

grep -q "<japicmp.baseline.version>${CANDIDATE_VERSION}</japicmp.baseline.version>" pom.xml \
    || die_fail "Failed to advance japicmp.baseline.version to ${CANDIDATE_VERSION} -- pom.xml mutation did not match."
grep -q "<ignoreMissingOldVersion>false</ignoreMissingOldVersion>" pom.xml \
    || die_fail "Failed to flip ignoreMissingOldVersion to false -- pom.xml mutation did not match."
grep -q "<excludes></excludes>" pom.xml \
    || die_fail "Failed to empty the japicmp <excludes> block -- pom.xml mutation did not match. Aborting before running mvn."

note "GREEN leg: mvn -B -o clean verify -DskipTests (exclusion list empty, baseline ${CANDIDATE_VERSION})"
GREEN_EXIT=0
mvn -B -o "-Dmaven.repo.local=$LOCAL_REPOSITORY" clean verify -DskipTests >"$GREEN_LOG" 2>&1 || GREEN_EXIT=$?
cat "$GREEN_LOG"
note "GREEN leg exit code: ${GREEN_EXIT}"

if [ "$GREEN_EXIT" -ne 0 ]; then
    die_fail "GREEN leg failed (exit ${GREEN_EXIT}) -- emptying <excludes> with the baseline advanced to ${CANDIDATE_VERSION} did not build green. See the mvn output above."
fi

# ---------------------------------------------------------------- RED leg

note "injecting a deliberate binary break: making ${BREAK_TARGET_SIMPLE_NAME} final"
perl -i -pe "s/^(public )class(\\s+${BREAK_TARGET_SIMPLE_NAME}\\b)/\$1final class\$2/" "$BREAK_TARGET_FILE"

grep -qE "public final class ${BREAK_TARGET_SIMPLE_NAME}\\b" "$BREAK_TARGET_FILE" \
    || die_fail "Failed to inject the 'final' modifier into ${BREAK_TARGET_FILE} -- pattern did not match ${BREAK_TARGET_SIMPLE_NAME}'s declaration."

note "RED leg: mvn -B -o clean verify -DskipTests (same scratch pom, ${BREAK_TARGET_SIMPLE_NAME} now final)"
RED_EXIT=0
mvn -B -o "-Dmaven.repo.local=$LOCAL_REPOSITORY" clean verify -DskipTests >"$RED_LOG" 2>&1 || RED_EXIT=$?
cat "$RED_LOG"
note "RED leg exit code: ${RED_EXIT}"

if [ "$RED_EXIT" -eq 0 ]; then
    die_fail "RED leg unexpectedly succeeded (exit 0) -- the injected break in ${BREAK_TARGET} did not turn the build red. The gate is not proving what ROADMAP criterion 1 claims."
fi

if ! grep -q "$BREAK_TARGET_SIMPLE_NAME" "$RED_LOG"; then
    die_fail "RED leg failed (exit ${RED_EXIT}) but the failure output does not name ${BREAK_TARGET_SIMPLE_NAME} -- cannot confirm the break was correctly attributed."
fi

notice "Rehearsal proved the pair: GREEN leg exit ${GREEN_EXIT}, RED leg exit ${RED_EXIT} naming ${BREAK_TARGET}."
echo
echo "Candidate version:   ${CANDIDATE_VERSION}"
echo "Break target:        ${BREAK_TARGET}"
echo "GREEN leg exit code: ${GREEN_EXIT}"
echo "RED leg exit code:   ${RED_EXIT}"
echo

exit 0
