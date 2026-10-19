#!/usr/bin/env python3
"""Canonical retained-artifact model for the UltiTools-API release handoff.

release.sh builds a release candidate once, in --prepare mode, and keeps the exact bytes it
built. Independent acceptance (UAT, review, a human decision) then judges those bytes, and
--publish-approved publishes the same bytes without building again. This helper is the single
place that defines what "the same bytes" means:

  retain    copy the four payloads of one clean build (primary JAR, POM, sources JAR, javadoc
            JAR) into a NEW retention directory and write the public release manifest beside them
  validate  re-check a manifest, the retained bytes, the source commit they came from and,
            when given, the authority and UAT receipts that accepted that exact manifest
  stage     print the retained files to upload, in manifest order, after re-validating them
  compare   classify assets already on a release (downloaded into a directory) against the
            manifest: identical, partial or absent (reconcilable) or mismatch (never replaced)

Framework publication (publish-packages.yml, after a release with the retained assets is published):

  repository-state  read the primary JAR and POM at their coordinate in a Maven repository (Maven Central, or
                    GitHub Packages with a bearer token taken from an environment variable) and classify them
                    against the manifest: absent, identical, or a refused mismatch or partial coordinate
  central-inputs    validate the downloaded retained files, their source commit and tag and the four detached
                    signatures, then write the private registrar list and compile the release-only Maven core
                    extension (.github/scripts/maven) into an ephemeral helper JAR; nothing product is built
  central-recheck   re-hash every listed file and re-verify the signatures after publication

The manifest is public: it names files by basename and carries no local path, no approval
text and no receipt. Authority stays in the separate receipts, which are never uploaded.

Module publication (module-release.sh, called by each module's existing publish.yml):

  dispatch-inputs verify   read the workflow_dispatch event file as data and check the seven inputs and the
                           runner identity (event, default-branch ref, attempt 1, repository, triggering account)
  package-metadata digest  canonical digest of exact @ultikits/cli 1.2.0 registry metadata
  module-release-state     check the dispatched draft release (id, target, tag, exactly two staged assets)
  module-verify-assets     compare downloaded draft assets with the dispatch digests; manifest and ZIP identity
  module-ref-sha           resolve a tag or branch ref read through gh api
  catalogue-state          observe the UltiCloud catalogue before any write
  catalogue-readback       confirm the catalogue serves the accepted bytes at level 630 after an upload

Standard library only. The helper never runs a product build, only runs read-only git commands and makes no
network write; the module commands read the npm registry and the public catalogue API over https, and
repository-state reads Maven repositories over https. central-inputs and central-recheck run gpg --verify, and
central-inputs compiles the small registrar source with the installed JDK's javac. Exit codes: 0 ok,
1 refused, 2 usage error; compare uses 3 when the remote holds an identical subset that a retry may complete;
the catalogue commands use 4 for a transport, authentication or server error, which is never evidence that
something is absent.
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA_VERSION = 1
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
FORMAL_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
COORD_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
GITHUB_URL = re.compile(r"github\.com[:/]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$")
ROLES = ("javadoc", "pom", "primary", "sources")
MANIFEST_KEYS = ("artifact_id", "artifacts", "bytes", "filename", "group_id", "identify_string",
                 "plugin_api_version", "prior_served_version", "product_tree", "repo", "schema_version",
                 "sha256", "source_head", "version")
ARTIFACT_KEYS = ("bytes", "filename", "role", "sha256")
READ_ONLY_GIT = ("cat-file", "config", "rev-parse", "tag")

# Receipt contracts shared with the private release-evidence checker. The checker remains the
# full validator; this helper re-checks the fields that bind a receipt to one manifest.
AUTHORITY_SCHEMA = "phase21-authority/1"
GATES_SCHEMA = "phase21-gates/1"
PUBLISH_ACTIONS = ["central-publish", "release", "tag"]
PUBLISH_DESTINATIONS = ["GitHub", "MavenCentral"]
REQUIRED_GATES = ("ci", "codacy", "codex-review", "own-review", "tests", "uat", "verification")
QUOTA_SKIPPABLE = ("codacy", "codex-review")
BOOLEAN_APPROVAL_KEYS = ("approved", "approval", "authorized", "is_approved", "granted")


class Problems(object):
    """Collects every refusal reason so one run reports all of them."""

    def __init__(self):
        self.items = []

    def add(self, code, detail):
        self.items.append((code, str(detail)))

    def __bool__(self):
        return bool(self.items)

    __nonzero__ = __bool__

    def emit(self):
        for code, detail in self.items:
            sys.stderr.write("refused: %s: %s\n" % (code, detail))


# ------------------------------------------------------------------------- basics

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical_manifest_bytes(manifest):
    return (json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode("ascii")


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def version_tuple(value):
    match = FORMAL_VERSION.match(value) if isinstance(value, str) else None
    return tuple(int(part) for part in match.groups()) if match else None


def read_regular(path, problems, label):
    """Read a retained file in one pass; size and digest come from the same bytes."""
    path = Path(path)
    try:
        info = os.lstat(str(path))
    except OSError:
        problems.add("file_missing", "%s: %s is missing" % (label, path.name))
        return None
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        problems.add("file_not_regular", "%s: %s must be a regular file, not a link" % (label, path.name))
        return None
    with open(str(path), "rb") as handle:
        return handle.read()


def reject_duplicate_keys(pairs):
    seen = {}
    for key, value in pairs:
        if key in seen:
            raise ValueError("duplicate key %r" % key)
        seen[key] = value
    return seen


def load_json_record(path, problems, label):
    raw = read_regular(path, problems, label)
    if raw is None:
        return None, None
    if not raw.strip():
        problems.add("record_empty", "%s is empty" % label)
        return None, raw
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
    except (UnicodeDecodeError, ValueError) as exc:
        problems.add("record_invalid_json", "%s: %s" % (label, exc))
        return None, raw
    if not isinstance(data, dict) or not data:
        problems.add("record_empty", "%s must be a non-empty JSON object" % label)
        return None, raw
    return data, raw


# ----------------------------------------------------------------------------- git

def run_git(checkout, *args):
    if not args or args[0] not in READ_ONLY_GIT:
        raise RuntimeError("git %s is not a read-only command allowed here" % (args[:1],))
    result = subprocess.run(["git", "-C", str(checkout)] + list(args), stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return result.returncode, result.stdout


def git_text(checkout, *args):
    code, out = run_git(checkout, *args)
    return out.decode("utf-8", "replace").strip() if code == 0 else None


def origin_repo(checkout):
    url = git_text(checkout, "config", "--get", "remote.origin.url")
    match = GITHUB_URL.search(url or "")
    return "%s/%s" % match.groups() if match else None


# ------------------------------------------------------------------- content checks

def open_zip(data, problems, label):
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as exc:
        problems.add("zip_unreadable", "%s: %s" % (label, exc))
        return None, None
    names = archive.namelist()
    if len(names) != len(set(names)):
        problems.add("zip_duplicate_entry", "%s repeats an entry name" % label)
        return None, None
    bad = archive.testzip()
    if bad is not None:
        problems.add("zip_crc_mismatch", "%s: entry %s fails its CRC check" % (label, bad))
        return None, None
    return archive, names


def parse_plugin_yml(text, problems):
    values = {}
    for line in text.splitlines():
        if not line or line[0] in " \t#-":
            continue
        match = re.match(r"^([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$", line)
        if not match:
            continue
        key, value = match.groups()
        if len(value) >= 2 and value[0] in "'\"" and value[-1] == value[0]:
            value = value[1:-1]
        if key in values:
            problems.add("plugin_yml_duplicate_key", key)
        values[key] = value
    return values


def inspect_primary(data, version, problems):
    """Return (main class entry, plugin.yml values) of the shaded plugin JAR."""
    archive, names = open_zip(data, problems, "primary JAR")
    if archive is None:
        return None, {}
    if "plugin.yml" not in names:
        problems.add("plugin_yml_missing", "the primary JAR has no plugin.yml; this is not a plugin JAR")
        return None, {}
    plugin = parse_plugin_yml(archive.read("plugin.yml").decode("utf-8", "replace"), problems)
    if plugin.get("version") != version:
        problems.add("plugin_version_mismatch", "plugin.yml version %r, release %r" % (plugin.get("version"), version))
    main = plugin.get("main") or ""
    entry = main.replace(".", "/") + ".class"
    if not main or entry not in names:
        problems.add("main_class_missing", "plugin.yml main %r has no class entry; wrong JAR selected" % main)
    if not plugin.get("api-version"):
        problems.add("plugin_api_version_missing", "plugin.yml declares no api-version")
    return entry, plugin


def inspect_sources(data, main_entry, problems):
    archive, names = open_zip(data, problems, "sources JAR")
    if archive is None:
        return
    if not any(name.endswith(".java") for name in names):
        problems.add("sources_without_java", "the sources JAR holds no .java entry")
    if main_entry and main_entry[:-len(".class")] + ".java" not in names:
        problems.add("sources_main_missing", "the sources JAR lacks the main class source")
    if any(name.endswith(".class") for name in names):
        problems.add("sources_with_classes", "the sources JAR holds compiled classes")


def inspect_javadoc(data, problems):
    archive, names = open_zip(data, problems, "javadoc JAR")
    if archive is None:
        return
    if "index.html" not in names:
        problems.add("javadoc_index_missing", "the javadoc JAR has no index.html")
    if any(name.endswith(".class") for name in names):
        problems.add("javadoc_with_classes", "the javadoc JAR holds compiled classes")


def inspect_pom(data, version, problems):
    """Return (groupId, artifactId) declared directly by the POM."""
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        problems.add("pom_doctype_refused", "a POM with a DOCTYPE or ENTITY declaration is refused")
        return None, None
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        problems.add("pom_unreadable", exc)
        return None, None
    fields = {}
    for child in root:
        tag = child.tag.split("}", 1)[-1] if isinstance(child.tag, str) else ""
        if tag in ("groupId", "artifactId", "version", "packaging"):
            fields[tag] = (child.text or "").strip()
    if fields.get("version") != version:
        problems.add("pom_version_mismatch", "POM version %r, release %r" % (fields.get("version"), version))
    if fields.get("packaging", "jar") != "jar":
        problems.add("pom_packaging", "packaging %r is not jar" % fields.get("packaging"))
    group, artifact = fields.get("groupId"), fields.get("artifactId")
    if not (group and COORD_RE.match(group)) or not (artifact and COORD_RE.match(artifact)):
        problems.add("pom_coordinates", "groupId/artifactId must be declared directly: %r/%r" % (group, artifact))
        return None, None
    return group, artifact


def payload_names(artifact_id, version):
    base = "%s-%s" % (artifact_id, version)
    return {"primary": base + ".jar", "pom": base + ".pom", "sources": base + "-sources.jar",
            "javadoc": base + "-javadoc.jar"}


def manifest_name(artifact_id, version):
    return "%s-%s-release-manifest.json" % (artifact_id, version)


def inspect_payloads(contents, version, problems):
    """Check the four payload byte strings; return (group, artifact, plugin values)."""
    main_entry, plugin = inspect_primary(contents["primary"], version, problems)
    inspect_sources(contents["sources"], main_entry, problems)
    inspect_javadoc(contents["javadoc"], problems)
    group, artifact = inspect_pom(contents["pom"], version, problems)
    return group, artifact, plugin


# ------------------------------------------------------------------------ manifest

def validate_manifest_shape(manifest, raw, problems):
    if raw != canonical_manifest_bytes(manifest):
        problems.add("manifest_not_canonical", "the manifest bytes are not its canonical serialization")
    missing = sorted(set(MANIFEST_KEYS) - set(manifest))
    unknown = sorted(set(manifest) - set(MANIFEST_KEYS))
    if missing:
        problems.add("manifest_missing_field", ", ".join(missing))
    if unknown:
        problems.add("manifest_unknown_field", "%s (the public manifest allowlists its fields)" % ", ".join(unknown))
    if missing:
        return False
    if manifest["schema_version"] != SCHEMA_VERSION or not is_int(manifest["schema_version"]):
        problems.add("schema_version", "expected %d, got %r" % (SCHEMA_VERSION, manifest["schema_version"]))
    if not isinstance(manifest["repo"], str) or not REPO_RE.match(manifest["repo"]):
        problems.add("repo_format", repr(manifest["repo"]))
    for key in ("group_id", "artifact_id"):
        if not isinstance(manifest[key], str) or not COORD_RE.match(manifest[key]):
            problems.add("coordinate_format", "%s %r" % (key, manifest[key]))
    for key in ("source_head", "product_tree"):
        if not isinstance(manifest[key], str) or not HEX40.match(manifest[key]):
            problems.add("%s_format" % key, repr(manifest[key]))
    version = version_tuple(manifest["version"])
    prior = version_tuple(manifest["prior_served_version"])
    if version is None:
        problems.add("version_format", "%r is not a formal MAJOR.MINOR.PATCH" % manifest["version"])
    if prior is None:
        problems.add("prior_served_version_format", repr(manifest["prior_served_version"]))
    if version is not None and prior is not None:
        if version == prior:
            problems.add("version_collision", "%s equals the served version; equal versions never overwrite"
                         % manifest["version"])
        elif version < prior:
            problems.add("version_regression", "%s is below the served %s" % (manifest["version"],
                                                                              manifest["prior_served_version"]))
    identify = manifest["identify_string"]
    if identify is not None and (not isinstance(identify, str) or not identify):
        problems.add("identify_string_format", repr(identify))
    if not isinstance(manifest["plugin_api_version"], str) or not manifest["plugin_api_version"]:
        problems.add("plugin_api_version_format", repr(manifest["plugin_api_version"]))
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        problems.add("artifacts_empty", "an empty artifact list is never a release candidate")
        return False
    ok = True
    expected = payload_names(manifest["artifact_id"], manifest["version"]) if version else {}
    roles = []
    for index, artifact in enumerate(artifacts):
        where = "artifacts[%d]" % index
        if not isinstance(artifact, dict) or sorted(artifact) != sorted(ARTIFACT_KEYS):
            problems.add("artifact_fields", "%s must have exactly %s" % (where, ", ".join(ARTIFACT_KEYS)))
            ok = False
            continue
        roles.append(artifact["role"])
        if artifact["role"] not in ROLES:
            problems.add("artifact_role", "%s role %r" % (where, artifact["role"]))
            ok = False
        elif artifact["filename"] != expected.get(artifact["role"]):
            problems.add("artifact_filename", "%s: %r is not the exact name %r" % (
                where, artifact["filename"], expected.get(artifact["role"])))
            ok = False
        if not is_int(artifact["bytes"]) or artifact["bytes"] <= 0:
            problems.add("artifact_bytes", where)
        if not isinstance(artifact["sha256"], str) or not HEX64.match(artifact["sha256"]):
            problems.add("artifact_sha256", where)
    if sorted(roles) != list(ROLES):
        problems.add("artifact_roles", "exactly one each of %s, got %s" % (", ".join(ROLES), roles))
        ok = False
    if ok:
        names = [a["filename"] for a in artifacts]
        if names != sorted(names):
            problems.add("artifacts_not_sorted", "artifacts must be sorted by filename")
        primary = [a for a in artifacts if a["role"] == "primary"][0]
        for key in ("filename", "bytes", "sha256"):
            if manifest[key] != primary[key]:
                problems.add("primary_mismatch", "top-level %s differs from the primary artifact" % key)
    return ok


def load_manifest(path, problems):
    manifest, raw = load_json_record(path, problems, "manifest")
    if manifest is None:
        return None, raw
    if not validate_manifest_shape(manifest, raw, problems):
        return None, raw
    expected = manifest_name(manifest["artifact_id"], manifest["version"])
    if Path(path).name != expected:
        problems.add("manifest_filename", "%r is not the exact name %r" % (Path(path).name, expected))
    return manifest, raw


def check_retained(manifest, folder, problems):
    """Re-hash every retained payload and re-inspect its content; return role -> bytes."""
    contents = {}
    for artifact in manifest["artifacts"]:
        data = read_regular(Path(folder) / artifact["filename"], problems, artifact["role"])
        if data is None:
            continue
        if len(data) != artifact["bytes"]:
            problems.add("bytes_mismatch", "%s: manifest %d, retained %d" % (artifact["filename"], artifact["bytes"],
                                                                             len(data)))
        if sha256_bytes(data) != artifact["sha256"]:
            problems.add("sha256_mismatch", "%s: retained bytes differ from the accepted digest"
                         % artifact["filename"])
        contents[artifact["role"]] = data
    if len(contents) != len(ROLES):
        return None
    group, artifact_id, plugin = inspect_payloads(contents, manifest["version"], problems)
    if (group, artifact_id) != (manifest["group_id"], manifest["artifact_id"]):
        problems.add("pom_coordinates", "POM %s:%s, manifest %s:%s" % (group, artifact_id, manifest["group_id"],
                                                                       manifest["artifact_id"]))
    if plugin.get("api-version") != manifest["plugin_api_version"]:
        problems.add("plugin_api_version_mismatch", "plugin.yml %r, manifest %r" % (
            plugin.get("api-version"), manifest["plugin_api_version"]))
    if plugin.get("identify-string") != manifest["identify_string"]:
        problems.add("identify_string_mismatch", "plugin.yml %r, manifest %r (exact comparison)" % (
            plugin.get("identify-string"), manifest["identify_string"]))
    return contents


def check_source(manifest, checkout, pom_bytes, problems):
    head = manifest["source_head"]
    if git_text(checkout, "cat-file", "-t", head) != "commit":
        problems.add("source_head_unknown", "%s is not a commit in this checkout" % head)
        return
    tree = git_text(checkout, "rev-parse", "--verify", head + "^{tree}")
    if tree != manifest["product_tree"]:
        problems.add("product_tree_mismatch", "commit tree %s, manifest %s" % (tree, manifest["product_tree"]))
    repo = origin_repo(checkout)
    if repo != manifest["repo"]:
        problems.add("source_repo_mismatch", "origin %r, manifest %r" % (repo, manifest["repo"]))
    code, blob = run_git(checkout, "cat-file", "blob", head + ":pom.xml")
    if code != 0:
        problems.add("pom_unreadable", "no pom.xml at %s" % head)
    elif pom_bytes is not None and blob != pom_bytes:
        problems.add("pom_not_source_bytes", "the retained POM differs from pom.xml at %s" % head)


# ------------------------------------------------------------------------ receipts

def check_tag(manifest, tag, checkout, problems):
    """The release tag must be v<version> and, in the checkout, name exactly the accepted source commit."""
    if tag != "v" + manifest["version"]:
        problems.add("tag_mismatch", "tag %r is not v%s" % (tag, manifest["version"]))
        return
    if not checkout:
        problems.add("tag_unverified", "checking the tag needs --source-checkout")
        return
    target = git_text(Path(checkout), "rev-parse", "-q", "--verify", "refs/tags/%s^{commit}" % tag)
    if target != manifest["source_head"]:
        problems.add("tag_mismatch", "tag %s names %r, the accepted source is %s" % (tag, target,
                                                                                  manifest["source_head"]))


def find_boolean_approvals(value, where):
    found = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in BOOLEAN_APPROVAL_KEYS and item is True:
                found.append("%s.%s" % (where, key))
            found.extend(find_boolean_approvals(item, "%s.%s" % (where, key)))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            found.extend(find_boolean_approvals(item, "%s[%d]" % (where, index)))
    return found


def check_retained_ref(ref, base, problems, where):
    """An evidence reference is {path, sha256} of a retained file next to the receipt."""
    if not isinstance(ref, dict) or not isinstance(ref.get("path"), str) or not ref.get("path"):
        problems.add("evidence_missing", "%s needs {path, sha256} of a retained file" % where)
        return None
    path = Path(ref["path"])
    path = path if path.is_absolute() else Path(base) / path
    data = read_regular(path, problems, where)
    if data is None:
        return None
    if not data.strip():
        problems.add("evidence_empty", where)
        return None
    if sha256_bytes(data) != ref.get("sha256"):
        problems.add("evidence_sha256_mismatch", "%s does not match its recorded digest" % where)
        return None
    return data


def check_authority(manifest, manifest_sha, path, problems):
    receipt, _ = load_json_record(path, problems, "authority receipt")
    if receipt is None:
        return
    for where in find_boolean_approvals(receipt, "authority"):
        problems.add("boolean_approval_rejected", "%s: approval is a retained human response, never a flag" % where)
    if receipt.get("schema") != AUTHORITY_SCHEMA or receipt.get("evidence_state") != "observed":
        problems.add("authority_schema", "expected an observed %s receipt" % AUTHORITY_SCHEMA)
        return
    if receipt.get("action") != "framework-publish":
        problems.add("authority_action", "receipt action %r is not framework-publish" % receipt.get("action"))
    if receipt.get("decision") != "publish":
        problems.add("authority_not_publish", "decision %r does not grant publication" % receipt.get("decision"))
    if not isinstance(receipt.get("question"), str) or not receipt["question"].strip():
        problems.add("authority_question_missing", "the question asked must be retained")
    response = receipt.get("human_response")
    text = check_retained_ref(response, Path(path).resolve().parent, problems, "authority.human_response")
    quote = receipt.get("response_decision_quote")
    if text is not None:
        try:
            decoded = text.decode("utf-8")
        except UnicodeDecodeError:
            decoded = None
            problems.add("response_not_utf8", "the retained response is not UTF-8 text")
        if decoded is not None and (not isinstance(quote, str) or not quote.strip() or quote not in decoded):
            problems.add("decision_not_in_response", "the decision quote must appear verbatim in the response")
    scope = receipt.get("scope")
    if not isinstance(scope, dict):
        problems.add("authority_scope", "scope must be an object")
        return
    if receipt.get("repo") != manifest["repo"] or scope.get("repo") != manifest["repo"]:
        problems.add("authority_repo", "receipt repo does not match %s" % manifest["repo"])
    for key in ("source_head", "product_tree", "version"):
        if scope.get(key) != manifest[key]:
            problems.add("authority_stale", "scope.%s %r does not match the manifest %r" % (key, scope.get(key),
                                                                                         manifest[key]))
    if scope.get("manifest_sha256") != manifest_sha:
        problems.add("authority_stale", "scope.manifest_sha256 does not match this manifest's bytes")
    expected = [{"bytes": a["bytes"], "filename": a["filename"], "sha256": a["sha256"]}
                for a in manifest["artifacts"]]
    if scope.get("artifacts") != expected:
        problems.add("authority_stale", "scope.artifacts do not equal the manifest's accepted files")
    if scope.get("destinations") != PUBLISH_DESTINATIONS:
        problems.add("authority_destinations", "destinations must be exactly %s" % PUBLISH_DESTINATIONS)
    if scope.get("allowed_actions") != PUBLISH_ACTIONS:
        problems.add("authority_actions", "allowed_actions must be exactly %s, got %r" % (
            PUBLISH_ACTIONS, scope.get("allowed_actions")))


def check_uat(manifest, manifest_sha, path, problems):
    record, _ = load_json_record(path, problems, "UAT receipt")
    if record is None:
        return
    if record.get("schema") != GATES_SCHEMA or record.get("evidence_state") != "observed":
        problems.add("uat_schema", "expected an observed %s record" % GATES_SCHEMA)
        return
    if record.get("release_authorized") is not False:
        problems.add("release_authorized_not_false", "a gates record never grants release authority")
    if record.get("repo") != manifest["repo"] or record.get("source_head") != manifest["source_head"]:
        problems.add("uat_stale", "gates repo/source_head do not match the manifest")
    if record.get("candidate_sha256") != manifest["sha256"]:
        problems.add("uat_stale", "gates candidate_sha256 is not the retained primary JAR")
    if record.get("manifest_sha256") != manifest_sha:
        problems.add("uat_stale", "gates manifest_sha256 does not match this manifest's bytes")
    gates = record.get("gates")
    if not isinstance(gates, list) or not gates:
        problems.add("uat_gates_missing", "the gates record lists no gate")
        return
    base = Path(path).resolve().parent
    seen = {}
    for entry in gates:
        name = entry.get("gate") if isinstance(entry, dict) else None
        if name not in REQUIRED_GATES or name in seen:
            problems.add("uat_gate_invalid", "unknown or repeated gate %r" % name)
            continue
        seen[name] = entry
    for name in REQUIRED_GATES:
        entry = seen.get(name)
        if entry is None:
            problems.add("uat_gate_missing", name)
            continue
        status = entry.get("status")
        if status == "pass":
            if entry.get("head") != manifest["source_head"]:
                problems.add("uat_stale", "gate %s ran on %r" % (name, entry.get("head")))
            check_retained_ref(entry.get("evidence"), base, problems, "gate %s evidence" % name)
        elif status == "not-configured" and name == "codacy" and entry.get("configured") is False:
            continue
        elif status == "quota-skipped" and name in QUOTA_SKIPPABLE and entry.get("provider_message"):
            check_retained_ref(entry.get("provider_evidence"), base, problems, "gate %s provider evidence" % name)
        else:
            problems.add("uat_gate_not_passed", "gate %s is %r" % (name, status))
    uat = seen.get("uat")
    if uat is not None and uat.get("status") == "pass":
        if uat.get("artifact_sha256") != manifest["sha256"]:
            problems.add("uat_stale", "UAT accepted %r, not the retained primary JAR" % uat.get("artifact_sha256"))
        if uat.get("independent") is not True or uat.get("saw_review_output") is not False:
            problems.add("uat_not_independent", "UAT must be independent and must not see review output")
        if uat.get("release_authorized") is not False:
            problems.add("release_authorized_not_false", "UAT verdicts keep release_authorized false")


# ------------------------------------------------------------------------ commands

def cmd_retain(args):
    problems = Problems()
    checkout = Path(args.source_checkout).resolve()
    head, version = args.source_head, args.version
    if not HEX40.match(head or ""):
        problems.add("source_head_format", repr(head))
    if version_tuple(version) is None:
        problems.add("version_format", "%r is not a formal MAJOR.MINOR.PATCH" % version)
    if problems:
        problems.emit()
        return 1
    if git_text(checkout, "rev-parse", "HEAD") != head:
        problems.add("source_head_moved", "checkout HEAD is not %s" % head)
    tree = git_text(checkout, "rev-parse", "--verify", head + "^{tree}")
    repo = origin_repo(checkout)
    if repo is None:
        problems.add("source_repo_unresolved", "origin is not a GitHub repository URL")
    code, pom_blob = run_git(checkout, "cat-file", "blob", head + ":pom.xml")
    if code != 0:
        problems.add("pom_unreadable", "no pom.xml at %s" % head)
        pom_blob = None
    pom_file = read_regular(checkout / "pom.xml", problems, "pom")
    if pom_blob is not None and pom_file is not None and pom_blob != pom_file:
        problems.add("pom_not_source_bytes", "the working pom.xml differs from %s" % head)
    group = artifact_id = None
    if pom_file is not None:
        group, artifact_id = inspect_pom(pom_file, version, problems)
    contents, plugin = {}, {}
    if artifact_id:
        names = payload_names(artifact_id, version)
        sources = {"primary": checkout / "target" / names["primary"], "sources": checkout / "target" / names["sources"],
                   "javadoc": checkout / "target" / names["javadoc"], "pom": checkout / "pom.xml"}
        for role, source in sources.items():
            data = read_regular(source, problems, role)
            if data is not None:
                contents[role] = data
        if len(contents) == len(ROLES):
            _, _, plugin = inspect_payloads(contents, version, problems)
    prior = prior_served_version(checkout, version, problems)
    retain = Path(os.path.abspath(args.retain_dir))
    if os.path.lexists(str(retain)):
        problems.add("retain_dir_exists", "prepare writes into a new directory only")
    elif not retain.parent.is_dir():
        problems.add("retain_parent_missing", "the parent of the retention directory must exist")
    else:
        real_parent = os.path.realpath(str(retain.parent))
        real_checkout = os.path.realpath(str(checkout))
        if os.path.commonpath([real_parent, real_checkout]) == real_checkout:
            problems.add("retain_dir_inside_checkout", "retained files must live outside the source checkout")
    if problems:
        problems.emit()
        return 1
    artifacts = []
    for role in ROLES:
        data = contents[role]
        artifacts.append({"bytes": len(data), "filename": names[role], "role": role, "sha256": sha256_bytes(data)})
    artifacts.sort(key=lambda item: item["filename"])
    primary = contents["primary"]
    manifest = {
        "schema_version": SCHEMA_VERSION, "repo": repo, "group_id": group, "artifact_id": artifact_id,
        "version": version, "source_head": head, "product_tree": tree, "filename": names["primary"],
        "bytes": len(primary), "sha256": sha256_bytes(primary), "identify_string": plugin.get("identify-string"),
        "prior_served_version": prior, "plugin_api_version": plugin.get("api-version"), "artifacts": artifacts,
    }
    os.mkdir(str(retain), 0o755)
    for role in ROLES:
        write_once(retain / names[role], contents[role])
    manifest_path = retain / manifest_name(artifact_id, version)
    write_once(manifest_path, canonical_manifest_bytes(manifest))
    check = Problems()
    written, _ = load_manifest(manifest_path, check)
    if written is not None:
        check_retained(written, retain, check)
    if check:
        check.emit()
        return 1
    print(str(manifest_path))
    return 0


def write_once(path, data):
    """Create a file that did not exist, flush it to disk and make it read-only."""
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(str(path), 0o444)


def prior_served_version(checkout, version, problems):
    """The highest formal version tag below the release version is the served version."""
    listing = git_text(checkout, "tag", "--list", "v*")
    target = version_tuple(version)
    candidates = []
    for tag in (listing or "").splitlines():
        parsed = version_tuple(tag.strip()[1:])
        if parsed is None:
            continue
        if parsed == target:
            problems.add("version_collision", "tag %s already exists" % tag.strip())
        elif parsed < target:
            candidates.append(parsed)
    if not candidates:
        problems.add("prior_served_version_unresolved", "no formal version tag below %s" % version)
        return None
    return "%d.%d.%d" % max(candidates)


def validated(args, problems):
    manifest, raw = load_manifest(args.manifest, problems)
    if manifest is None:
        return None, None
    folder = Path(os.path.abspath(args.manifest)).parent
    contents = check_retained(manifest, folder, problems)
    manifest_sha = sha256_bytes(raw)
    if getattr(args, "source_checkout", None):
        check_source(manifest, Path(args.source_checkout), (contents or {}).get("pom"), problems)
    expected_head = getattr(args, "expected_source_head", None)
    if expected_head is not None and expected_head != manifest["source_head"]:
        problems.add("source_head_mismatch", "the run is at %r, the accepted source is %s" % (
            expected_head, manifest["source_head"]))
    tag = getattr(args, "tag", None)
    if tag is not None:
        check_tag(manifest, tag, getattr(args, "source_checkout", None), problems)
    authority = getattr(args, "authority_receipt", None)
    uat = getattr(args, "uat_receipt", None)
    if authority or uat:
        if not (authority and uat):
            problems.add("receipt_missing", "publication needs both the authority and the UAT receipt")
        else:
            check_authority(manifest, manifest_sha, authority, problems)
            check_uat(manifest, manifest_sha, uat, problems)
    return manifest, manifest_sha


def cmd_validate(args):
    problems = Problems()
    manifest, manifest_sha = validated(args, problems)
    if problems or manifest is None:
        problems.emit()
        return 1
    if args.shell:
        fields = (("REPO", manifest["repo"]), ("VERSION", manifest["version"]), ("TAG", "v" + manifest["version"]),
                  ("SOURCE_HEAD", manifest["source_head"]), ("PRODUCT_TREE", manifest["product_tree"]),
                  ("MANIFEST_SHA256", manifest_sha), ("PRIMARY_FILENAME", manifest["filename"]),
                  ("PRIMARY_BYTES", str(manifest["bytes"])), ("PRIMARY_SHA256", manifest["sha256"]))
        for key, value in fields:
            print("%s=%s" % (key, value))
    else:
        print(json.dumps({"status": "ok", "version": manifest["version"], "source_head": manifest["source_head"],
                          "manifest_sha256": manifest_sha, "primary_sha256": manifest["sha256"]}, sort_keys=True))
    return 0


def cmd_stage(args):
    problems = Problems()
    manifest, _ = validated(args, problems)
    if problems or manifest is None:
        problems.emit()
        return 1
    folder = Path(os.path.abspath(args.manifest)).parent
    paths = [str(folder / a["filename"]) for a in manifest["artifacts"]] + [str(Path(os.path.abspath(args.manifest)))]
    if any("\n" in p or "\r" in p for p in paths):
        sys.stderr.write("refused: path_format: a retained path contains a line break\n")
        return 1
    for path in paths:
        print(path)
    return 0


def cmd_compare(args):
    """Compare a release's downloaded assets with the accepted manifest, byte for byte."""
    problems = Problems()
    manifest, raw = load_manifest(args.manifest, problems)
    if manifest is None:
        problems.emit()
        return 1
    expected = dict((a["filename"], a["sha256"]) for a in manifest["artifacts"])
    expected[Path(args.manifest).name] = sha256_bytes(raw)
    remote = Path(args.remote_dir)
    present, mismatched, unexpected = [], [], []
    for entry in sorted(os.listdir(str(remote))):
        path = remote / entry
        if entry not in expected:
            unexpected.append(entry)
            problems.add("unexpected_asset", "%s is not part of the accepted candidate" % entry)
            continue
        data = read_regular(path, problems, entry)
        if data is None:
            mismatched.append(entry)
            continue
        present.append(entry)
        if sha256_bytes(data) != expected[entry]:
            mismatched.append(entry)
            problems.add("asset_mismatch", "%s on the release differs from the accepted bytes" % entry)
    draft = None
    if args.release_json:
        release, _ = load_json_record(args.release_json, problems, "release view")
        if release is not None:
            draft = release.get("isDraft")
            if release.get("tagName") != "v" + manifest["version"]:
                problems.add("release_tag", "release tag %r is not v%s" % (release.get("tagName"), manifest["version"]))
            if release.get("targetCommitish") != manifest["source_head"]:
                problems.add("release_target", "the release targets %r, the accepted source is %s" % (
                    release.get("targetCommitish"), manifest["source_head"]))
            if not isinstance(draft, bool):
                problems.add("release_draft_unknown", "isDraft must be a boolean")
            listed = release.get("assets")
            if not isinstance(listed, list):
                problems.add("release_assets_unknown", "the release view lists no assets array")
                listed = []
            for item in listed:
                name = item.get("name") if isinstance(item, dict) else None
                if name not in present and name not in mismatched and name not in unexpected:
                    problems.add("asset_not_read_back", "%r is listed on the release but was not downloaded" % name)
                elif name in present and item.get("size") != (remote / name).stat().st_size:
                    problems.add("asset_mismatch", "%s: listed size differs from the downloaded bytes" % name)
            names = set(item.get("name") for item in listed if isinstance(item, dict))
            for name in present:
                if name not in names:
                    problems.add("asset_not_listed", "%s was downloaded but is not listed on the release" % name)
    missing = sorted(set(expected) - set(present) - set(mismatched))
    if problems:
        status = "mismatch"
    elif not missing:
        status = "identical"
    elif not present:
        status = "absent"
    else:
        status = "partial"
    problems.emit()
    if args.shell:
        print("STATUS=%s" % status)
        print("DRAFT=%s" % ("true" if draft else "false"))
        for name in missing:
            print("MISSING=%s" % name)
    else:
        print(json.dumps({"status": status, "missing": missing, "mismatched": sorted(mismatched),
                          "unexpected": unexpected, "draft": draft}, sort_keys=True))
    return {"identical": 0, "mismatch": 1}.get(status, 3)


