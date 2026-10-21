#!/usr/bin/env bash
# module-release.sh - shared retained-asset publication adapter for UltiKits module publish workflows.
#
# Each module's existing .github/workflows/publish.yml calls this adapter on an explicit workflow_dispatch.
# It publishes bytes that were built once, accepted independently and staged on a draft GitHub release.
# It never builds and never re-uploads a release asset.
#
# Authority stays with the maintainer. Before the CLI installation and before any remote write, the run
# must be a first-attempt workflow_dispatch on the default branch of the reviewed repository, started by
# the reviewed maintainer login, and the staged draft assets must hash to the SHA-256 values the dispatch
# carries. A mismatch exits non-zero with nothing installed and nothing written. The dispatch inputs are
# read from GITHUB_EVENT_PATH by release_artifacts.py as data; this script never expands raw input text.
#
# Usage (from the module's publish.yml, with this file and release_artifacts.py fetched at a reviewed
# framework commit and their digests checked):
#
#   bash module-release.sh --repo UltiKits/<Module> --identify-string <exact catalogue identity> \
#     --default-branch master --maintainer <login> --jar-prefix <ArtifactId> --helper-sha256 <hex>
#
# GitHub and the UltiCloud catalogue are reconciled independently. An identical draft is published; an
# identical release that is already published (its tag at the accepted commit) is left alone; a missing
# catalogue version is uploaded from the same staged JAR; these exact bytes already served at level 630 are
# left alone. Different bytes on either side, a served version that is not lower than this one, a moved
# default branch, a changed draft or a concurrent run refuse. A transport, authentication or server error is
# an error, never evidence that something is absent. The CLI is installed only when an upload is needed.
#
# The accepted JAR is <jar-prefix>-<version>.jar exactly; its manifest is
# <jar-prefix>-<version>-release-manifest.json. Environment: the GitHub Actions runner variables, GH_TOKEN
# (the job token, contents: write) and ULTIKITS_TOKEN (the existing catalogue secret).
#
# Consumer contract: actions/checkout must use persist-credentials: false and fetch-depth: 0.
# npm and every CLI invocation run without GH_TOKEN/GITHUB_TOKEN; only gh inherits the job token.
# CHANGELOG.md (or --changelog-file) must contain exactly one ## [<version>] section.
#
# Exit status: 0 both sides complete; 1 refused before any install or write; 2 usage;
# 3 a write was attempted but the run did not complete both sides.

set -uo pipefail
umask 077

readonly PACKAGE_SPEC="@ultikits/cli@1.2.0"
readonly PACKAGE_VERSION="1.2.0"

REPO="" IDENTIFY="" DEFAULT_BRANCH="" MAINTAINER="" JAR_PREFIX="" HELPER_SHA256="" CHANGELOG_FILE=""
CHECKOUT="${GITHUB_WORKSPACE:-$PWD}"
RESULT="refused" REFUSAL="" WROTE=0
RELEASE_BEFORE="unknown" RELEASE_OUTCOME="not-attempted" CATALOGUE_BEFORE="unknown" CATALOGUE_OUTCOME="not-attempted"
INSTALLED="none" OBSERVED_METADATA="" NO_LEVEL_BEFORE="" NO_LEVEL_AFTER="" LEVEL630_AFTER=""
IN_INPUTS_SHA256="" IN_SOURCE_HEAD="" IN_VERSION="" IN_DRAFT_RELEASE_ID="" IN_MANIFEST_SHA256=""
IN_ARTIFACT_SHA256="" IN_PACKAGE_METADATA_SHA256="" IN_INSTALLATION_ID="" IN_RUN_ID="" IN_RUN_ATTEMPT=""
IN_EVENT="" IN_TRIGGERING_ACTOR=""
WORK="" LOCK=""

usage() {
    echo "usage: module-release.sh --repo OWNER/NAME --identify-string ID --default-branch BRANCH" \
         "--maintainer LOGIN --jar-prefix PREFIX --helper-sha256 HEX [--changelog-file FILE]" >&2
    RESULT="usage"
    exit 2
}

