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

The manifest is public: it names files by basename and carries no local path, no approval
text and no receipt. Authority stays in the separate receipts, which are never uploaded.

Standard library only. The helper never contacts a network service, never runs a build and
only runs read-only git commands. Exit codes: 0 ok, 1 refused, 2 usage error.
"""

import argparse
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
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
    validate.add_argument("--shell", action="store_true", help="print KEY=VALUE lines of validated fields")
    stage = sub.add_parser("stage", help="print the retained files to upload, in manifest order")
    stage.add_argument("--manifest", required=True)
    args = parser.parse_args(argv)
    handlers = {"retain": cmd_retain, "validate": cmd_validate, "stage": cmd_stage}
    if args.command not in handlers:
        parser.print_usage(sys.stderr)
        return 2
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