# ------------------------------------------------------------ framework publication
#
# publish-packages.yml publishes the retained, accepted files of a published release; it never builds. The
# commands below check those files again in the credentialed job, observe the target repositories before and
# after a write, and prepare the Central handoff: a private list of the exact files and a release-only Maven
# core extension (.github/scripts/maven) that assigns them to the project Maven reads from the retained POM, so
# the existing central-publishing-maven-plugin publishes those bytes and nothing a build would produce.

REGISTRAR_DIR = Path(os.path.abspath(__file__)).parent / "maven"
REGISTRAR_SOURCE = "RetainedArtifactsParticipant.java"
REGISTRAR_DESCRIPTOR = "components.xml"
REGISTRAR_CLASS = "com/ultikits/release/RetainedArtifactsParticipant.class"
REGISTRAR_IMPLEMENTATION = "com.ultikits.release.RetainedArtifactsParticipant"
REGISTRAR_FORMAT = "ultitools-retained-artifacts/1"
REGISTRAR_FIELDS = ("role", "type", "classifier", "file", "bytes", "sha256")
# (entry role, Maven type, classifier, manifest role of the payload, file-name suffix)
REGISTRAR_LAYOUT = (
    ("pom", "pom", "", "pom", ""),
    ("primary", "jar", "", "primary", ""),
    ("sources", "jar", "sources", "sources", ""),
    ("javadoc", "jar", "javadoc", "javadoc", ""),
    ("pom-signature", "pom.asc", "", "pom", ".asc"),
    ("primary-signature", "jar.asc", "", "primary", ".asc"),
    ("sources-signature", "jar.asc", "sources", "sources", ".asc"),
    ("javadoc-signature", "jar.asc", "javadoc", "javadoc", ".asc"),
)
ARMOR_HEADER = b"-----BEGIN PGP SIGNATURE-----"
REPOSITORY_LIMIT = 512 << 20
SIGNATURE_FAILURES = ("BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "NO_PUBKEY")


def cmd_repository_state(args):
    """Classify the primary JAR and POM at their coordinate in a Maven repository against the manifest."""
    problems = Problems()
    manifest, _ = load_manifest(args.manifest, problems)
    if manifest is None:
        problems.emit()
        return 1
    artifact_id = args.artifact_id
    if artifact_id not in (manifest["artifact_id"], manifest["artifact_id"].lower()):
        problems.add("artifact_id", "%r is neither the manifest artifactId nor its lower-case form" % artifact_id)
    base = args.base_url.rstrip("/")
    if not base.startswith("https://"):
        problems.add("base_url", "the repository URL must be https")
    authorization = None
    if args.token_env:
        token = os.environ.get(args.token_env, "")
        if not token:
            problems.add("token_missing", "the environment variable %s is empty" % args.token_env)
        authorization = "Bearer " + token
    if problems:
        problems.emit()
        return 1
    expected = dict((a["role"], a["sha256"]) for a in manifest["artifacts"])
    version = manifest["version"]
    coordinate = "%s:%s:%s" % (manifest["group_id"], artifact_id, version)
    observed = {}
    try:
        for role, extension in (("primary", ".jar"), ("pom", ".pom")):
            url = "%s/%s/%s/%s/%s-%s%s" % (base, manifest["group_id"].replace(".", "/"), artifact_id, version,
                                           artifact_id, version, extension)
            status, body = https_get(url, REPOSITORY_LIMIT, accept="*/*", authorization=authorization)
            if status == 404:
                observed[role] = "absent"
            elif status == 200:
                observed[role] = "identical" if sha256_bytes(body) == expected[role] else "different"
            else:
                raise FetchError("the %s request answered HTTP %d" % (role, status))
    except FetchError as exc:
        sys.stderr.write("error: repository_unreadable: %s (never read as absent)\n" % exc)
        return 4
    states = set(observed.values())
    if "different" in states:
        problems.add("remote_mismatch", "%s holds bytes that differ from the accepted files; a published coordinate"
                     " is never replaced" % coordinate)
    elif len(states) > 1:
        problems.add("remote_partial", "%s holds only part of the coordinate (%s)" % (
            coordinate, ", ".join("%s %s" % item for item in sorted(observed.items()))))
    if problems:
        problems.emit()
        return 1
    print("STATE=%s" % states.pop())
    return 0


def check_publication_paths(inputs, work, scratch, checkout, problems):
    """Retained inputs, helper output and publisher scratch must be disjoint, link-free and new where written."""
    named = [("inputs", inputs), ("work", work), ("scratch", scratch)]
    if checkout:
        named.append(("source checkout", checkout))
    resolved = []
    for name, path in named:
        absolute = os.path.abspath(str(path))
        if os.path.realpath(absolute) != absolute:
            problems.add("path_link", "the %s path resolves through a symbolic link" % name)
        resolved.append((name, absolute.rstrip(os.sep) or os.sep))
    for index, (first, a) in enumerate(resolved):
        for second, b in resolved[index + 1:]:
            if a == b or a.startswith(b + os.sep) or b.startswith(a + os.sep):
                problems.add("path_overlap", "the %s and %s paths overlap; they must stay disjoint" % (first, second))
    for name, path in (("work", work), ("scratch", scratch)):
        if os.path.lexists(str(path)):
            problems.add("%s_dir_exists" % name, "the %s directory must not exist yet" % name)
        elif not os.path.isdir(os.path.dirname(os.path.abspath(str(path)))):
            problems.add("%s_parent_missing" % name, "the parent of the %s directory must exist" % name)
    folder = Path(os.path.abspath(str(inputs)))
    for directory in [folder] + list(folder.parents):
        if os.path.lexists(str(directory / ".mvn")):
            problems.add("maven_config_dir", "a .mvn directory at or above the inputs would configure Maven")
            break


def check_inputs_listing(manifest, folder, problems):
    expected = set(a["filename"] for a in manifest["artifacts"])
    expected |= set(name + ".asc" for name in list(expected))
    expected.add(manifest_name(manifest["artifact_id"], manifest["version"]))
    try:
        actual = set(os.listdir(str(folder)))
    except OSError:
        problems.add("inputs_unreadable", "the inputs directory cannot be listed")
        return
    if actual != expected:
        problems.add("inputs_listing", "the inputs directory must hold exactly the manifest, the four retained files "
                     "and their signatures (unexpected: %s; missing: %s)" % (
                         ", ".join(sorted(actual - expected)) or "none", ", ".join(sorted(expected - actual)) or "none"))


def verify_signatures(manifest, folder, gpg, problems):
    """Verify each detached signature with gpg; return the one signing key fingerprint, or None."""
    fingerprints = set()
    for artifact in manifest["artifacts"]:
        name = artifact["filename"]
        signature = read_regular(Path(folder) / (name + ".asc"), problems, name + ".asc")
        if signature is None:
            continue
        if not signature.startswith(ARMOR_HEADER):
            problems.add("signature_format", "%s.asc is not an ASCII-armoured detached signature" % name)
            continue
        try:
            proc = subprocess.run([gpg, "--batch", "--no-tty", "--status-fd", "1", "--verify",
                                   str(Path(folder) / (name + ".asc")), str(Path(folder) / name)],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError:
            problems.add("gpg_unavailable", "gpg cannot be run")
            return None
        status = [line.split() for line in proc.stdout.decode("utf-8", "replace").splitlines()
                  if line.startswith("[GNUPG:] ")]
        words = [fields[1] for fields in status if len(fields) > 1]
        valid = [fields for fields in status if len(fields) > 2 and fields[1] == "VALIDSIG"]
        if proc.returncode != 0 or "GOODSIG" not in words or any(w in SIGNATURE_FAILURES for w in words) \
                or len(valid) != 1:
            problems.add("signature_invalid", "%s.asc does not verify as one good signature over %s" % (name, name))
            continue
        fingerprints.add(valid[0][-1].upper())
    if len(fingerprints) > 1:
        problems.add("signature_signer", "the detached signatures were made by different keys")
        return None
    return fingerprints.pop() if fingerprints else None


def registrar_entries(manifest, folder):
    """The eight registrar entries in order: (role, type, classifier, absolute path)."""
    names = payload_names(manifest["artifact_id"], manifest["version"])
    return [(role, kind, classifier, os.path.join(os.path.abspath(str(folder)), names[payload] + suffix))
            for role, kind, classifier, payload, suffix in REGISTRAR_LAYOUT]


def check_descriptor(data, problems):
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        problems.add("registrar_descriptor", "components.xml does not parse")
        return
    components = root.findall("components/component")
    if b"<!DOCTYPE" in data or len(components) != 1 \
            or components[0].findtext("role") != "org.apache.maven.AbstractMavenLifecycleParticipant" \
            or components[0].findtext("role-hint") != "retained-artifacts" \
            or components[0].findtext("implementation") != REGISTRAR_IMPLEMENTATION:
        problems.add("registrar_descriptor", "components.xml must declare exactly the retained-artifacts participant")


def build_registrar_jar(work, lib, problems):
    """Compile only the registrar source against the active Maven distribution and package the helper JAR."""
    source = REGISTRAR_DIR / REGISTRAR_SOURCE
    descriptor = read_regular(REGISTRAR_DIR / REGISTRAR_DESCRIPTOR, problems, "registrar descriptor")
    if read_regular(source, problems, "registrar source") is None or descriptor is None:
        return None
    check_descriptor(descriptor, problems)
    java_home = os.environ.get("JAVA_HOME")
    javac = os.path.join(java_home, "bin", "javac") if java_home else None
    if not javac or not os.path.isfile(javac):
        javac = shutil.which("javac")
    if not javac:
        problems.add("javac_missing", "the installed JDK's javac is required to compile the registrar")
    if problems:
        return None
    classes = Path(work) / "classes"
    os.mkdir(str(classes), 0o700)
    proc = subprocess.run([javac, "--release", "8", "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-nowarn",
                           "-Xlint:-options", "-cp", os.path.join(str(lib), "*"), "-d", str(classes), str(source)],
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        problems.add("registrar_compile", proc.stdout.decode("utf-8", "replace").strip()[-2000:])
        return None
    compiled = sorted(str(p.relative_to(classes)).replace(os.sep, "/") for p in classes.rglob("*") if p.is_file())
    if compiled != [REGISTRAR_CLASS]:
        problems.add("registrar_compile", "expected exactly %s, got %s" % (REGISTRAR_CLASS, compiled))
        return None
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in (("META-INF/plexus/components.xml", descriptor),
                           (REGISTRAR_CLASS, (classes / REGISTRAR_CLASS).read_bytes())):
            archive.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), data)
    jar = Path(work) / "retained-artifacts-helper.jar"
    write_private(jar, buffer.getvalue())
    return jar


def write_private(path, data):
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        view = memoryview(data)
        while view:
            view = view[os.write(fd, view):]
        os.fsync(fd)
    finally:
        os.close(fd)


def cmd_central_inputs(args):
    """Validate the downloaded retained files and signatures, then write the registrar list and helper JAR."""
    problems = Problems()
    manifest, _ = validated(args, problems)
    if manifest is None:
        problems.emit()
        return 1
    folder = Path(os.path.abspath(args.manifest)).parent
    work, scratch = Path(os.path.abspath(args.work_dir)), Path(os.path.abspath(args.scratch_dir))
    check_inputs_listing(manifest, folder, problems)
    check_publication_paths(folder, work, scratch, args.source_checkout, problems)
    lib = Path(args.maven_home) / "lib" if args.maven_home else None
    if lib is None or not lib.is_dir() or not list(lib.glob("maven-core*.jar")):
        problems.add("maven_home", "--maven-home must name the active Maven distribution (lib/maven-core*.jar)")
    fingerprint = verify_signatures(manifest, folder, args.gpg, problems)
    entries = registrar_entries(manifest, folder)
    for _, _, _, path in entries + [("", "", "", str(work)), ("", "", "", str(scratch))]:
        if any(ord(c) < 0x21 or ord(c) > 0x7e for c in path) or "\\" in path:
            problems.add("path_format", "paths handed to Maven must be printable ASCII without spaces or backslashes")
            break
    if problems or fingerprint is None:
        if not problems:
            problems.add("signature_invalid", "no detached signature verified")
        problems.emit()
        return 1
    lines = ["# Written by release_artifacts.py central-inputs for RetainedArtifactsParticipant; private, never uploaded.",
             "format=" + REGISTRAR_FORMAT, "group_id=" + manifest["group_id"], "artifact_id=" + manifest["artifact_id"],
             "version=" + manifest["version"], "packaging=jar", "count=%d" % len(entries)]
    for index, (role, kind, classifier, path) in enumerate(entries, 1):
        data = read_regular(path, problems, os.path.basename(path))
        if data is None:
            break
        values = (role, kind, classifier, path, str(len(data)), sha256_bytes(data))
        lines += ["artifact.%d.%s=%s" % (index, field, value) for field, value in zip(REGISTRAR_FIELDS, values)]
    if problems:
        problems.emit()
        return 1
    os.mkdir(str(work), 0o700)
    os.mkdir(str(scratch), 0o700)
    properties = work / "registrar.properties"
    write_private(properties, ("\n".join(lines) + "\n").encode("ascii"))
    jar = build_registrar_jar(work, lib, problems)
    if jar is None:
        problems.emit()
        return 1
    fields = (("RETAINED_POM", entries[0][3]), ("PROPERTIES", str(properties)), ("HELPER_JAR", str(jar)),
              ("HELPER_JAR_SHA256", sha256_bytes(jar.read_bytes())), ("STAGING_DIR", str(scratch / "staging")),
              ("DEFERRED_DIR", str(scratch / "deferred")), ("OUTPUT_DIR", str(scratch / "output")),
              ("SIGNER_FINGERPRINT", fingerprint), ("VERSION", manifest["version"]))
    if args.shell:
        emit_kv(fields)
    else:
        print(json.dumps(dict(fields), sort_keys=True))
    return 0


def cmd_central_recheck(args):
    """After publication: every listed file is unchanged and every signature still verifies."""
    problems = Problems()
    manifest, _ = validated(args, problems)
    if manifest is None:
        problems.emit()
        return 1
    folder = Path(os.path.abspath(args.manifest)).parent
    raw = read_regular(args.properties, problems, "registrar list")
    listed = {}
    for line in (raw or b"").decode("ascii", "replace").splitlines():
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            listed[key] = value
    entries = registrar_entries(manifest, folder)
    accepted = dict((a["role"], a["sha256"]) for a in manifest["artifacts"])
    for index, (role, kind, classifier, path) in enumerate(entries, 1):
        prefix = "artifact.%d." % index
        if (listed.get(prefix + "role"), listed.get(prefix + "type"), listed.get(prefix + "classifier"),
                listed.get(prefix + "file")) != (role, kind, classifier, path):
            problems.add("registrar_list", "entry %d does not name %s" % (index, os.path.basename(path)))
            continue
        data = read_regular(path, problems, os.path.basename(path))
        if data is None:
            continue
        if str(len(data)) != listed.get(prefix + "bytes"):
            problems.add("bytes_mismatch", "%s changed size after it was listed" % os.path.basename(path))
        if sha256_bytes(data) != listed.get(prefix + "sha256"):
            problems.add("sha256_mismatch", "%s changed after it was listed" % os.path.basename(path))
        payload = REGISTRAR_LAYOUT[index - 1]
        if not payload[4] and listed.get(prefix + "sha256") != accepted.get(payload[3]):
            problems.add("sha256_mismatch", "the listed %s digest is not the accepted one" % role)
    verify_signatures(manifest, folder, args.gpg, problems)
    if problems:
        problems.emit()
        return 1
    print("RECHECK=ok")
    return 0


# ------------------------------------------------------------- module publication
#
# The commands below serve module-release.sh, the shared adapter every module's existing publish.yml calls on
# an explicit workflow_dispatch. They print only validated KEY=VALUE lines (never raw input text) and refuse
# with exit 1. Network reads use urllib over https only; there is no network write here.

# The seven dispatch inputs. GitHub delivers workflow_dispatch inputs as strings; each must match in full
# (fullmatch, so a trailing newline is not accepted the way a `$` anchor would accept it).
DISPATCH_INPUT_FORMATS = {
    "artifact_sha256": re.compile(r"[0-9a-f]{64}"),
    "draft_release_id": re.compile(r"[1-9][0-9]{0,18}"),
    "installation_id": re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{7,63}"),
    "manifest_sha256": re.compile(r"[0-9a-f]{64}"),
    "package_metadata_sha256": re.compile(r"[0-9a-f]{64}"),
    "source_head": re.compile(r"[0-9a-f]{40}"),
    "version": re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"),
}
LOGIN_FORMAT = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}")
BRANCH_FORMAT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,99}")
IDENTIFY_FORMAT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
RUN_ID_FORMAT = re.compile(r"[1-9][0-9]{0,19}")
EVENT_LIMIT = 1 << 20