# Every value written here has passed a format check (hex digests, digits, versions, fixed words,
# validated identities), so the JSON needs no escaping.
write_receipt() {
    local target="${RUNNER_TEMP:-}"
    [ -n "$target" ] || return 0
    {
        printf '{\n'
        printf '  "schema": "module-release-receipt/1",\n'
        printf '  "result": "%s",\n  "refusal": "%s",\n' "$RESULT" "$REFUSAL"
        printf '  "repo": "%s",\n  "identify_string": "%s",\n' "$REPO" "$IDENTIFY"
        printf '  "version": "%s",\n  "source_head": "%s",\n' "$IN_VERSION" "$IN_SOURCE_HEAD"
        printf '  "inputs_sha256": "%s",\n  "installation_id": "%s",\n' "$IN_INPUTS_SHA256" "$IN_INSTALLATION_ID"
        printf '  "run_id": "%s",\n  "run_attempt": "%s",\n' "$IN_RUN_ID" "$IN_RUN_ATTEMPT"
        printf '  "event": "%s",\n  "triggering_actor": "%s",\n' "$IN_EVENT" "$IN_TRIGGERING_ACTOR"
        printf '  "release_before": "%s",\n  "release_outcome": "%s",\n' "$RELEASE_BEFORE" "$RELEASE_OUTCOME"
        printf '  "catalogue_before": "%s",\n  "catalogue_outcome": "%s",\n' "$CATALOGUE_BEFORE" "$CATALOGUE_OUTCOME"
        printf '  "installed_package": "%s",\n  "package_metadata_sha256": "%s",\n' "$INSTALLED" "$OBSERVED_METADATA"
        printf '  "no_level_before": "%s",\n  "no_level_after": "%s",\n' "$NO_LEVEL_BEFORE" "$NO_LEVEL_AFTER"
        printf '  "level630_after": "%s"\n' "$LEVEL630_AFTER"
        printf '}\n'
    } > "$target/module-release-receipt.json"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            printf '### Module publication: %s %s\n\n' "$REPO" "$IN_VERSION"
            printf '| Field | Value |\n|---|---|\n'
            printf '| result | %s %s |\n| inputs SHA-256 | %s |\n' "$RESULT" "$REFUSAL" "$IN_INPUTS_SHA256"
            printf '| run | %s attempt %s (%s by %s) |\n' "$IN_RUN_ID" "$IN_RUN_ATTEMPT" "$IN_EVENT" "$IN_TRIGGERING_ACTOR"
            printf '| installation | %s |\n| installed | %s |\n' "$IN_INSTALLATION_ID" "$INSTALLED"
            printf '| package metadata SHA-256 | %s |\n' "$OBSERVED_METADATA"
            printf '| GitHub release | %s -> %s |\n' "$RELEASE_BEFORE" "$RELEASE_OUTCOME"
            printf '| catalogue | %s -> %s |\n' "$CATALOGUE_BEFORE" "$CATALOGUE_OUTCOME"
            printf '| no-level latest | %s -> %s |\n| level 630 latest | %s |\n' \
                "$NO_LEVEL_BEFORE" "$NO_LEVEL_AFTER" "$LEVEL630_AFTER"
        } >> "$GITHUB_STEP_SUMMARY"
    fi
}

finish() {
    local status=$?
    write_receipt
    echo "RESULT=$RESULT RELEASE_OUTCOME=$RELEASE_OUTCOME CATALOGUE_OUTCOME=$CATALOGUE_OUTCOME"
    [ -z "$LOCK" ] || rmdir "$LOCK" 2>/dev/null
    [ -z "$WORK" ] || rm -rf "$WORK"
    exit "$status"
}
trap finish EXIT

# A refusal before any write exits 1; once a write was attempted the run is partial and exits 3.
refuse() {
    REFUSAL="$1"
    echo "refused: $1: $2" >&2
    if [ "$WROTE" -eq 1 ]; then
        RESULT="partial"
        exit 3
    fi
    RESULT="refused"
    exit 1
}

need_value() { [ "$1" -ge 2 ] || usage; }

while [ $# -gt 0 ]; do
    case "$1" in
        --repo) need_value $#; REPO="$2"; shift 2 ;;
        --identify-string) need_value $#; IDENTIFY="$2"; shift 2 ;;
        --default-branch) need_value $#; DEFAULT_BRANCH="$2"; shift 2 ;;
        --maintainer) need_value $#; MAINTAINER="$2"; shift 2 ;;
        --jar-prefix) need_value $#; JAR_PREFIX="$2"; shift 2 ;;
        --helper-sha256) need_value $#; HELPER_SHA256="$2"; shift 2 ;;
        --changelog-file) need_value $#; CHANGELOG_FILE="$2"; shift 2 ;;
        *) usage ;;
    esac
