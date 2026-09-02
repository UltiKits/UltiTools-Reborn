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

[ -n "$CANDIDATE_VERSION" ] || die_usage "--candidate-version needs a value."
[ -n "$BREAK_TARGET" ]      || die_usage "--break-target needs a value."

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

if [ -n "$(git status --porcelain -- pom.xml)" ]; then
    die_usage "pom.xml already carries uncommitted changes. This script mutates and restores pom.xml via 'git checkout', so an already-dirty pom would be destroyed by the restore step. Commit or stash first, then re-run."
fi

if [ -n "$(git status --porcelain -- "$BREAK_TARGET_FILE")" ]; then
    die_usage "${BREAK_TARGET_FILE} already carries uncommitted changes; the same restore-step hazard applies."
fi

if [ "$DRY_RUN" = 1 ]; then
    note "dry-run: candidate-version=${CANDIDATE_VERSION} break-target=${BREAK_TARGET}"
    note "dry-run: would install a ${CANDIDATE_VERSION} candidate, empty <excludes> with japicmp.baseline.version advanced to ${CANDIDATE_VERSION} and ignoreMissingOldVersion flipped to false, run 'mvn verify' (expect green), add 'final' to ${BREAK_TARGET_SIMPLE_NAME}, re-run 'mvn verify' (expect red naming ${BREAK_TARGET}), then restore pom.xml, ${BREAK_TARGET_FILE} and compatibility/ via git checkout."
    note "dry-run: nothing was mutated. Exiting 0."
    exit 0
fi

# ---------------------------------------------------------------- restore trap

CLEANUP_DONE=0
GREEN_LOG=""
RED_LOG=""

cleanup() {
    if [ "$CLEANUP_DONE" = 1 ]; then
        return
    fi
    CLEANUP_DONE=1
    note "restoring pom.xml, ${BREAK_TARGET_FILE} and compatibility/ via git checkout"
    git checkout -- pom.xml "$BREAK_TARGET_FILE" compatibility/ 2>/dev/null || true
    if [ "$KEEP_ARTIFACTS" != 1 ]; then
        LOCAL_ARTIFACT_DIR="${HOME}/.m2/repository/com/ultikits/UltiTools-API/${CANDIDATE_VERSION}"
        if [ -d "$LOCAL_ARTIFACT_DIR" ]; then
            rm -rf "$LOCAL_ARTIFACT_DIR"
            note "removed local candidate artifact ${LOCAL_ARTIFACT_DIR}"
        fi
    fi
    [ -n "$GREEN_LOG" ] && [ -f "$GREEN_LOG" ] && rm -f "$GREEN_LOG"
    [ -n "$RED_LOG" ] && [ -f "$RED_LOG" ] && rm -f "$RED_LOG"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------- install the candidate

CURRENT_VERSION="$(grep -m1 -oP '(?<=<version>)[^<]+(?=</version>)' pom.xml || true)"
[ -n "$CURRENT_VERSION" ] || die_fail "Could not read the project's current <version> from pom.xml."

note "installing candidate ${CANDIDATE_VERSION} (currently ${CURRENT_VERSION}) to the local repository"
perl -i -pe "s/<version>\\Q${CURRENT_VERSION}\\E<\\/version>/<version>${CANDIDATE_VERSION}<\\/version>/" pom.xml

# Skip japicmp and the registry generator here (flagged assumption A1, 08-01-PLAN.md) -- this
# install exists only to place the candidate coordinate in the local repository; japicmp's real
# comparison happens below, once the scratch pom points ignoreMissingOldVersion/excludes/baseline
# at it. If either property name below is wrong this install fails loudly and immediately.
if ! mvn -B -o clean install -DskipTests -Djapicmp.skip=true -Dexec.skip=true; then
    die_fail "Installing the ${CANDIDATE_VERSION} candidate failed -- see the mvn output above. If the failure names 'japicmp.skip' or 'exec.skip' as unknown, the property name is wrong (flagged assumption A1); fix the property, do not remove the skip."
fi

git checkout -- pom.xml
note "candidate installed; pom.xml restored to ${CURRENT_VERSION}"

# ---------------------------------------------------------------- GREEN leg

CURRENT_BASELINE="$(grep -m1 -oP '(?<=<japicmp\.baseline\.version>)[^<]+(?=</japicmp\.baseline\.version>)' pom.xml || true)"
[ -n "$CURRENT_BASELINE" ] || die_fail "Could not read japicmp.baseline.version from pom.xml."

note "advancing japicmp.baseline.version ${CURRENT_BASELINE} -> ${CANDIDATE_VERSION}, flipping ignoreMissingOldVersion to false, emptying <excludes>"
perl -i -pe "s/<japicmp\\.baseline\\.version>\\Q${CURRENT_BASELINE}\\E<\\/japicmp\\.baseline\\.version>/<japicmp.baseline.version>${CANDIDATE_VERSION}<\\/japicmp.baseline.version>/" pom.xml
perl -i -pe "s/<ignoreMissingOldVersion>true<\\/ignoreMissingOldVersion>/<ignoreMissingOldVersion>false<\\/ignoreMissingOldVersion>/" pom.xml
perl -i -0777 -pe "s/(<artifactId>japicmp-maven-plugin<\\/artifactId>.*?)<excludes>.*?<\\/excludes>/\$1<excludes><\\/excludes>/s" pom.xml

grep -q "<japicmp.baseline.version>${CANDIDATE_VERSION}</japicmp.baseline.version>" pom.xml \
    || die_fail "Failed to advance japicmp.baseline.version to ${CANDIDATE_VERSION} -- pom.xml mutation did not match."
grep -q "<ignoreMissingOldVersion>false</ignoreMissingOldVersion>" pom.xml \
    || die_fail "Failed to flip ignoreMissingOldVersion to false -- pom.xml mutation did not match."
grep -q "<excludes></excludes>" pom.xml \
    || die_fail "Failed to empty the japicmp <excludes> block -- pom.xml mutation did not match. Aborting before running mvn."

note "GREEN leg: mvn -B -o clean verify -DskipTests (exclusion list empty, baseline ${CANDIDATE_VERSION})"
GREEN_LOG="$(mktemp)"
GREEN_EXIT=0
mvn -B -o clean verify -DskipTests >"$GREEN_LOG" 2>&1 || GREEN_EXIT=$?
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
RED_LOG="$(mktemp)"
RED_EXIT=0
mvn -B -o clean verify -DskipTests >"$RED_LOG" 2>&1 || RED_EXIT=$?
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