PACKAGE_NAME, PACKAGE_PIN = "@ultikits/cli", "1.2.0"
REGISTRY_DOCUMENT_URL = "https://registry.npmjs.org/@ultikits%2fcli"
CATALOGUE_API = "https://api.ultikits.com"
MODULE_API_LEVEL = "630"
MODULE_MANIFEST_SCHEMA = "phase21-candidate/1"
MODULE_MANIFEST_KEYS = ("artifacts", "evidence_state", "formal_acceptance", "identify_string", "kind", "label",
                        "plugin_api_version", "prior_served_version", "product_tree", "release_authorized", "repo",
                        "schema", "source_head", "version")
SERVED_VERSION_FORMAT = re.compile(r"[0-9]+(\.[0-9]+){0,3}")


class FetchError(Exception):
    """A transport, authentication or server failure: an error, never evidence that something is absent."""


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def dispatch_inputs_sha256(inputs):
    """Canonical SHA-256 of the dispatch inputs: sorted keys, compact separators, ASCII escaping.

    The private release-evidence checker writes the same digest into the dispatch record; both test suites
    assert one shared fixture literal."""
    return hashlib.sha256(canonical_json(inputs).encode("ascii")).hexdigest()


def package_metadata_projection(doc):
    """The fields a human verifies for exact @ultikits/cli 1.2.0: publisher, integrity, scripts, dependencies."""
    if not isinstance(doc, dict):
        raise ValueError("metadata must be the exact-version JSON object")
    user, dist = doc.get("_npmUser"), doc.get("dist")
    scripts, deps = doc.get("scripts", {}), doc.get("dependencies", {})
    if not isinstance(doc.get("name"), str) or not isinstance(doc.get("version"), str):
        raise ValueError("metadata needs name and version strings")
    if not isinstance(user, dict) or not isinstance(user.get("name"), str):
        raise ValueError("metadata needs _npmUser.name")
    if not isinstance(dist, dict) or not isinstance(dist.get("integrity"), str):
        raise ValueError("metadata needs dist.integrity")
    if not isinstance(scripts, dict) or not isinstance(deps, dict):
        raise ValueError("scripts and dependencies must be objects")
    return {"name": doc["name"], "version": doc["version"], "publisher": user["name"],
            "dist_integrity": dist["integrity"], "scripts": scripts, "dependencies": deps}


