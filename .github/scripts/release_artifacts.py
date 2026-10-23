#!/usr/bin/env python3
"""Canonical retained-artifact model for the UltiTools-API release handoff.

release.sh builds a release candidate once, in --prepare mode, and keeps the exact bytes it
built. Independent acceptance (UAT, review, a human decision) then judges those bytes, and
--publish-approved publishes the same bytes without building again. This helper is the single
place that defines what "the same bytes" means:

  retain    copy the four payloads of one clean build (primary JAR, flattened POM, sources JAR,
            javadoc JAR) and the GitHub Packages POM derived from the flattened POM into a NEW
            retention directory and write the public release manifest beside them
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
                    signatures, then write the private registrar list, a private project-model copy of the
                    verified source pom.xml, and compile the release-only Maven core extension
                    (.github/scripts/maven) into an ephemeral helper JAR; nothing product is built
  central-recheck   re-hash every listed file and re-verify the signatures after publication

The manifest is public: it names files by basename and carries no local path, no approval
text and no receipt. Authority stays in the separate receipts, which are never uploaded.

Standard library only. The helper never runs a product build, only runs read-only git commands and makes no
network write. repository-state reads Maven repositories over HTTPS. central-inputs and central-recheck run
gpg --verify, and central-inputs compiles the small registrar source with the installed JDK's javac.
Exit codes: 0 ok, 1 refused, 2 usage error; compare uses 3 when the remote holds an identical subset that a
retry may complete.
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
import xml.etree.ElementTree as element_tree
from pathlib import Path

SCHEMA_VERSION = 1
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
FORMAL_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
COORD_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
GITHUB_URL = re.compile(r"github\.com[:/]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$")
ROLES = ("github-packages-pom", "javadoc", "pom", "primary", "sources")
# The four files Maven Central receives, each with a detached signature. The GitHub Packages POM is retained and
# released with them but is never signed or sent to Central.
CENTRAL_ROLES = ("javadoc", "pom", "primary", "sources")
# GitHub Packages keeps the coordinate com.ultikits:ultitools-api it has always used. Gradle refuses a POM whose
# artifactId differs from its coordinate, so that registry gets the flattened POM with only the project
# artifactId lower-cased (maintainer decision, Phase 21).
GITHUB_PACKAGES_ROLE = "github-packages-pom"
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
REQUIRED_GATES = ("ci", "codacy", "own-review", "tests", "uat", "verification")
CROSS_FAMILY_GATES = ("codex-review", "opus-review")
QUOTA_SKIPPABLE = ("codacy",) + CROSS_FAMILY_GATES
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


def parse_release_xml(data):
    """Parse local release XML without accepting DTDs or entity declarations."""
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise element_tree.ParseError("DOCTYPE and ENTITY declarations are not release metadata")
    # Parses this release's own POM, never untrusted XML.
    parser = element_tree.XMLParser()  # nosemgrep
    parser.feed(data)
    return parser.close()


# The published POM keeps exactly the shape of the UltiTools-API 6.2.5 POM on Maven Central
# (https://repo1.maven.org/maven2/com/ultikits/UltiTools-API/6.2.5/UltiTools-API-6.2.5.pom), the output of the
# build's flatten-maven-plugin: these top-level elements, and a build that only declares the Central publisher
# as a build extension. Anything else (dependencies, repositories, profiles, properties, ...) is refused.
PUBLICATION_POM_ELEMENTS = ("modelVersion", "groupId", "artifactId", "version", "name", "description", "url",
                            "licenses", "developers", "scm", "build")
PUBLICATION_BUILD_PLUGIN = ("org.sonatype.central", "central-publishing-maven-plugin")
PUBLICATION_PLUGIN_ELEMENTS = ("groupId", "artifactId", "version", "extensions")


def local_name(element):
    return element.tag.split("}", 1)[-1] if isinstance(element.tag, str) else ""


def check_publication_build(build, problems):
    """The build may only declare the Central publisher as an extension, as the 6.2.5 POM does."""
    plugins = list(build)
    entries = list(plugins[0]) if len(plugins) == 1 and local_name(plugins[0]) == "plugins" else []
    fields = {}
    if len(entries) == 1 and local_name(entries[0]) == "plugin":
        fields = dict((local_name(child), (child.text or "").strip()) for child in entries[0] if not list(child))
    if sorted(fields) != sorted(PUBLICATION_PLUGIN_ELEMENTS) or len(list(entries[0])) != len(fields) \
            or (fields["groupId"], fields["artifactId"]) != PUBLICATION_BUILD_PLUGIN \
            or fields["extensions"] != "true" or not fields["version"]:
        problems.add("pom_publication_build", "the publication POM's build must only declare %s:%s as an "
                     "extension" % PUBLICATION_BUILD_PLUGIN)


def check_publication_shape(root, problems):
    """The retained publication POM must have exactly the top-level elements of the 6.2.5 published POM."""
    names = [local_name(child) for child in root]
    if sorted(names) != sorted(PUBLICATION_POM_ELEMENTS):
        problems.add("pom_publication_section", "the publication POM has %s; the 6.2.5 published shape is "
                     "exactly %s" % (", ".join(sorted(names)), ", ".join(sorted(PUBLICATION_POM_ELEMENTS))))
        return
    check_publication_build([child for child in root if local_name(child) == "build"][0], problems)


def inspect_pom(data, version, problems, publication=False):
    """Return directly declared coordinates; publication POMs must have the 6.2.5 published shape."""
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        problems.add("pom_doctype_refused", "a POM with a DOCTYPE or ENTITY declaration is refused")
        return None, None
    try:
        root = parse_release_xml(data)
    except element_tree.ParseError as exc:
        problems.add("pom_unreadable", exc)
        return None, None
    if publication:
        check_publication_shape(root, problems)
    fields = {}
    for child in root:
        tag = local_name(child)
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
            "javadoc": base + "-javadoc.jar", GITHUB_PACKAGES_ROLE: base + "-github-packages.pom"}


def github_packages_pom(flattened, artifact_id):
    """The flattened POM with exactly one byte-level change: the project's own artifactId lower-cased.
    Returns None when that element does not occur exactly once."""
    original = ("<artifactId>%s</artifactId>" % artifact_id).encode("ascii")
    if flattened.count(original) != 1:
        return None
    return flattened.replace(original, ("<artifactId>%s</artifactId>" % artifact_id.lower()).encode("ascii"))


def check_github_packages_pom(contents, group, artifact_id, version, problems):
    """The GitHub Packages POM must be exactly the derivation of the retained flattened POM."""
    derived = github_packages_pom(contents["pom"], artifact_id or "")
    if derived is None or derived != contents[GITHUB_PACKAGES_ROLE]:
        problems.add("github_packages_pom_mismatch", "the GitHub Packages POM must equal the flattened POM with "
                     "only its project artifactId lower-cased")
        return
    lower = Problems()
    if inspect_pom(derived, version, lower, publication=True) != (group, artifact_id.lower()):
        problems.add("github_packages_pom_mismatch", "the GitHub Packages POM does not declare %s:%s:%s" % (
            group, artifact_id.lower(), version))


def manifest_name(artifact_id, version):
    return "%s-%s-release-manifest.json" % (artifact_id, version)


def inspect_payloads(contents, version, problems):
    """Check the five retained byte strings; return (group, artifact, plugin values)."""
    main_entry, plugin = inspect_primary(contents["primary"], version, problems)
    inspect_sources(contents["sources"], main_entry, problems)
    inspect_javadoc(contents["javadoc"], problems)
    group, artifact = inspect_pom(contents["pom"], version, problems, publication=True)
    if group and artifact:
        check_github_packages_pom(contents, group, artifact, version, problems)
    return group, artifact, plugin


# ------------------------------------------------------------------------ manifest

def check_manifest_identity(manifest, problems):
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


def check_manifest_artifacts(manifest, problems):
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        problems.add("artifacts_empty", "an empty artifact list is never a release candidate")
        return False
    ok = True
    expected = payload_names(manifest["artifact_id"], manifest["version"]) if version_tuple(manifest["version"]) else {}
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
    check_manifest_identity(manifest, problems)
    return check_manifest_artifacts(manifest, problems)


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


def check_source(manifest, checkout, problems):
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
    source_pom = checked_source_pom(checkout, head, problems)
    if source_pom is not None:
        coordinates = inspect_pom(source_pom, manifest["version"], problems)
        if coordinates != (manifest["group_id"], manifest["artifact_id"]):
            problems.add("pom_coordinates", "the source project coordinates differ from the retained POM")


def checked_source_pom(checkout, head, problems):
    """Check source provenance separately from the flattened publication payload."""
    code, blob = run_git(checkout, "cat-file", "blob", head + ":pom.xml")
    if code != 0:
        problems.add("pom_unreadable", "no pom.xml at %s" % head)
        return None
    working = read_regular(Path(checkout) / "pom.xml", problems, "source POM")
    if working is not None and blob != working:
        problems.add("pom_not_source_bytes", "the working pom.xml differs from %s" % head)
    return blob


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


def check_authority_response(receipt, path, problems):
    response = receipt.get("human_response")
    text = check_retained_ref(response, Path(path).resolve().parent, problems, "authority.human_response")
    quote = receipt.get("response_decision_quote")
    if text is not None:
        try:
            decoded = text.decode("utf-8")
        except UnicodeDecodeError:
            decoded = None
            problems.add("response_not_utf8", "the retained response is not UTF-8 text")
        if decoded is not None:
            if not isinstance(quote, str) or not quote.strip() or quote not in decoded:
                problems.add("decision_not_in_response", "the decision quote must appear verbatim in the response")
            scope = receipt.get("scope")
            rows = scope.get("accepted_blocked_uat_rows") if isinstance(scope, dict) else None
            if isinstance(rows, list) and valid_row_ids(rows):
                for row in rows:
                    if row not in decoded:
                        problems.add("accepted_row_not_in_response",
                                     "accepted blocked UAT row %r must appear verbatim in the response" % row)


def valid_row_ids(rows):
    """Require a non-empty, sorted, unique list of non-empty row-id strings."""
    return (isinstance(rows, list) and bool(rows)
            and all(isinstance(row, str) and bool(row.strip()) for row in rows)
            and rows == sorted(set(rows)))


def check_authority_scope(receipt, manifest, manifest_sha, problems):
    """Bind publication scope exactly, allowing only explicit blocked-UAT row acceptance."""
    scope = receipt.get("scope")
    if not isinstance(scope, dict):
        problems.add("authority_scope", "scope must be an object")
        return
    allowed = {"repo", "source_head", "product_tree", "version", "manifest_sha256", "artifacts",
               "destinations", "allowed_actions", "accepted_blocked_uat_rows"}
    if set(scope) - allowed:
        problems.add("authority_scope", "unknown scope keys: %s" % sorted(set(scope) - allowed))
    if "accepted_blocked_uat_rows" in scope and not valid_row_ids(scope["accepted_blocked_uat_rows"]):
        problems.add("authority_scope", "accepted_blocked_uat_rows must be non-empty, sorted, unique row ids")
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
    check_authority_response(receipt, path, problems)
    check_authority_scope(receipt, manifest, manifest_sha, problems)
    return receipt


def check_blocked_uat_details(entry, problems):
    """Cross-check optional row/count evidence without requiring it on older receipts."""
    blocked = entry.get("blocked_rows")
    if "counts" in entry:
        counts = entry["counts"]
        if not isinstance(counts, dict) or any(
                key in counts and (not is_int(counts[key]) or counts[key] < 0)
                for key in ("fail", "blocked")):
            problems.add("uat_counts_invalid", "UAT counts must contain non-negative integer counts")
        else:
            if counts.get("fail", 0) != 0:
                problems.add("uat_failed_rows", "blocked UAT must not contain failed rows")
            if "blocked" in counts and valid_row_ids(blocked) and counts["blocked"] != len(blocked):
                problems.add("uat_blocked_count_mismatch", "UAT counts.blocked must equal len(blocked_rows)")
    if "rows" in entry:
        rows = entry["rows"]
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
            problems.add("uat_rows_invalid", "UAT rows must be a list of row objects")
            return
        if any(row.get("status") == "fail" for row in rows):
            problems.add("uat_failed_rows", "blocked UAT must not contain failed rows")
        actual = [row.get("id") for row in rows if row.get("status") == "blocked"]
        if not all(isinstance(row, str) for row in actual) or sorted(actual) != blocked:
            problems.add("uat_blocked_rows_mismatch", "blocked-status row ids must exactly match blocked_rows")


def check_gate_evidence(gates, manifest, path, problems, accepted_rows=None):
    """Require all base gates and either review; only explicitly accepted UAT may be blocked."""
    base = Path(path).resolve().parent
    seen = {}
    for entry in gates:
        name = entry.get("gate") if isinstance(entry, dict) else None
        if name not in REQUIRED_GATES + CROSS_FAMILY_GATES or name in seen:
            problems.add("uat_gate_invalid", "unknown or repeated gate %r" % name)
            continue
        seen[name] = entry
    if not any(name in seen for name in CROSS_FAMILY_GATES):
        problems.add("uat_gate_missing", "codex-review|opus-review")
    for name in REQUIRED_GATES + tuple(name for name in CROSS_FAMILY_GATES if name in seen):
        entry = seen.get(name)
        if entry is None:
            problems.add("uat_gate_missing", name)
            continue
        status = entry.get("status")
        blocked_uat = name == "uat" and status == "blocked"
        if status == "pass" or blocked_uat:
            if blocked_uat:
                rows = entry.get("blocked_rows")
                if not valid_row_ids(rows) or accepted_rows is None:
                    problems.add("uat_gate_not_passed", "gate uat is 'blocked' without explicit valid row acceptance")
                elif rows != accepted_rows:
                    problems.add("uat_acceptance_mismatch", "accepted blocked UAT rows must exactly match blocked_rows")
                check_blocked_uat_details(entry, problems)
            if entry.get("head") != manifest["source_head"]:
                problems.add("uat_stale", "gate %s ran on %r" % (name, entry.get("head")))
            check_retained_ref(entry.get("evidence"), base, problems, "gate %s evidence" % name)
        elif status == "not-configured" and name == "codacy" and entry.get("configured") is False:
            continue
        elif status == "quota-skipped" and name in QUOTA_SKIPPABLE and entry.get("provider_message"):
            check_retained_ref(entry.get("provider_evidence"), base, problems, "gate %s provider evidence" % name)
        else:
            problems.add("uat_gate_not_passed", "gate %s is %r" % (name, status))
    return seen


def check_independent_uat(seen, manifest, problems):
    uat = seen.get("uat")
    if uat is not None and uat.get("status") in ("pass", "blocked"):
        if uat.get("artifact_sha256") != manifest["sha256"]:
            problems.add("uat_stale", "UAT accepted %r, not the retained primary JAR" % uat.get("artifact_sha256"))
        if uat.get("independent") is not True or uat.get("saw_review_output") is not False:
            problems.add("uat_not_independent", "UAT must be independent and must not see review output")
        if uat.get("release_authorized") is not False:
            problems.add("release_authorized_not_false", "UAT verdicts keep release_authorized false")


def check_uat(manifest, manifest_sha, path, problems, accepted_rows=None):
    """Validate independent retained-byte UAT, including explicitly accepted blocked rows."""
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
    seen = check_gate_evidence(gates, manifest, path, problems, accepted_rows)
    check_independent_uat(seen, manifest, problems)


# ------------------------------------------------------------------------ commands

def read_build_payloads(checkout, group, artifact_id, version, problems):
    """Read only the named outputs of the same clean verification build."""
    names = {}
    contents, plugin = {}, {}
    if artifact_id:
        names = payload_names(artifact_id, version)
        sources = {"primary": checkout / "target" / names["primary"], "sources": checkout / "target" / names["sources"],
                   "javadoc": checkout / "target" / names["javadoc"], "pom": checkout / ".flattened-pom.xml"}
        for role, source in sources.items():
            data = read_regular(source, problems, role)
            if data is not None:
                contents[role] = data
        if "pom" in contents:
            derived = github_packages_pom(contents["pom"], artifact_id)
            if derived is None:
                problems.add("github_packages_pom_mismatch", "the flattened POM must declare <artifactId>%s"
                             "</artifactId> exactly once" % artifact_id)
            else:
                contents[GITHUB_PACKAGES_ROLE] = derived
        if len(contents) == len(ROLES):
            retained_group, retained_artifact, plugin = inspect_payloads(contents, version, problems)
            if (retained_group, retained_artifact) != (group, artifact_id):
                problems.add("pom_coordinates", "the publication POM coordinates differ from the source project")
    return contents, plugin, names


def check_retain_destination(retain_dir, checkout, problems):
    retain = Path(os.path.abspath(retain_dir))
    if os.path.lexists(str(retain)):
        problems.add("retain_dir_exists", "prepare writes into a new directory only")
    elif not retain.parent.is_dir():
        problems.add("retain_parent_missing", "the parent of the retention directory must exist")
    else:
        real_parent = os.path.realpath(str(retain.parent))
        real_checkout = os.path.realpath(str(checkout))
        if os.path.commonpath([real_parent, real_checkout]) == real_checkout:
            problems.add("retain_dir_inside_checkout", "retained files must live outside the source checkout")
    return retain


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
    pom_file = checked_source_pom(checkout, head, problems)
    group = artifact_id = None
    if pom_file is not None:
        group, artifact_id = inspect_pom(pom_file, version, problems)
    contents, plugin, names = read_build_payloads(checkout, group, artifact_id, version, problems)
    prior = prior_served_version(checkout, version, problems)
    retain = check_retain_destination(args.retain_dir, checkout, problems)
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
    check_retained(manifest, folder, problems)
    manifest_sha = sha256_bytes(raw)
    if getattr(args, "source_checkout", None):
        check_source(manifest, Path(args.source_checkout), problems)
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
            receipt = check_authority(manifest, manifest_sha, authority, problems)
            scope = receipt.get("scope") if receipt else None
            accepted_rows = scope.get("accepted_blocked_uat_rows") if isinstance(scope, dict) else None
            check_uat(manifest, manifest_sha, uat, problems, accepted_rows)
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


def compare_remote_payloads(remote, expected, problems):
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
    return present, mismatched, unexpected


def compare_release_view(release_json, manifest, remote, present, mismatched, unexpected, problems):
    draft = None
    if release_json:
        release, _ = load_json_record(release_json, problems, "release view")
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
    return draft


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
    present, mismatched, unexpected = compare_remote_payloads(remote, expected, problems)
    draft = compare_release_view(args.release_json, manifest, remote, present, mismatched, unexpected, problems)
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
# core extension (.github/scripts/maven) that assigns them to the project Maven reads, so the existing
# central-publishing-maven-plugin publishes those bytes and nothing a build would produce. Maven reads the
# project model from a private copy of the verified source pom.xml, because the plugin only cleans its scratch
# directories for a project whose build declares it, and the source POM's build always does; the registrar then
# makes the retained flattened POM the project file, which is the POM the plugin stages and uploads.

REGISTRAR_DIR = Path(os.path.abspath(__file__)).parent / "maven"
REGISTRAR_SOURCE = "RetainedArtifactsParticipant.java"
REGISTRAR_DESCRIPTOR = "components.xml"
REGISTRAR_CLASS = "com/ultikits/release/RetainedArtifactsParticipant.class"
REGISTRAR_IMPLEMENTATION = "com.ultikits.release.RetainedArtifactsParticipant"
REGISTRAR_FORMAT = "ultitools-retained-artifacts/2"
PROJECT_MODEL_DIR = "project-model"
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
    # The lower-case GitHub Packages coordinate serves the POM that names it; Central serves the flattened POM.
    pom_role = {manifest["artifact_id"]: "pom"}.get(artifact_id, GITHUB_PACKAGES_ROLE)
    observed = {}
    try:
        for role, extension in (("primary", ".jar"), (pom_role, ".pom")):
            url = "%s/%s/%s/%s/%s-%s%s" % (base, manifest["group_id"].replace(".", "/"), artifact_id, version,
                                           artifact_id, version, extension)
            status, body = https_get(url, REPOSITORY_LIMIT, urllib.parse.urlsplit(base).hostname,
                                     accept="*/*", authorization=authorization)
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
    for name, path in (("inputs", inputs), ("work", work)):
        folder = Path(os.path.abspath(str(path)))
        if any(os.path.lexists(str(directory / ".mvn")) for directory in [folder] + list(folder.parents)):
            problems.add("maven_config_dir", "a .mvn directory at or above the %s would configure Maven" % name)


def check_inputs_listing(manifest, folder, problems):
    expected = set(a["filename"] for a in manifest["artifacts"])
    expected |= set(a["filename"] + ".asc" for a in manifest["artifacts"] if a["role"] in CENTRAL_ROLES)
    expected.add(manifest_name(manifest["artifact_id"], manifest["version"]))
    try:
        actual = set(os.listdir(str(folder)))
    except OSError:
        problems.add("inputs_unreadable", "the inputs directory cannot be listed")
        return
    if actual != expected:
        problems.add("inputs_listing", "the inputs directory must hold exactly the manifest, the five retained "
                     "files and the signatures of the four Central files (unexpected: %s; missing: %s)" % (
                         ", ".join(sorted(actual - expected)) or "none", ", ".join(sorted(expected - actual)) or "none"))


def verify_signatures(manifest, folder, gpg, problems):
    """Verify each detached signature with gpg; return the one signing key fingerprint, or None."""
    fingerprints = set()
    for artifact in [a for a in manifest["artifacts"] if a["role"] in CENTRAL_ROLES]:
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
        root = parse_release_xml(data)
    except element_tree.ParseError:
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


def registrar_lines(manifest, entries, project_pom, model, problems):
    """The private registrar list: identity, the project-model POM Maven reads and the eight uploaded files."""
    lines = ["# Written by release_artifacts.py central-inputs for RetainedArtifactsParticipant; private, never uploaded.",
             "format=" + REGISTRAR_FORMAT, "group_id=" + manifest["group_id"], "artifact_id=" + manifest["artifact_id"],
             "version=" + manifest["version"], "packaging=jar", "count=%d" % len(entries),
             "project_pom.file=%s" % project_pom, "project_pom.bytes=%d" % len(model),
             "project_pom.sha256=" + sha256_bytes(model)]
    for index, (role, kind, classifier, path) in enumerate(entries, 1):
        data = read_regular(path, problems, os.path.basename(path))
        if data is None:
            break
        values = (role, kind, classifier, path, str(len(data)), sha256_bytes(data))
        lines += ["artifact.%d.%s=%s" % (index, field, value) for field, value in zip(REGISTRAR_FIELDS, values)]
    return lines


def check_central_maven(lib, problems):
    """The pinned publisher uses the legacy installer layout provided by Maven 3.8/3.9 only."""
    cores = sorted(lib.glob("maven-core*.jar")) if lib is not None and lib.is_dir() else []
    if not cores:
        problems.add("maven_home", "--maven-home must name the active Maven distribution (lib/maven-core*.jar)")
        return
    reason = ("central-publishing-maven-plugin 0.9.0 requires Maven 3.8/3.9 to keep resolver bookkeeping "
              "out of staging; Maven 3.10 adds _remote.repositories and maven-metadata-local.xml")
    try:
        if len(cores) != 1:
            raise ValueError("ambiguous maven-core jars")
        with zipfile.ZipFile(str(cores[0])) as jar:
            raw = jar.read("META-INF/maven/org.apache.maven/maven-core/pom.properties").decode("ascii")
        versions = re.findall(r"^version\s*=\s*(\S+)\s*$", raw, re.MULTILINE)
        if len(versions) != 1 or not re.fullmatch(r"3\.[89]\.\d+", versions[0]):
            raise ValueError("unsupported or missing version: %s" % versions)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        problems.add("maven_version", "%s; cannot accept maven-core version: %s" % (reason, exc))


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
    check_central_maven(lib, problems)
    fingerprint = verify_signatures(manifest, folder, args.gpg, problems)
    entries = registrar_entries(manifest, folder)
    project_pom = work / PROJECT_MODEL_DIR / "pom.xml"
    for _, _, _, path in entries + [("", "", "", str(project_pom)), ("", "", "", str(scratch))]:
        if any(ord(c) < 0x21 or ord(c) > 0x7e for c in path) or "\\" in path:
            problems.add("path_format", "paths handed to Maven must be printable ASCII without spaces or backslashes")
            break
    if problems or fingerprint is None:
        if not problems:
            problems.add("signature_invalid", "no detached signature verified")
        problems.emit()
        return 1
    # validated() already compared this blob with the working pom.xml; the copy keeps Maven out of the checkout.
    model = checked_source_pom(Path(args.source_checkout), manifest["source_head"], problems)
    lines = registrar_lines(manifest, entries, project_pom, model or b"", problems)
    if problems:
        problems.emit()
        return 1
    os.mkdir(str(work), 0o700)
    os.mkdir(str(project_pom.parent), 0o700)
    os.mkdir(str(scratch), 0o700)
    write_private(project_pom, model)
    properties = work / "registrar.properties"
    write_private(properties, ("\n".join(lines) + "\n").encode("ascii"))
    jar = build_registrar_jar(work, lib, problems)
    if jar is None:
        problems.emit()
        return 1
    fields = (("RETAINED_POM", entries[0][3]), ("PROJECT_POM", str(project_pom)),
              ("PROPERTIES", str(properties)), ("HELPER_JAR", str(jar)),
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


# ------------------------------------------------------------- shared publication transport

class FetchError(Exception):
    """A transport, authentication or server failure: an error, never evidence that something is absent."""


def check_https_host(url, expected_host):
    """Only fetch HTTPS URLs at the host chosen by the caller, never local-file schemes."""
    try:
        parsed = urllib.parse.urlsplit(url)
        valid = (parsed.scheme == "https" and parsed.hostname == expected_host and bool(expected_host)
                 and parsed.username is None and parsed.password is None and parsed.port in (None, 443))
    except ValueError:
        valid = False
    if not valid:
        raise FetchError("refusing a URL outside the expected HTTPS host")


def https_get(url, limit, expected_host, accept="application/json", authorization=None):
    """GET over https; return (status, body). 4xx statuses are returned; transport errors raise FetchError.

    An authorization value is sent as an unredirected header: urllib does not copy it to a redirect target, so
    a token for one host is never handed to the storage host a repository redirects a download to.
    """
    check_https_host(url, expected_host)
    request = urllib.request.Request(url, headers={"User-Agent": "ultitools-module-release", "Accept": accept})
    if authorization:
        request.add_unredirected_header("Authorization", authorization)
    try:
        # HTTPS scheme and caller-selected host are checked above; no local-file URL can reach this call.
        with urllib.request.urlopen(request, timeout=60) as response:  # nosemgrep
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
    args = parser.parse_args(argv)
    handlers = {"retain": cmd_retain, "validate": cmd_validate, "stage": cmd_stage, "compare": cmd_compare,
                "repository-state": cmd_repository_state, "central-inputs": cmd_central_inputs,
                "central-recheck": cmd_central_recheck}
    if args.command not in handlers:
        parser.print_usage(sys.stderr)
        return 2
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