done

[[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || usage
[[ "$IDENTIFY" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || usage
[[ "$DEFAULT_BRANCH" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$ && "$DEFAULT_BRANCH" != *..* ]] || usage
[[ "$MAINTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,38}$ ]] || usage
[[ "$JAR_PREFIX" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$ ]] || usage
[[ "$HELPER_SHA256" =~ ^[0-9a-f]{64}$ ]] || usage

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$TOOLS_DIR/release_artifacts.py"

# --------------------------------------------------------------- checks before any install or write

actual_helper="$(sha256sum "$HELPER" 2>/dev/null | cut -d' ' -f1)"
[ "$actual_helper" = "$HELPER_SHA256" ] || refuse helper_digest_mismatch "release_artifacts.py is not the reviewed pinned helper"

WORK="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/module-release.XXXXXX")" || refuse workspace "no scratch directory"
mkdir "$WORK/assets"

helper() { python3 -I "$HELPER" "$@"; }
# Apply to installation too: disabling lifecycle scripts alone is not a credential boundary.
without_github_token() { env -u GH_TOKEN -u GITHUB_TOKEN "$@"; }

# Read KEY=VALUE lines printed by the helper into IN_* variables; only allowlisted keys are taken.
read_kv() {
    local key value
    while IFS='=' read -r key value; do
        case "$key" in
            INPUTS_SHA256|SOURCE_HEAD|VERSION|DRAFT_RELEASE_ID|MANIFEST_SHA256|ARTIFACT_SHA256|\
PACKAGE_METADATA_SHA256|INSTALLATION_ID|RUN_ID|RUN_ATTEMPT|EVENT|TRIGGERING_ACTOR|RELEASE_STATE|\
JAR_ASSET_ID|JAR_ASSET_SIZE|MANIFEST_ASSET_ID|MANIFEST_ASSET_SIZE|PRODUCT_TREE|JAR_BYTES|SHA|\
CATALOGUE_STATE|PLUGIN_ID|NO_LEVEL_LATEST|LEVEL630_LATEST)
                printf -v "IN_$key" '%s' "$value" ;;
        esac
    done < "$1"
}

helper dispatch-inputs verify --repository "$REPO" --default-branch "$DEFAULT_BRANCH" \
    --maintainer "$MAINTAINER" > "$WORK/inputs.env" || refuse dispatch_inputs "the dispatch does not verify"
read_kv "$WORK/inputs.env"
[[ "$IN_SOURCE_HEAD" =~ ^[0-9a-f]{40}$ && "$IN_ARTIFACT_SHA256" =~ ^[0-9a-f]{64}$ \
   && "$IN_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ && "$IN_PACKAGE_METADATA_SHA256" =~ ^[0-9a-f]{64}$ \
   && "$IN_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$IN_DRAFT_RELEASE_ID" =~ ^[1-9][0-9]*$ ]] \
    || refuse dispatch_inputs "verified inputs are malformed"
echo "INPUTS_SHA256=$IN_INPUTS_SHA256"
echo "installation $IN_INSTALLATION_ID, run $IN_RUN_ID attempt $IN_RUN_ATTEMPT"

JAR_NAME="$JAR_PREFIX-$IN_VERSION.jar"
MANIFEST_NAME="$JAR_PREFIX-$IN_VERSION-release-manifest.json"
# The optional path selects a changelog document, never an arbitrary shell fragment. Extract before
# installation or any external write, even when the two publication sides are already complete.
[ -n "$CHANGELOG_FILE" ] || CHANGELOG_FILE="$CHECKOUT/CHANGELOG.md"
CHANGELOG_TEXT="$(helper module-changelog --changelog-file "$CHANGELOG_FILE" --version "$IN_VERSION")" \
    || refuse changelog_invalid "the released-version changelog section could not be extracted"

# One publication per repository and version on this runner; the workflow's concurrency group serializes
# runs across runners, and every write is preceded by a fresh remote comparison.
lock_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ultitools-module-release-locks"
mkdir -p "$lock_root" || refuse workspace "no lock directory"
if mkdir "$lock_root/${REPO//\//_}-$IN_VERSION.lock" 2>/dev/null; then
    LOCK="$lock_root/${REPO//\//_}-$IN_VERSION.lock"
else
    refuse concurrent_run "another publication of $REPO $IN_VERSION is in progress"
fi

# The checked-out default branch must carry exactly the accepted product tree.
head_sha="$(git -C "$CHECKOUT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" || refuse checkout_unreadable "no checkout"
[ -z "${GITHUB_SHA:-}" ] || [ "$GITHUB_SHA" = "$head_sha" ] || refuse checkout_not_run_head "the checkout is not the run commit"
[ "$(git -C "$CHECKOUT" cat-file -t "$IN_SOURCE_HEAD" 2>/dev/null)" = "commit" ] \
    || refuse source_head_unknown "the accepted source commit is not in this repository"
git -C "$CHECKOUT" merge-base --is-ancestor "$IN_SOURCE_HEAD" "$head_sha" 2>/dev/null \
    || refuse source_not_on_default_branch "the accepted source is not on the default branch"
source_tree="$(git -C "$CHECKOUT" rev-parse --verify "$IN_SOURCE_HEAD^{tree}" 2>/dev/null)"
[ -n "$source_tree" ] || refuse source_head_unknown "the accepted source tree could not be read"
# Preserve the full accepted tree identity below, but do not invalidate retained bytes for docs or tooling.
git -C "$CHECKOUT" diff --quiet "$IN_SOURCE_HEAD" "$head_sha" -- pom.xml src/main/ \
    || refuse product_tree_differs "the default branch product paths differ from the accepted source"

# Observe the remote state; the same reads run again immediately before each write.
observe_github() {  # $1: suffix for the scratch files
    gh api "repos/$REPO/releases/$IN_DRAFT_RELEASE_ID" > "$WORK/release$1.json" 2>/dev/null \
        || refuse github_unavailable "the dispatched release could not be read"
    helper module-release-state --release-json "$WORK/release$1.json" --draft-release-id "$IN_DRAFT_RELEASE_ID" \
        --source-head "$IN_SOURCE_HEAD" --version "$IN_VERSION" --jar-name "$JAR_NAME" > "$WORK/release$1.env" \
        || refuse release_state "the dispatched release is not the accepted draft"
    gh api "repos/$REPO/git/matching-refs/tags/v$IN_VERSION" > "$WORK/tags$1.json" 2>/dev/null \
        || refuse github_unavailable "tags could not be read"
    helper module-ref-sha --refs-json "$WORK/tags$1.json" --ref "refs/tags/v$IN_VERSION" --allow-absent \
        > "$WORK/tag$1.env" || refuse tag_unreadable "the version tag does not resolve to one commit"
    gh api "repos/$REPO/git/ref/heads/$DEFAULT_BRANCH" > "$WORK/head$1.json" 2>/dev/null \
        || refuse github_unavailable "the default branch could not be read"
    helper module-ref-sha --refs-json "$WORK/head$1.json" --ref "refs/heads/$DEFAULT_BRANCH" > "$WORK/head$1.env" \
        || refuse github_unavailable "the default branch does not resolve"
    read_kv "$WORK/head$1.env"
    [ "$IN_SHA" = "$head_sha" ] || refuse stale_default_head "the default branch moved after this run started"
    read_kv "$WORK/tag$1.env"
    TAG_SHA="$IN_SHA"
    read_kv "$WORK/release$1.env"
}

# The dispatched draft release, its tag and the default branch; then its two staged assets.
observe_github ""
RELEASE_BEFORE="$IN_RELEASE_STATE"
if [ "$TAG_SHA" != "absent" ] && [ "$TAG_SHA" != "$IN_SOURCE_HEAD" ]; then
    refuse tag_elsewhere "tag v$IN_VERSION already names another commit"
fi
if [ "$RELEASE_BEFORE" = "published" ] && [ "$TAG_SHA" != "$IN_SOURCE_HEAD" ]; then
    refuse tag_elsewhere "the published release's tag does not resolve to the accepted commit"
fi
first_assets_view="$(grep -v '^RELEASE_STATE=' "$WORK/release.env")"
for pair in "$IN_JAR_ASSET_ID:$JAR_NAME" "$IN_MANIFEST_ASSET_ID:$MANIFEST_NAME"; do
    gh api -H "Accept: application/octet-stream" "repos/$REPO/releases/assets/${pair%%:*}" \
        > "$WORK/assets/${pair#*:}" 2>/dev/null || refuse github_unavailable "a staged asset could not be downloaded"
done
helper module-verify-assets --assets-dir "$WORK/assets" --jar-name "$JAR_NAME" --jar-size "$IN_JAR_ASSET_SIZE" \
    --manifest-size "$IN_MANIFEST_ASSET_SIZE" --repo "$REPO" --identify-string "$IDENTIFY" \
    --source-head "$IN_SOURCE_HEAD" --version "$IN_VERSION" --manifest-sha256 "$IN_MANIFEST_SHA256" \
    --artifact-sha256 "$IN_ARTIFACT_SHA256" > "$WORK/assets.env" || refuse staged_assets "the staged assets are not the accepted bytes"
read_kv "$WORK/assets.env"
[ "$IN_PRODUCT_TREE" = "$source_tree" ] || refuse product_tree_mismatch "the manifest names another product tree"


observe_catalogue() {  # $1: suffix
    helper catalogue-state --identify-string "$IDENTIFY" --version "$IN_VERSION" \
        --artifact-sha256 "$IN_ARTIFACT_SHA256" > "$WORK/catalogue$1.env" \
        || refuse catalogue_state "the catalogue refuses this version"
    read_kv "$WORK/catalogue$1.env"
}
observe_catalogue ""
CATALOGUE_BEFORE="$IN_CATALOGUE_STATE"
NO_LEVEL_BEFORE="$IN_NO_LEVEL_LATEST"
NO_LEVEL_AFTER="$IN_NO_LEVEL_LATEST"
LEVEL630_AFTER="$IN_LEVEL630_LATEST"

need_release=0 need_catalogue=0
[ "$RELEASE_BEFORE" = "draft" ] && need_release=1
[ "$CATALOGUE_BEFORE" = "absent" ] && need_catalogue=1
[ "$need_release" -eq 1 ] || RELEASE_OUTCOME="already-published"
[ "$need_catalogue" -eq 1 ] || CATALOGUE_OUTCOME="already-served"
echo "github: $RELEASE_BEFORE; catalogue: $CATALOGUE_BEFORE"

if [ "$need_catalogue" -eq 1 ]; then
    [ -n "${ULTIKITS_TOKEN:-}" ] || refuse catalogue_token_missing "ULTIKITS_TOKEN is not set"
    # Fresh exact metadata immediately before the single installation.
    OBSERVED_METADATA="$(helper package-metadata digest)" || refuse registry_unavailable "registry metadata could not be read"
    [[ "$OBSERVED_METADATA" =~ ^[0-9a-f]{64}$ ]] || refuse registry_unavailable "registry metadata digest is malformed"
    [ "$OBSERVED_METADATA" = "$IN_PACKAGE_METADATA_SHA256" ] \
        || refuse package_metadata_changed "fresh $PACKAGE_SPEC metadata differs from the verified snapshot"
    without_github_token npm install --global --ignore-scripts --no-audit --no-fund "$PACKAGE_SPEC" > /dev/null 2>&1 \
        || refuse cli_install_failed "the pinned CLI did not install"
    INSTALLED="$PACKAGE_SPEC"
    [ "$(without_github_token ultikits --version 2>/dev/null)" = "$PACKAGE_VERSION" ] || refuse cli_version_mismatch "the installed CLI is not $PACKAGE_VERSION"
    cli_help="$(without_github_token ultikits publish --help 2>/dev/null)" || refuse cli_flags_unknown "the CLI publish help is unavailable"
    [[ "$cli_help" == *"--module-version"* && "$cli_help" == *"--id "* && "$cli_help" == *"--changelog "* ]] \
        || refuse cli_flags_unknown "the installed CLI does not document --id and --module-version"
fi

# Compare the remote again immediately before a write: the same release assets in the expected state,
# the tag absent or at the accepted commit, and the default head unchanged.
recheck_github() {  # $1: label, $2: expected release state
    observe_github "-$1"
    [ "$(grep -v '^RELEASE_STATE=' "$WORK/release-$1.env")" = "$first_assets_view" ] \
        || refuse remote_changed "the release assets changed since they were verified"
    [ "$IN_RELEASE_STATE" = "$2" ] || refuse remote_changed "the release is no longer $2"
    [ "$TAG_SHA" = "absent" ] || [ "$TAG_SHA" = "$IN_SOURCE_HEAD" ] \
        || refuse tag_elsewhere "tag v$IN_VERSION now names another commit"
}

# ------------------------------------------------------------------------------------- writes

if [ "$need_release" -eq 1 ]; then
    recheck_github before-release draft
    WROTE=1
    gh api -X PATCH "repos/$REPO/releases/$IN_DRAFT_RELEASE_ID" -F draft=false > "$WORK/published.json" 2>/dev/null \
        || { RELEASE_OUTCOME="error"; refuse release_publish_failed "the draft could not be published"; }
    observe_github "-after"
    if [ "$IN_RELEASE_STATE" != "published" ] || [ "$TAG_SHA" != "$IN_SOURCE_HEAD" ]; then
        RELEASE_OUTCOME="error"
        refuse release_readback "the release is not published at the accepted commit"
    fi
    RELEASE_OUTCOME="published"
    echo "github: release v$IN_VERSION published from the accepted draft"
fi

if [ "$need_catalogue" -eq 1 ]; then
    recheck_github before-catalogue "$([ "$need_release" -eq 1 ] && echo published || echo "$RELEASE_BEFORE")"
    observe_catalogue "-before-upload"
    if [ "$IN_CATALOGUE_STATE" = "absent" ]; then
        changelog_args=(--changelog "$CHANGELOG_TEXT")
        WROTE=1
        ( cd "$CHECKOUT" && without_github_token ultikits publish "$WORK/assets/$JAR_NAME" --id "$IDENTIFY" \
            --module-version "$IN_VERSION" --yes "${changelog_args[@]}" > /dev/null 2>&1 ) \
            || { CATALOGUE_OUTCOME="error"; refuse catalogue_upload_failed "the catalogue upload failed"; }
        CATALOGUE_OUTCOME="uploaded"
    else
        CATALOGUE_OUTCOME="already-served"
    fi
    helper catalogue-readback --identify-string "$IDENTIFY" --version "$IN_VERSION" \
        --artifact-sha256 "$IN_ARTIFACT_SHA256" --no-level-before "$NO_LEVEL_BEFORE" > "$WORK/readback.env" \
        || { CATALOGUE_OUTCOME="error"; refuse catalogue_readback "the catalogue does not serve the accepted bytes"; }
    read_kv "$WORK/readback.env"
    LEVEL630_AFTER="$IN_LEVEL630_LATEST"
    NO_LEVEL_AFTER="$IN_NO_LEVEL_LATEST"
    # The CLI has now run. Observe GitHub again and download fresh bytes, not the cached assets or just
    # their id/size listing: a same-sized replacement must never be reported as successful publication.
    recheck_github after-catalogue published
    [ "$TAG_SHA" = "$IN_SOURCE_HEAD" ] || refuse tag_elsewhere "the published tag no longer names the accepted source"
    mkdir "$WORK/post-assets" || refuse workspace "no readback directory"
    for pair in "$IN_JAR_ASSET_ID:$JAR_NAME" "$IN_MANIFEST_ASSET_ID:$MANIFEST_NAME"; do
        gh api -H "Accept: application/octet-stream" "repos/$REPO/releases/assets/${pair%%:*}" \
            > "$WORK/post-assets/${pair#*:}" 2>/dev/null \
            || refuse github_unavailable "a published asset could not be read back after upload"
    done
    helper module-verify-assets --assets-dir "$WORK/post-assets" --jar-name "$JAR_NAME" \
        --jar-size "$IN_JAR_ASSET_SIZE" --manifest-size "$IN_MANIFEST_ASSET_SIZE" \
        --repo "$REPO" --identify-string "$IDENTIFY" --source-head "$IN_SOURCE_HEAD" \
        --version "$IN_VERSION" --manifest-sha256 "$IN_MANIFEST_SHA256" --artifact-sha256 "$IN_ARTIFACT_SHA256" \
        > /dev/null || { RELEASE_OUTCOME="error"; refuse github_asset_readback "published assets changed after upload"; }
    echo "catalogue: $IDENTIFY $IN_VERSION served at level 630; no-level latest unchanged"
fi

RESULT="complete"
exit 0