def package_metadata_sha256(doc):
    return hashlib.sha256(canonical_json(package_metadata_projection(doc)).encode("ascii")).hexdigest()


def https_get(url, limit, accept="application/json", authorization=None):
    """GET over https; return (status, body). 4xx statuses are returned; transport errors raise FetchError.

    An authorization value is sent as an unredirected header: urllib does not copy it to a redirect target, so
    a token for one host is never handed to the storage host a repository redirects a download to.
    """
    if not url.startswith("https://"):
        raise FetchError("refusing a non-https URL")
    request = urllib.request.Request(url, headers={"User-Agent": "ultitools-module-release", "Accept": accept})
    if authorization:
        request.add_unredirected_header("Authorization", authorization)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            final = response.geturl() if hasattr(response, "geturl") else url
            if not str(final).startswith("https://"):
                raise FetchError("redirected away from https")
            body = response.read(limit + 1)
            status = getattr(response, "status", None) or response.getcode()
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(limit + 1)
        except Exception:  # noqa: BLE001 - the status is what matters for an HTTP error
            body = b""
        status = exc.code
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise FetchError("transport failure: %s" % type(exc).__name__)
    if len(body) > limit:
        raise FetchError("response larger than %d bytes" % limit)
    return status, body


def emit_kv(pairs):
    for key, value in pairs:
        print("%s=%s" % (key, value))


