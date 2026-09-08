#!/usr/bin/env bash
# ci-drift-guard.sh -- regenerate a module's uat/surface.json and prove it is byte-identical
# to the committed one (Phase 10, D-10-07). One script, run identically by all seventeen
# module-side repositories' maven-ci.yml, so their CI steps never drift from each other.
#
# Usage (run from inside a module checkout, after `mvn test-compile` or `mvn verify`):
#   ci-drift-guard.sh [MODULE_NAME] [CLASSES_PATH]
#
#   MODULE_NAME   defaults to the checkout directory's base name (e.g. "UltiChat").
#   CLASSES_PATH  defaults to target/classes; pass an explicit jar path for a module whose
#                 classes live in a shaded dist jar (UltiBot's ultibot-dist).
#
# Exit codes:
#   0  uat/surface.json regenerated cleanly and is byte-identical to what git already has
#      (or this is the very first time it has ever been staged -- see the blob-size check
#      below -- in which case there is nothing yet to have drifted from).
#   1  a named failure: the framework artifact could not be resolved on the classpath,
#      uat/surface.json is not tracked by git (T-10-14 -- a repository cannot pass this
#      guard by regenerating into a path git does not see), or regeneration produced a
#      different file than the one already committed (the diff is printed above the
#      failure message).
set -euo pipefail

MODULE="${1:-$(basename "$PWD")}"
CLASSES="${2:-target/classes}"
CP_FILE="target/uat-cp.txt"
SURFACE_OUTPUT="uat/surface.json"
DEPENDENCY_PLUGIN_VERSION="3.11.0"
TOOL_GROUP_ARTIFACT="com.ultikits:ultitools-uat-tools"
TOOL_JAR_DIR="target/uat-tool"

echo "ci-drift-guard.sh: building the compile classpath for '${MODULE}' (maven-dependency-plugin pinned to ${DEPENDENCY_PLUGIN_VERSION} -- an unpinned default can silently resolve the old 2.8 goal implementation)." >&2
mvn -B "org.apache.maven.plugins:maven-dependency-plugin:${DEPENDENCY_PLUGIN_VERSION}:build-classpath" \
    -Dmdep.outputFile="${CP_FILE}" -Dmdep.includeScope=test

if [ ! -s "${CP_FILE}" ]; then
    echo "ci-drift-guard.sh: FATAL: ${CP_FILE} was not written, or is empty -- the classpath could not be assembled." >&2
    exit 1
fi

if ! grep -q "UltiTools-API" "${CP_FILE}"; then
    echo "ci-drift-guard.sh: FATAL: the resolved classpath does not include the UltiTools-API framework jar (checked ${CP_FILE})." >&2
    echo "ci-drift-guard.sh: the extractor needs the framework's own classes at runtime -- confirm this module depends on the framework and that the framework jar is resolvable (e.g. installed to the local Maven repository)." >&2
    exit 1
fi

# Phase 10, D-10-03 as amended 2026-09-08: the extractor moved out of the plugin jar into its
# own artifact, com.ultikits:ultitools-uat-tools, published in lockstep with the framework by
# the same snapshot/publish workflows. Derive the version to resolve from this module's own
# resolved framework version (the UltiTools-API entry the check above just confirmed is on the
# classpath), never a hardcoded one, so a module pinning a different framework version gets the
# matching tool.
FRAMEWORK_VERSION="$(grep -oE 'UltiTools-API-[^/:]+\.jar' "${CP_FILE}" | head -1 | sed -E 's/^UltiTools-API-(.+)\.jar$/\1/')"
if [ -z "${FRAMEWORK_VERSION}" ]; then
    echo "ci-drift-guard.sh: FATAL: could not derive the framework version from the resolved classpath (checked ${CP_FILE})." >&2
    echo "ci-drift-guard.sh: expected an entry matching UltiTools-API-<version>.jar; without it the matching ${TOOL_GROUP_ARTIFACT} version cannot be determined." >&2
    exit 1
fi