def cmd_dispatch_inputs_verify(args):
    problems = Problems()
    for value, pattern, code in ((args.repository, REPO_RE, "repository_format"),
                                 (args.default_branch, BRANCH_FORMAT, "default_branch_format"),
                                 (args.maintainer, LOGIN_FORMAT, "maintainer_format")):
        if not isinstance(value, str) or not pattern.fullmatch(value):
            problems.add(code, "the wrapper-passed literal is malformed")
    if ".." in (args.default_branch or ""):
        problems.add("default_branch_format", "the wrapper-passed literal is malformed")
    if problems:
        problems.emit()
        return 2
    env = os.environ
    if env.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
        problems.add("event_not_dispatch", "publication runs only on workflow_dispatch")
    if env.get("GITHUB_REF") != "refs/heads/" + args.default_branch:
        problems.add("ref_not_default", "the run is not on refs/heads/%s" % args.default_branch)
    if env.get("GITHUB_RUN_ATTEMPT") != "1":
        problems.add("rerun_refused", "only run attempt 1 may install or write; a rerun needs a new dispatch")
    if env.get("GITHUB_REPOSITORY") != args.repository:
        problems.add("repository_mismatch", "the run is not in %s" % args.repository)
    if env.get("GITHUB_TRIGGERING_ACTOR") != args.maintainer:
        problems.add("actor_not_maintainer", "only %s may start a publication run" % args.maintainer)
    run_id = env.get("GITHUB_RUN_ID", "")
    if not RUN_ID_FORMAT.fullmatch(run_id):
        problems.add("run_id_format", "GITHUB_RUN_ID is not a run number")
    event_path = env.get("GITHUB_EVENT_PATH")
    payload = None
    if not event_path:
        problems.add("event_missing", "GITHUB_EVENT_PATH is not set")
    else:
        raw = read_regular(event_path, problems, "event payload")
        if raw is not None and len(raw) > EVENT_LIMIT:
            problems.add("event_too_large", "the event payload exceeds %d bytes" % EVENT_LIMIT)
        elif raw is not None:
            try:
                payload = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
            except (UnicodeDecodeError, ValueError):
                problems.add("event_invalid_json", "the event payload is not one JSON object with unique keys")
    inputs = None
    if payload is not None:
        if not isinstance(payload, dict) or not isinstance(payload.get("inputs"), dict):
            problems.add("inputs_missing", "the event payload carries no inputs object")
        else:
            inputs = payload["inputs"]
            if "ref" in payload and payload["ref"] != env.get("GITHUB_REF"):
                problems.add("ref_not_default", "the event ref differs from the run ref")
            for key in sorted(set(DISPATCH_INPUT_FORMATS) - set(inputs)):
                problems.add("inputs_missing_key", key)
            for key in sorted(set(inputs) - set(DISPATCH_INPUT_FORMATS)):
                problems.add("inputs_unknown_key", "an input outside the seven-key allowlist")
            for key in sorted(set(inputs) & set(DISPATCH_INPUT_FORMATS)):
                value = inputs[key]
                if not isinstance(value, str) or not value:
                    problems.add("input_format", "%s must be a non-empty string" % key)
                elif not DISPATCH_INPUT_FORMATS[key].fullmatch(value):
                    problems.add("input_format", "%s does not match its required format" % key)
    if problems or inputs is None:
        problems.emit()
        return 1
    emit_kv([("INPUTS_SHA256", dispatch_inputs_sha256(inputs))] +
            [(key.upper(), inputs[key]) for key in sorted(DISPATCH_INPUT_FORMATS)] +
            [("EVENT", "workflow_dispatch"), ("REF", env["GITHUB_REF"]), ("RUN_ID", run_id),
             ("RUN_ATTEMPT", "1"), ("TRIGGERING_ACTOR", args.maintainer), ("REPOSITORY", args.repository)])
    return 0