echo "ci-drift-guard.sh: resolving ${TOOL_GROUP_ARTIFACT}:${FRAMEWORK_VERSION} (the module's own resolved framework version)." >&2
mkdir -p "${TOOL_JAR_DIR}"
if ! mvn -B "org.apache.maven.plugins:maven-dependency-plugin:${DEPENDENCY_PLUGIN_VERSION}:copy" \
    -Dartifact="${TOOL_GROUP_ARTIFACT}:${FRAMEWORK_VERSION}:jar" \
    -DoutputDirectory="${TOOL_JAR_DIR}" -Dmdep.stripVersion=false; then
    echo "ci-drift-guard.sh: FATAL: could not resolve ${TOOL_GROUP_ARTIFACT}:${FRAMEWORK_VERSION} from the repositories this build already declares." >&2
    echo "ci-drift-guard.sh: this artifact is published by the same snapshot/publish workflow as the framework -- confirm the framework version above has a matching tool release, and that this build has network access to the same repositories that resolved UltiTools-API." >&2
    exit 1
fi

TOOL_JAR="$(ls "${TOOL_JAR_DIR}"/ultitools-uat-tools-"${FRAMEWORK_VERSION}".jar 2>/dev/null | head -1)"
if [ -z "${TOOL_JAR}" ] || [ ! -f "${TOOL_JAR}" ]; then
    echo "ci-drift-guard.sh: FATAL: the resolved tool artifact was not found at the expected path under ${TOOL_JAR_DIR}." >&2
    exit 1
fi

if ! unzip -l "${TOOL_JAR}" | grep -q 'com/ultikits/ultitools/uat/SurfaceExtractorMain.class'; then
    echo "ci-drift-guard.sh: FATAL: ${TOOL_JAR} does not contain com/ultikits/ultitools/uat/SurfaceExtractorMain.class." >&2
    echo "ci-drift-guard.sh: the downloaded jar is not a usable ${TOOL_GROUP_ARTIFACT} build -- do not fall back to skipping this check silently." >&2
    exit 1
fi

CLASSPATH="${TOOL_JAR}:$(cat "${CP_FILE}")"

echo "ci-drift-guard.sh: regenerating ${SURFACE_OUTPUT} for '${MODULE}' from ${CLASSES}." >&2
java -cp "${CLASSPATH}:${CLASSES}" com.ultikits.ultitools.uat.SurfaceExtractorMain \
    --module "${MODULE}" --classes "${CLASSES}" --output "${SURFACE_OUTPUT}"

# T-10-14: a completely untracked path is invisible to `git diff`, which reports zero
# differences for a file git has never seen -- exactly the false-clean this guard exists
# to prevent. Assert trackedness explicitly, before ever trusting a diff's exit code.
if ! git ls-files --error-unmatch "${SURFACE_OUTPUT}" >/dev/null 2>&1; then
    echo "ci-drift-guard.sh: FATAL: ${SURFACE_OUTPUT} is not tracked by git." >&2
    echo "ci-drift-guard.sh: a drift guard cannot prove byte-identity against a file git does not see -- run 'git add ${SURFACE_OUTPUT}' and commit it before this guard can pass." >&2
    exit 1
fi

# A path staged via `git add -N` (intent-to-add) is tracked, but carries the empty blob as
# its staged content -- `git diff` on such a path always reports the whole file as new,
# regardless of whether the working tree matches the previous run, because there is no
# real baseline to compare against yet. Checked by blob size rather than a hardcoded empty-
# blob hash, so this holds under either the SHA-1 or SHA-256 object format.
STAGED_BLOB="$(git ls-files --stage -- "${SURFACE_OUTPUT}" | awk '{print $2}')"
STAGED_BLOB_SIZE="$(git cat-file -s "${STAGED_BLOB}" 2>/dev/null || echo -1)"

if [ "${STAGED_BLOB_SIZE}" = "0" ]; then
    echo "ci-drift-guard.sh: ${SURFACE_OUTPUT} has no real baseline staged yet (first-time generation) -- nothing to compare against. This is expected on the commit that first adds it." >&2
    exit 0
fi

if ! git diff --exit-code -- "${SURFACE_OUTPUT}"; then
    echo "ci-drift-guard.sh: FATAL: regenerating ${SURFACE_OUTPUT} produced a different file than the one committed (diff printed above). Regenerate locally and commit the result." >&2
    exit 1
fi

echo "ci-drift-guard.sh: ${SURFACE_OUTPUT} is byte-identical after regeneration." >&2