def exact_version_metadata(document):
    """Pick the exact pinned version out of a registry package document."""
    versions = document.get("versions") if isinstance(document, dict) else None
    doc = versions.get(PACKAGE_PIN) if isinstance(versions, dict) else None
    if not isinstance(doc, dict):
        raise ValueError("the registry document has no %s entry" % PACKAGE_PIN)
    return doc


def cmd_package_metadata_digest(args):
    problems = Problems()
    if args.metadata_file:
        doc, _ = load_json_record(args.metadata_file, problems, "metadata file")
        if doc is None:
            problems.emit()
            return 1
    else:
        try:
            status, body = https_get(REGISTRY_DOCUMENT_URL, 8 << 20)
        except FetchError as exc:
            sys.stderr.write("refused: registry_unavailable: %s\n" % exc)
            return 1
        if status != 200:
            sys.stderr.write("refused: registry_unavailable: HTTP %d\n" % status)
            return 1
        try:
            doc = exact_version_metadata(json.loads(body.decode("utf-8"), object_pairs_hook=reject_duplicate_keys))
        except (UnicodeDecodeError, ValueError) as exc:
            sys.stderr.write("refused: registry_document_invalid: %s\n" % exc)
            return 1
    try:
        digest = package_metadata_sha256(doc)
    except ValueError as exc:
        sys.stderr.write("refused: metadata_invalid: %s\n" % exc)
        return 1
    if doc.get("name") != PACKAGE_NAME or doc.get("version") != PACKAGE_PIN:
        sys.stderr.write("refused: metadata_identity: the document is not %s@%s\n" % (PACKAGE_NAME, PACKAGE_PIN))
        return 1
    if args.save_metadata:
        target = Path(os.path.abspath(args.save_metadata))
        if os.path.lexists(str(target)):
            sys.stderr.write("refused: metadata_snapshot_exists: snapshots are written once\n")
            return 1
        write_once(target, (json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode("ascii"))
    print(digest)
    return 0


def jar_name_for(prefix, version):
    return "%s-%s.jar" % (prefix, version)


def manifest_asset_name(jar_name):
    return jar_name[:-len(".jar")] + "-release-manifest.json"


def cmd_module_release_state(args):
    """Validate the draft release named by the dispatch: id, target, tag and the exact two staged assets."""
    problems = Problems()
    release, _ = load_json_record(args.release_json, problems, "release view")
    if release is None:
        problems.emit()
        return 1
    jar, manifest = args.jar_name, manifest_asset_name(args.jar_name)
    if release.get("id") != int(args.draft_release_id) or isinstance(release.get("id"), bool):
        problems.add("release_id_mismatch", "the release is not the dispatched draft")
    if not isinstance(release.get("draft"), bool):
        problems.add("release_draft_unknown", "draft must be a boolean")
    if release.get("target_commitish") != args.source_head:
        problems.add("release_target_mismatch", "the draft does not target the accepted source commit")
    if release.get("tag_name") != "v" + args.version:
        problems.add("release_tag_mismatch", "the draft is not tagged v%s" % args.version)
    assets = release.get("assets")
    found = {}
    if not isinstance(assets, list):
        problems.add("release_assets_mismatch", "the release view lists no assets array")
        assets = []
    for item in assets:
        name = item.get("name") if isinstance(item, dict) else None
        if name not in (jar, manifest) or name in found:
            problems.add("release_assets_mismatch", "the draft must hold exactly the retained JAR and its manifest")
            continue
        if not is_int(item.get("id")) or item["id"] <= 0 or not is_int(item.get("size")) or item["size"] <= 0:
            problems.add("release_assets_mismatch", "asset id and size must be positive integers")
            continue
        found[name] = item
    for name in (jar, manifest):
        if name not in found:
            problems.add("release_assets_mismatch", "a staged asset is missing from the draft")
    if problems:
        problems.emit()
        return 1
    emit_kv([("RELEASE_STATE", "draft" if release["draft"] else "published"),
             ("JAR_ASSET_ID", found[jar]["id"]), ("JAR_ASSET_SIZE", found[jar]["size"]),
             ("MANIFEST_ASSET_ID", found[manifest]["id"]), ("MANIFEST_ASSET_SIZE", found[manifest]["size"])])
    return 0


def cmd_module_verify_assets(args):
    """Compare downloaded draft assets with the dispatch digests, then check manifest and ZIP identity."""
    problems = Problems()
    folder = Path(args.assets_dir)
    jar_name, manifest_name = args.jar_name, manifest_asset_name(args.jar_name)
    jar = read_regular(folder / jar_name, problems, "staged JAR")
    raw = read_regular(folder / manifest_name, problems, "staged manifest")
    if jar is not None and sha256_bytes(jar) != args.artifact_sha256:
        problems.add("artifact_sha256_mismatch", "the staged JAR does not hash to artifact_sha256")
    if raw is not None and sha256_bytes(raw) != args.manifest_sha256:
        problems.add("manifest_sha256_mismatch", "the staged manifest does not hash to manifest_sha256")
    if jar is not None and args.jar_size is not None and len(jar) != args.jar_size:
        problems.add("asset_size_mismatch", "the downloaded JAR size differs from the release listing")
    if raw is not None and args.manifest_size is not None and len(raw) != args.manifest_size:
        problems.add("asset_size_mismatch", "the downloaded manifest size differs from the release listing")
    if problems:
        problems.emit()
        return 1
    try:
        manifest = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
    except (UnicodeDecodeError, ValueError):
        problems.add("manifest_invalid", "the staged manifest is not one JSON object with unique keys")
        problems.emit()
        return 1
    if not isinstance(manifest, dict) or sorted(manifest) != sorted(MODULE_MANIFEST_KEYS):
        problems.add("manifest_fields", "the staged manifest must carry exactly the candidate manifest fields")
        problems.emit()
        return 1
    expected = {"schema": MODULE_MANIFEST_SCHEMA, "evidence_state": "observed", "kind": "module",
                "label": "final-candidate", "release_authorized": False, "repo": args.repo,
                "source_head": args.source_head, "version": args.version, "identify_string": args.identify_string,
                "plugin_api_version": MODULE_API_LEVEL}
    for key, value in sorted(expected.items()):
        if manifest[key] != value or isinstance(manifest[key], bool) != isinstance(value, bool):
            problems.add("%s_mismatch" % key if key in ("identify_string", "version", "repo", "source_head")
                         else "manifest_value_mismatch", "manifest %s does not match the dispatch" % key)
    if not isinstance(manifest["formal_acceptance"], bool):
        problems.add("manifest_value_mismatch", "formal_acceptance must be a boolean")
    if not isinstance(manifest["product_tree"], str) \
            or not DISPATCH_INPUT_FORMATS["source_head"].fullmatch(manifest["product_tree"]):
        problems.add("product_tree_format", "the manifest product tree is not 40 hex")
    prior, version = version_tuple(manifest["prior_served_version"]), version_tuple(args.version)
    if prior is None or version is None or not prior < version:
        problems.add("version_not_greater", "the manifest's prior served version is not below this version")
    artifacts = manifest["artifacts"]
    want = [{"bytes": len(jar), "filename": jar_name, "role": "primary", "sha256": args.artifact_sha256}]
    if artifacts != want:
        problems.add("manifest_artifact_mismatch", "the manifest must list exactly the staged JAR as its primary")
    _, plugin = inspect_primary(jar, args.version, problems)
    if plugin.get("api-version") != MODULE_API_LEVEL:
        problems.add("plugin_api_version_mismatch", "plugin.yml api-version is not %s" % MODULE_API_LEVEL)
    if plugin.get("identify-string") != args.identify_string:
        problems.add("identify_string_mismatch", "plugin.yml identify-string differs (exact comparison)")
    if problems:
        problems.emit()
        return 1
    emit_kv([("PRODUCT_TREE", manifest["product_tree"]), ("JAR_BYTES", len(jar))])
    return 0


def cmd_module_ref_sha(args):
    """Print the commit a ref resolves to, from a gh api ref (object) or matching-refs (array) response."""
    problems = Problems()
    raw = read_regular(args.refs_json, problems, "ref view")
    data = None
    if raw is not None:
        try:
            data = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
        except (UnicodeDecodeError, ValueError):
            problems.add("record_invalid_json", "the ref view is not JSON")
    if problems:
        problems.emit()
        return 1
    entries = data if isinstance(data, list) else [data]
    matches = [e for e in entries if isinstance(e, dict) and e.get("ref") == args.ref]
    if not matches and args.allow_absent and isinstance(data, list):
        print("SHA=absent")
        return 0
    target = matches[0].get("object") if len(matches) == 1 else None
    sha = target.get("sha") if isinstance(target, dict) else None
    if not isinstance(target, dict) or target.get("type") != "commit" or not isinstance(sha, str) \
            or not DISPATCH_INPUT_FORMATS["source_head"].fullmatch(sha):
        sys.stderr.write("refused: ref_not_commit: the ref does not resolve to exactly one commit\n")
        return 1
    print("SHA=%s" % sha)
    return 0


def catalogue_call(path):
    """GET one catalogue API path; return (status, data). 404/409 are answers; anything else non-200 errors."""
    status, body = https_get(CATALOGUE_API + path, 4 << 20)
    if status not in (200, 404, 409):
        raise FetchError("catalogue answered HTTP %d" % status)
    try:
        envelope = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise FetchError("catalogue answered a non-JSON body")
    if not isinstance(envelope, dict) or envelope.get("code") != str(status):
        raise FetchError("catalogue envelope does not match its HTTP status")
    return status, envelope.get("data")


def served_tuple(version):
    if not isinstance(version, str) or not SERVED_VERSION_FORMAT.fullmatch(version):
        return None
    parts = [int(p) for p in version.split(".")]
    return tuple(parts + [0] * (4 - len(parts)))


def catalogue_plugin_id(identify):
    status, data = catalogue_call("/plugin/get?identifyString=" + urllib.parse.quote(identify, safe=""))
    if status != 200 or not isinstance(data, dict) or not is_int(data.get("id")) or data["id"] <= 0 \
            or data.get("identifyString", identify) != identify:
        return None
    return data["id"]


def catalogue_latest(plugin_id, level630):
    query = "?apiLevel=630&minApiLevel=630" if level630 else ""
    status, data = catalogue_call("/plugin/%d/latest%s" % (plugin_id, query))
    if status == 200:
        if served_tuple(data) is None:
            raise FetchError("catalogue latest is not a comparable version")
        return data
    return "none"


def download_sha256(link):
    if not isinstance(link, str) or not link.startswith("https://"):
        raise FetchError("served download link is not https")
    status, body = https_get(link, 256 << 20)
    if status != 200:
        raise FetchError("served download answered HTTP %d" % status)
    return sha256_bytes(body)


def cmd_catalogue_state(args):
    """Observe the catalogue before any write: module identity, every served version and the latest answers."""
    try:
        plugin_id = catalogue_plugin_id(args.identify_string)
        if plugin_id is None:
            sys.stderr.write("refused: catalogue_module_missing: the exact identity is not a catalogue module\n")
            return 1
        status, versions = catalogue_call("/plugin/%d/versions-detail" % plugin_id)
        if status != 200 or not isinstance(versions, list):
            raise FetchError("catalogue version history unavailable")
        target = served_tuple(args.version)
        state = "absent"
        for entry in versions:
            served = entry.get("version") if isinstance(entry, dict) else None
            parsed = served_tuple(served)
            if parsed is None:
                sys.stderr.write("refused: catalogue_version_not_comparable: a served version cannot be ordered\n")
                return 1
            if served == args.version:
                # An equal version is never permission to overwrite. It is accepted only as the recovery of a
                # completed upload: these exact accepted bytes already served at level 630.
                status, link = catalogue_call("/plugin/%d/%s/download?apiLevel=630&minApiLevel=630"
                                              % (plugin_id, urllib.parse.quote(args.version, safe="")))
                if status != 200 or download_sha256(link) != args.artifact_sha256:
                    sys.stderr.write("refused: catalogue_version_occupied: %s is served with other bytes or at "
                                     "another level\n" % args.version)
                    return 1
                state = "identical"
            elif parsed >= target:
                sys.stderr.write("refused: version_not_greater: a served version is not below %s\n" % args.version)
                return 1
        no_level = catalogue_latest(plugin_id, False)
        level630 = catalogue_latest(plugin_id, True)
    except FetchError as exc:
        sys.stderr.write("refused: catalogue_unavailable: %s\n" % exc)
        return 4
    emit_kv([("CATALOGUE_STATE", state), ("PLUGIN_ID", plugin_id), ("NO_LEVEL_LATEST", no_level),
             ("LEVEL630_LATEST", level630)])
    return 0


def cmd_catalogue_readback(args):
    """After an upload: level 630 selects these exact bytes and the no-level answer is unchanged."""
    problems = Problems()
    try:
        plugin_id = catalogue_plugin_id(args.identify_string)
        if plugin_id is None:
            raise FetchError("catalogue module disappeared")
        level630 = catalogue_latest(plugin_id, True)
        no_level = catalogue_latest(plugin_id, False)
        if level630 != args.version:
            problems.add("level630_not_selected", "level 630 does not select %s" % args.version)
        if no_level != args.no_level_before:
            problems.add("no_level_changed", "the no-level answer changed")
        status, link = catalogue_call("/plugin/%d/%s/download?apiLevel=630&minApiLevel=630"
                                      % (plugin_id, urllib.parse.quote(args.version, safe="")))
        if status != 200:
            problems.add("served_download_missing", "the new version has no level-630 download")
        elif download_sha256(link) != args.artifact_sha256:
            problems.add("served_bytes_mismatch", "the catalogue serves bytes other than the accepted JAR")
    except FetchError as exc:
        sys.stderr.write("refused: catalogue_unavailable: %s\n" % exc)
        return 4
    if problems:
        problems.emit()
        return 1
    emit_kv([("LEVEL630_LATEST", level630), ("NO_LEVEL_LATEST", no_level)])
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="release_artifacts.py", description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command")
    retain = sub.add_parser("retain", help="retain one clean build's payloads and write the manifest")
    retain.add_argument("--source-checkout", required=True)
    retain.add_argument("--source-head", required=True)
    retain.add_argument("--version", required=True)
    retain.add_argument("--retain-dir", required=True)
    validate = sub.add_parser("validate", help="re-check a manifest, its retained bytes, source and receipts")
    validate.add_argument("--manifest", required=True)
    validate.add_argument("--source-checkout")
    validate.add_argument("--authority-receipt")
    validate.add_argument("--uat-receipt")
    validate.add_argument("--expected-source-head", help="the commit the run is at (GITHUB_SHA)")
    validate.add_argument("--tag", help="the release tag; must be v<version> naming the accepted source")
    validate.add_argument("--shell", action="store_true", help="print KEY=VALUE lines of validated fields")
    stage = sub.add_parser("stage", help="print the retained files to upload, in manifest order")
    stage.add_argument("--manifest", required=True)
    compare = sub.add_parser("compare", help="classify a release's downloaded assets against the manifest")
    compare.add_argument("--manifest", required=True)
    compare.add_argument("--remote-dir", required=True)
    compare.add_argument("--release-json", help="output of gh release view --json tagName,targetCommitish,isDraft,assets")
    compare.add_argument("--shell", action="store_true", help="print STATUS, DRAFT and MISSING lines")
    repo_state = sub.add_parser("repository-state", help="classify the coordinate in a Maven repository")
    repo_state.add_argument("--manifest", required=True)
    repo_state.add_argument("--base-url", required=True)
    repo_state.add_argument("--artifact-id", required=True)
    repo_state.add_argument("--token-env", help="name of the environment variable holding a bearer token")
    central = sub.add_parser("central-inputs", help="validate retained files and signatures; prepare the registrar")
    central.add_argument("--manifest", required=True)
    central.add_argument("--source-checkout", required=True)
    central.add_argument("--expected-source-head", required=True)
    central.add_argument("--tag", required=True)
    central.add_argument("--maven-home", required=True)
    central.add_argument("--work-dir", required=True)
    central.add_argument("--scratch-dir", required=True)
    central.add_argument("--gpg", default="gpg")
    central.add_argument("--shell", action="store_true", help="print KEY=VALUE lines")
    recheck = sub.add_parser("central-recheck", help="re-hash the listed files and re-verify the signatures")
    recheck.add_argument("--manifest", required=True)
    recheck.add_argument("--properties", required=True)
    recheck.add_argument("--gpg", default="gpg")
    dispatch = sub.add_parser("dispatch-inputs", help="workflow_dispatch input checks for module publication")
    dispatch_sub = dispatch.add_subparsers(dest="action")
    verify = dispatch_sub.add_parser("verify", help="check GITHUB_EVENT_PATH inputs and the runner identity")
    verify.add_argument("--repository", required=True, help="reviewed owner/name literal from the wrapper")
    verify.add_argument("--default-branch", required=True, help="reviewed default branch literal")
    verify.add_argument("--maintainer", required=True, help="reviewed maintainer login literal")
    metadata = sub.add_parser("package-metadata", help="exact @ultikits/cli 1.2.0 registry metadata")
    metadata_sub = metadata.add_subparsers(dest="action")
    digest = metadata_sub.add_parser("digest", help="print the canonical metadata digest")
    digest.add_argument("--metadata-file", help="hash a saved exact-version document instead of fetching")
    digest.add_argument("--save-metadata", help="also write the fetched exact-version document (once)")
    state = sub.add_parser("module-release-state", help="check the dispatched draft release view")
    state.add_argument("--release-json", required=True)
    state.add_argument("--draft-release-id", required=True)
    state.add_argument("--source-head", required=True)
    state.add_argument("--version", required=True)
    state.add_argument("--jar-name", required=True)
    assets = sub.add_parser("module-verify-assets", help="compare downloaded draft assets with the dispatch")
    assets.add_argument("--assets-dir", required=True)
    assets.add_argument("--jar-name", required=True)
    assets.add_argument("--jar-size", type=int)
    assets.add_argument("--manifest-size", type=int)
    assets.add_argument("--repo", required=True)
    assets.add_argument("--identify-string", required=True)
    assets.add_argument("--source-head", required=True)
    assets.add_argument("--version", required=True)
    assets.add_argument("--manifest-sha256", required=True)
    assets.add_argument("--artifact-sha256", required=True)
    ref = sub.add_parser("module-ref-sha", help="resolve a ref from a gh api response")
    ref.add_argument("--refs-json", required=True)
    ref.add_argument("--ref", required=True)
    ref.add_argument("--allow-absent", action="store_true", help="print SHA=absent for an empty match list")
    cat_state = sub.add_parser("catalogue-state", help="observe the catalogue before any write")
    cat_state.add_argument("--identify-string", required=True)
    cat_state.add_argument("--version", required=True)
    cat_state.add_argument("--artifact-sha256", required=True)
    cat_back = sub.add_parser("catalogue-readback", help="confirm the catalogue after an upload")
    cat_back.add_argument("--identify-string", required=True)
    cat_back.add_argument("--version", required=True)
    cat_back.add_argument("--artifact-sha256", required=True)
    cat_back.add_argument("--no-level-before", required=True)
    args = parser.parse_args(argv)
    handlers = {"retain": cmd_retain, "validate": cmd_validate, "stage": cmd_stage, "compare": cmd_compare,
                "repository-state": cmd_repository_state, "central-inputs": cmd_central_inputs,
                "central-recheck": cmd_central_recheck,
                "module-release-state": cmd_module_release_state, "module-verify-assets": cmd_module_verify_assets,
                "module-ref-sha": cmd_module_ref_sha, "catalogue-state": cmd_catalogue_state,
                "catalogue-readback": cmd_catalogue_readback}
    nested = {("dispatch-inputs", "verify"): cmd_dispatch_inputs_verify,
              ("package-metadata", "digest"): cmd_package_metadata_digest}
    if (args.command, getattr(args, "action", None)) in nested:
        return nested[(args.command, args.action)](args)
    if args.command not in handlers:
        parser.print_usage(sys.stderr)
        return 2
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
