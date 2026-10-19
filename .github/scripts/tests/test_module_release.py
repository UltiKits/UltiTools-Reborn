"""Hermetic tests for the shared module publication adapter .github/scripts/module-release.sh.

Every test runs the real shell: a reference module publish.yml step (the wrapper contract every module's
existing workflow follows) calls the real adapter, and the adapter calls the real release_artifacts.py
helper. Every external command they can reach is a fake placed first on PATH in a temporary directory:

- ``gh`` keeps one repository's releases, release assets, tags and default-branch head in a local state
  directory and models only the calls the adapter is allowed to make. Anything else exits 96.
- ``npm`` models exactly one command, the pinned global install of @ultikits/cli 1.2.0.
- ``ultikits`` is the catalogue CLI. It refuses to run before that install and records each publish.
- ``mvn`` always fails: publication never builds.
- ``git`` forwards only read-only subcommands to the real git.
- ``python3`` runs the real interpreter in isolated mode with ``urllib.request.urlopen`` replaced by a
  fake internet that serves the npm registry document and the UltiCloud catalogue from the state
  directory. No test opens a network connection, installs a package or publishes anything.

The tests never import private release-control files; the dispatch inputs they use are sanitized fixtures.
Standard library only.
"""

import hashlib
import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
ADAPTER = SCRIPTS / "module-release.sh"
HELPER = SCRIPTS / "release_artifacts.py"
REAL_GIT = shutil.which("git")
BASH = shutil.which("bash")
MAINTAINER = "wisdommen"
REPO = "UltiKits/FixtureMod"
IDENTIFY = "fixturemod"
PREFIX = "FixtureMod"
VERSION = "2.0.0"
PRIOR = "1.0.0"
DRAFT_ID = "777001"
INSTALLATION_ID = "inst-7f3c9a1e2b4d4c55"
INTEGRITY = "sha512-SMZXRvp9MBSX1qpPDzC+UbvsYv9pGVZWWt/Eqjq02rs9xfg5dopzYZuKpRuGmCOww+8rF3suGsmp4SKSUqtgqA=="
REGISTRY_URL = "https://registry.npmjs.org/@ultikits%2fcli"
CDN = "https://cdn.fixture.invalid/"
INPUT_KEYS = ("artifact_sha256", "draft_release_id", "installation_id", "manifest_sha256",
              "package_metadata_sha256", "source_head", "version")

# One shared fixture. The private release-evidence checker's suite asserts the same two literals for the
# same objects, so the producer of the dispatch record and this runner-side helper prove one canonical
# serialization without either importing the other.
SHARED_INPUTS = {"source_head": "0123456789abcdef0123456789abcdef01234567", "version": "3.0.0",
                 "draft_release_id": "123456789", "manifest_sha256": "a" * 64, "artifact_sha256": "b" * 64,
                 "package_metadata_sha256": "c" * 64, "installation_id": "inst-shared-fixture-01"}
SHARED_INPUTS_SHA256 = "10fd2bfe8fa2d4d3fb8f41fd8cd6d03b1c65846e64b49771a7657c917227b8b6"
SHARED_METADATA_SHA256 = "41d7ae9045c1550039aedfed19f4093f0b1f3a49628723b2fa6bf8f88723943d"


def metadata_doc():
    """Exact-version registry metadata for @ultikits/cli 1.2.0 (sanitized fixture)."""
    return {"name": "@ultikits/cli", "version": "1.2.0", "dist": {"integrity": INTEGRITY},
            "_npmUser": {"name": "wisdomme"}, "scripts": {"build": "tsc"},
            "dependencies": {"commander": "^12.0.0"}}


# The reference wrapper: the step every module's existing publish.yml adds. The workflow never expands
# dispatch inputs in a script; the helper reads them from GITHUB_EVENT_PATH as data. The helper digest is a
# reviewed literal (substituted per test because the fixture copies the helper).
WRAPPER_YML = """name: Auto Release & Publish

on:
  push:
    branches: [master]
  workflow_dispatch:
    inputs:
      source_head: {required: true, type: string}
      version: {required: true, type: string}
      draft_release_id: {required: true, type: string}
      manifest_sha256: {required: true, type: string}
      artifact_sha256: {required: true, type: string}
      package_metadata_sha256: {required: true, type: string}
      installation_id: {required: true, type: string}

jobs:
  release-and-publish:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    concurrency:
      group: module-publish-${{ github.repository }}
      cancel-in-progress: false
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Publish retained draft
        env:
          GH_TOKEN: ${{ github.token }}
          ULTIKITS_TOKEN: ${{ secrets.ULTIKITS_TOKEN }}
          RELEASE_TOOLS: ${{ runner.temp }}/release-tools
        run: |
          set -euo pipefail
          if [ "$GITHUB_EVENT_NAME" != "workflow_dispatch" ]; then
            echo "Routine run: nothing is installed or published."
            exit 0
          fi
          python3 -I "$RELEASE_TOOLS/release_artifacts.py" dispatch-inputs verify \\
            --repository UltiKits/FixtureMod --default-branch master --maintainer wisdommen > /dev/null
          bash "$RELEASE_TOOLS/module-release.sh" --repo UltiKits/FixtureMod --identify-string fixturemod \\
            --default-branch master --maintainer wisdommen --jar-prefix FixtureMod \\
            --helper-sha256 @HELPER_SHA256@
"""


def wrapper_script(yml):
    """Return the run block of the 'Publish retained draft' step, dedented, exactly as Actions runs it."""
    lines = yml.splitlines()
    start = lines.index("      - name: Publish retained draft")
    run = next(i for i in range(start, len(lines)) if lines[i].strip() == "run: |")
    indent = len(lines[run]) - len(lines[run].lstrip())
    body = []
    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        body.append(line)
    return textwrap.dedent("\n".join(body)) + "\n"


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def load_helper():
    spec = importlib.util.spec_from_file_location("release_artifacts_under_test", str(HELPER))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def plugin_yml(version=VERSION, api="630", identify=IDENTIFY, main="com.example.fixture.FixtureMod"):
    lines = ["name: FixtureMod", "version: '%s'" % version, "main: %s" % main, "api-version: %s" % api]
    if identify is not None:
        lines.append("identify-string: %s" % identify)
    return ("\n".join(lines) + "\n").encode("utf-8")


def jar_bytes(plugin, main="com.example.fixture.FixtureMod", stamp=b""):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(zipfile.ZipInfo("plugin.yml", (2026, 10, 19, 0, 0, 0)), plugin)
        zf.writestr(zipfile.ZipInfo(main.replace(".", "/") + ".class", (2026, 10, 19, 0, 0, 0)),
                    b"\xca\xfe\xba\xbe fixture class bytes" + stamp)
    return buffer.getvalue()


POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.ultikits.plugins</groupId>
    <artifactId>FixtureMod</artifactId>
    <version>{version}</version>
</project>
"""

# ------------------------------------------------------------------------------------------ fakes

FAKE_GH = r'''#!{python} -I
import json, os, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "gh", "argv": args}) + "\n")
path = os.path.join(STATE, "github.json")
gh = json.load(open(path, encoding="utf-8"))
def save():
    json.dump(gh, open(path, "w", encoding="utf-8"), indent=2, sort_keys=True)
def http(code):
    sys.stderr.write("gh: HTTP %d\n" % code)
    sys.exit(1)
if not args or args[0] != "api":
    sys.exit(96)
method, headers, fields, endpoint, i = "GET", [], {}, None, 1
while i < len(args):
    a = args[i]
    if a in ("-X", "--method"):
        method = args[i + 1]; i += 2
    elif a in ("-H", "--header"):
        headers.append(args[i + 1]); i += 2
    elif a in ("-F", "--field"):
        k, v = args[i + 1].split("=", 1); fields[k] = v; i += 2
    elif a.startswith("-") or endpoint is not None:
        sys.exit(96)
    else:
        endpoint = a; i += 1
prefix = "repos/%s/" % gh["repo"]
if endpoint is None or not endpoint.startswith(prefix):
    http(404)
parts = endpoint[len(prefix):].split("/")
fail = gh.get("fail", {})
counts = gh.setdefault("counts", {})
def count(name):
    counts[name] = counts.get(name, 0) + 1
    save()
    return counts[name]
def release_view(rel):
    return {"id": int(rel_id), "draft": rel["draft"], "target_commitish": rel["target_commitish"],
            "tag_name": rel["tag_name"],
            "assets": [{"id": a["id"], "name": a["name"], "size": os.path.getsize(os.path.join(STATE, a["file"])),
                        "state": "uploaded"} for a in rel["assets"]]}
if method == "GET" and fail.get("get"):
    http(int(fail["get"]))
if method == "GET" and len(parts) == 2 and parts[0] == "releases" and parts[1].isdigit():
    rel_id = parts[1]
    n = count("release_reads")
    hook = gh.get("on_release_read", {}).get(str(n))
    if hook:
        gh["releases"][rel_id].update(hook)
        save()
    rel = gh["releases"].get(rel_id)
    if rel is None:
        http(404)
    print(json.dumps(release_view(rel)))
elif method == "GET" and len(parts) == 3 and parts[:2] == ["releases", "assets"]:
    if "Accept: application/octet-stream" not in headers:
        sys.exit(96)
    for rel in gh["releases"].values():
        for a in rel["assets"]:
            if str(a["id"]) == parts[2]:
                sys.stdout.buffer.write(open(os.path.join(STATE, a["file"]), "rb").read())
                sys.exit(0)
    http(404)
elif method == "GET" and parts[:3] == ["git", "matching-refs", "tags"] and len(parts) == 4:
    print(json.dumps([{"ref": "refs/tags/" + t, "object": {"sha": s, "type": "commit"}}
                      for t, s in sorted(gh["tags"].items()) if t.startswith(parts[3])]))
elif method == "GET" and parts[:3] == ["git", "ref", "heads"] and len(parts) >= 4:
    branch = "/".join(parts[3:])
    if branch != gh["default_branch"]:
        http(404)
    n = count("head_reads")
    seq = gh.get("head_sequence") or []
    sha = seq[min(n, len(seq)) - 1] if seq else gh["default_head"]
    print(json.dumps({"ref": "refs/heads/" + branch, "object": {"sha": sha, "type": "commit"}}))
elif method == "PATCH" and len(parts) == 2 and parts[0] == "releases" and fields == {"draft": "false"}:
    rel_id = parts[1]
    rel = gh["releases"].get(rel_id)
    if rel is None:
        http(404)
    if fail.get("undraft"):
        http(int(fail["undraft"]))
    rel["draft"] = False
    gh["tags"].setdefault(rel["tag_name"], rel["target_commitish"])
    save()
    print(json.dumps(release_view(rel)))
else:
    sys.stderr.write("fake gh: unmodelled call\n")
    sys.exit(96)
'''

FAKE_NPM = r'''#!{python} -I
import json, os, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "npm", "argv": args}) + "\n")
if args != ["install", "--global", "--ignore-scripts", "--no-audit", "--no-fund", "@ultikits/cli@1.2.0"]:
    sys.stderr.write("fake npm: only the pinned global install is modelled\n")
    sys.exit(96)
flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
if flags.get("npm_fail"):
    sys.exit(1)
marker = os.path.join(STATE, "cli-installed")
count = int(open(marker).read()) if os.path.exists(marker) else 0
open(marker, "w").write(str(count + 1))
'''

FAKE_ULTIKITS = r'''#!{python} -I
import json, os, shutil, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "ultikits", "argv": args, "cwd": os.getcwd(),
                         "token_present": bool(os.environ.get("ULTIKITS_TOKEN"))}) + "\n")
if not os.path.exists(os.path.join(STATE, "cli-installed")):
    sys.stderr.write("ultikits: command not found (the CLI was never installed)\n")
    sys.exit(127)
flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
if args == ["--version"]:
    print(flags.get("cli_version", "1.2.0"))
    sys.exit(0)
if args == ["publish", "--help"]:
    print(flags.get("cli_help", "Usage: ultikits publish [options] [jar-path]\n\nOptions:\n"
          "  --id <identifyString>       Module identify string\n"
          "  --name <name>               Module display name\n"
          "  --module-version <version>  Module version\n"
          "  --changelog <text>          Version changelog\n"
          "  -y, --yes                   Skip confirmation prompts\n"))
    sys.exit(0)
if len(args) < 6 or args[0] != "publish":
    sys.exit(96)
jar, rest = args[1], args[2:]
opts = {}
i = 0
while i < len(rest):
    if rest[i] == "--yes":
        opts["yes"] = True; i += 1
    elif rest[i] in ("--id", "--module-version", "--changelog"):
        opts[rest[i]] = rest[i + 1]; i += 2
    else:
        sys.exit(96)
if not os.environ.get("ULTIKITS_TOKEN") or "--id" not in opts or "--module-version" not in opts:
    sys.exit(1)
if flags.get("upload_fail"):
    sys.stderr.write("Upload failed: HTTP 502\n")
    sys.exit(1)
cpath = os.path.join(STATE, "catalogue.json")
cat = json.load(open(cpath, encoding="utf-8"))
name = "%s-%s.jar" % (opts["--id"], opts["--module-version"])
data = open(jar, "rb").read()
if flags.get("upload_corrupt"):
    data = data + b"corrupted in transit"
open(os.path.join(STATE, "catalogue-files", name), "wb").write(data)
cat["versions"].append({"version": opts["--module-version"], "level": 630, "file": name,
                        "identify": opts["--id"]})
json.dump(cat, open(cpath, "w", encoding="utf-8"), indent=2, sort_keys=True)
print("Published %s@%s" % (opts["--id"], opts["--module-version"]))
'''

FAKE_MVN = r'''#!{python} -I
import json, os, sys
with open(os.path.join(os.environ["FAKE_STATE_DIR"], "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "mvn", "argv": sys.argv[1:]}) + "\n")
sys.stderr.write("fake mvn: publication never builds\n")
sys.exit(97)
'''

FAKE_GIT = r'''#!{python} -I
import json, os, sys
args = sys.argv[1:]
with open(os.path.join(os.environ["FAKE_STATE_DIR"], "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "git", "argv": args}) + "\n")
rest = args[2:] if args[:1] == ["-C"] else args
if not rest or rest[0] not in ("rev-parse", "cat-file", "merge-base"):
    sys.stderr.write("guarded git: only read-only subcommands are allowed\n")
    sys.exit(95)
os.execv({git!r}, [{git!r}] + args)
'''

FAKE_PYTHON = r'''#!/bin/sh
exec {python} -I {shim} "$@"
'''

PYTHON_SHIM = r'''
import email.message, io, json, os, runpy, sys, urllib.error, urllib.parse, urllib.request
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
if args[:1] == ["-I"]:
    args = args[1:]
if not args or args[0].startswith("-"):
    sys.exit(94)

def log(entry):
    with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")

def vt(v):
    return tuple(int(p) for p in v.split("."))

def envelope(status, data=None, msg="Success"):
    body = {"code": str(status), "msg": msg}
    if data is not None:
        body["data"] = data
    return status, json.dumps(body).encode("utf-8")

def route(url):
    parts = urllib.parse.urlsplit(url)
    flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
    if url == "https://registry.npmjs.org/@ultikits%2fcli":
        if flags.get("registry_status"):
            return int(flags["registry_status"]), b"{}"
        return 200, open(os.path.join(STATE, "npm-packument.json"), "rb").read()
    if parts.scheme == "https" and parts.netloc == "cdn.fixture.invalid":
        path = os.path.join(STATE, "catalogue-files", parts.path.lstrip("/"))
        return (200, open(path, "rb").read()) if os.path.exists(path) else (404, b"")
    if parts.scheme != "https" or parts.netloc != "api.ultikits.com":
        raise urllib.error.URLError("fake internet: no route to %s" % parts.netloc)
    cat = json.load(open(os.path.join(STATE, "catalogue.json"), encoding="utf-8"))
    if flags.get("catalogue_status"):
        return envelope(int(flags["catalogue_status"]), msg="fake outage")
    query = urllib.parse.parse_qs(parts.query)
    seg = parts.path.strip("/").split("/")
    module = cat.get("module")
    if seg == ["plugin", "get"]:
        if module and query.get("identifyString") == [module["identify"]]:
            return envelope(200, {"id": module["id"], "identifyString": module["identify"]})
        return envelope(404, msg="Plugin not found")
    if not module or len(seg) < 3 or seg[0] != "plugin" or seg[1] != str(module["id"]):
        return envelope(404, msg="Plugin not found")
    versions = cat["versions"]
    level630 = query.get("apiLevel") == ["630"] and query.get("minApiLevel") == ["630"]
    def link(v):
        return "https://cdn.fixture.invalid/" + v["file"]
    if seg[2:] == ["versions-detail"]:
        return envelope(200, [{"version": v["version"], "downloadLink": link(v)} for v in versions])
    if seg[2:] == ["latest"]:
        pool = [v for v in versions if (v["level"] == 630) == level630]
        if not pool:
            return envelope(404, msg="No versions found for this plugin")
        return envelope(200, max(pool, key=lambda v: vt(v["version"]))["version"])
    if len(seg) == 4 and seg[3] == "download":
        match = [v for v in versions if v["version"] == seg[2] and (v["level"] == 630) == level630]
        return envelope(200, link(match[0])) if match else envelope(404, msg="Version not found")
    return envelope(404, msg="Not found")

class Response(io.BytesIO):
    def __init__(self, url, status, body):
        io.BytesIO.__init__(self, body)
        self.status, self.url, self.headers = status, url, email.message.Message()
    def geturl(self):
        return self.url
    def getcode(self):
        return self.status
    def __enter__(self):
        return self
    def __exit__(self, *exc):
        self.close()

def fake_urlopen(request, timeout=None, **kwargs):
    url = request.full_url if hasattr(request, "full_url") else request
    log({"tool": "https", "url": url})
    status, body = route(url)
    if status >= 400:
        raise urllib.error.HTTPError(url, status, "fake", email.message.Message(), io.BytesIO(body))
    return Response(url, status, body)

urllib.request.urlopen = fake_urlopen
sys.argv = [args[0]] + args[1:]
runpy.run_path(args[0], run_name="__main__")
'''


class PublishFixture(object):
    """One module repository at its accepted head, a retained candidate staged on a draft release, and the
    fake GitHub, npm registry and catalogue it publishes to."""

    def __init__(self, root, identify=IDENTIFY, version=VERSION):
        self.root = Path(root)
        self.identify, self.version = identify, version
        for name in ("bin", "state", "checkout", "retained", "runner", "home", "tools"):
            (self.root / name).mkdir(parents=True)
        self.bin, self.state, self.checkout = self.root / "bin", self.root / "state", self.root / "checkout"
        self.retained, self.runner, self.tools = self.root / "retained", self.runner_dir(), self.root / "tools"
        (self.state / "assets").mkdir()
        (self.state / "catalogue-files").mkdir()
        self.install_fakes()
        self.make_checkout()
        self.make_candidate()
        self.stage_github()
        self.stage_catalogue()
        self.write_json(self.state / "npm-packument.json",
                        {"name": "@ultikits/cli", "dist-tags": {"latest": "1.2.0"},
                         "versions": {"1.2.0": metadata_doc()}})
        self.flags = {}
        self.save_flags()
        # The wrapper fetches the adapter and helper at a reviewed framework commit; the fixture copies the
        # files under test. A missing file is copied as missing so the run itself fails, not the fixture.
        for source in (ADAPTER, HELPER):
            if source.exists():
                shutil.copy2(str(source), str(self.tools / source.name))
        self.wrapper_yml = WRAPPER_YML.replace("@HELPER_SHA256@", self.helper_sha256())
        self.inputs = self.valid_inputs()
        self.write_event(self.inputs)

    def runner_dir(self):
        return self.root / "runner"

    @staticmethod
    def write_json(path, obj):
        Path(path).write_text(json.dumps(obj, indent=2, sort_keys=True), encoding="utf-8")

    def helper_sha256(self):
        return sha256_bytes((self.tools / "release_artifacts.py").read_bytes()) \
            if (self.tools / "release_artifacts.py").exists() else sha256_bytes(HELPER.read_bytes())

    def install_fakes(self):
        shim = self.root / "python_shim.py"
        shim.write_text(PYTHON_SHIM, encoding="utf-8")
        scripts = {"gh": FAKE_GH, "npm": FAKE_NPM, "ultikits": FAKE_ULTIKITS, "mvn": FAKE_MVN,
                   "git": FAKE_GIT.replace("{git!r}", repr(REAL_GIT)),
                   "python3": FAKE_PYTHON.replace("{shim}", str(shim))}
        for name, text in scripts.items():
            path = self.bin / name
            path.write_text(text.replace("{python}", sys.executable), encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def git(self, *args):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.root / "home"),
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "f@example.invalid",
               "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "f@example.invalid"}
        proc = subprocess.run([REAL_GIT, "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                               "-c", "init.defaultBranch=master"] + list(args),
                              cwd=str(self.checkout), env=env, capture_output=True, text=True, timeout=60)
        if proc.returncode != 0:
            raise RuntimeError("fixture git failed: %s %s" % (args, proc.stderr))
        return proc.stdout.strip()

    def make_checkout(self):
        self.git("init", "-q")
        (self.checkout / "pom.xml").write_text(POM_TEMPLATE.format(version=self.version), encoding="utf-8")
        (self.checkout / "ultikits.json").write_text(json.dumps({"identifyString": self.identify,
                                                                 "name": "FixtureMod"}), encoding="utf-8")
        self.git("add", "pom.xml", "ultikits.json")
        self.git("commit", "-q", "-m", "accepted source")
        self.git("remote", "add", "origin", "https://github.com/%s.git" % REPO)
        self.head = self.git("rev-parse", "HEAD")
        self.tree = self.git("rev-parse", "HEAD^{tree}")

    @property
    def jar_name(self):
        return "%s-%s.jar" % (PREFIX, self.version)

    @property
    def manifest_name(self):
        return "%s-%s-release-manifest.json" % (PREFIX, self.version)

    def make_candidate(self):
        self.jar = jar_bytes(plugin_yml(version=self.version, identify=self.identify))
        (self.retained / self.jar_name).write_bytes(self.jar)
        self.manifest = {
            "schema": "phase21-candidate/1", "evidence_state": "observed", "repo": REPO, "kind": "module",
            "label": "final-candidate", "formal_acceptance": False, "release_authorized": False,
            "source_head": self.head, "product_tree": self.tree, "version": self.version,
            "identify_string": self.identify, "prior_served_version": PRIOR, "plugin_api_version": "630",
            "artifacts": [{"filename": self.jar_name, "bytes": len(self.jar), "sha256": sha256_bytes(self.jar),
                           "role": "primary"}],
        }
        self.manifest_bytes = json.dumps(self.manifest, indent=2, sort_keys=True).encode("utf-8")
        (self.retained / self.manifest_name).write_bytes(self.manifest_bytes)

    def stage_github(self):
        (self.state / "assets" / "11").write_bytes(self.jar)
        (self.state / "assets" / "12").write_bytes(self.manifest_bytes)
        self.write_json(self.state / "github.json", {
            "repo": REPO, "default_branch": "master", "default_head": self.head,
            "tags": {"v" + PRIOR: "f" * 40},
            "releases": {DRAFT_ID: {"draft": True, "target_commitish": self.head, "tag_name": "v" + self.version,
                                    "assets": [{"id": 11, "name": self.jar_name, "file": "assets/11"},
                                               {"id": 12, "name": self.manifest_name, "file": "assets/12"}]}},
        })

    def stage_catalogue(self):
        self.prior_jar = jar_bytes(plugin_yml(version=PRIOR, api="620"))
        (self.state / "catalogue-files" / ("%s-%s.jar" % (self.identify, PRIOR))).write_bytes(self.prior_jar)
        self.write_json(self.state / "catalogue.json", {
            "module": {"id": 17, "identify": self.identify},
            "versions": [{"version": PRIOR, "level": 620, "file": "%s-%s.jar" % (self.identify, PRIOR),
                          "identify": self.identify}]})

    def save_flags(self):
        self.write_json(self.state / "flags.json", self.flags)

    def github(self):
        return json.loads((self.state / "github.json").read_text(encoding="utf-8"))

    def update_github(self, mutate):
        doc = self.github()
        mutate(doc)
        self.write_json(self.state / "github.json", doc)

    def catalogue(self):
        return json.loads((self.state / "catalogue.json").read_text(encoding="utf-8"))

    def update_catalogue(self, mutate):
        doc = self.catalogue()
        mutate(doc)
        self.write_json(self.state / "catalogue.json", doc)

    def valid_inputs(self):
        return {"source_head": self.head, "version": self.version, "draft_release_id": DRAFT_ID,
                "manifest_sha256": sha256_bytes(self.manifest_bytes), "artifact_sha256": sha256_bytes(self.jar),
                "package_metadata_sha256": SHARED_METADATA_SHA256, "installation_id": INSTALLATION_ID}

    def write_event(self, inputs, ref="refs/heads/master", raw=None):
        path = self.runner / "event.json"
        if raw is not None:
            path.write_bytes(raw)
        else:
            path.write_text(json.dumps({"inputs": inputs, "ref": ref, "repository": {"full_name": REPO},
                                        "sender": {"login": MAINTAINER},
                                        "workflow": ".github/workflows/publish.yml"}), encoding="utf-8")
        return path

    def env(self, **overrides):
        env = {
            "PATH": "%s:/usr/bin:/bin" % self.bin, "HOME": str(self.root / "home"), "LANG": "C.UTF-8",
            "FAKE_STATE_DIR": str(self.state),
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(self.runner / "event.json"),
            "GITHUB_REF": "refs/heads/master", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_RUN_ID": "9001",
            "GITHUB_REPOSITORY": REPO, "GITHUB_TRIGGERING_ACTOR": MAINTAINER, "GITHUB_ACTOR": MAINTAINER,
            "GITHUB_SHA": self.head, "GITHUB_WORKSPACE": str(self.checkout), "RUNNER_TEMP": str(self.runner),
            "GITHUB_STEP_SUMMARY": str(self.runner / "summary.md"),
            "GH_TOKEN": "fixture-gh-token-0000", "ULTIKITS_TOKEN": "fixture-catalogue-token-0000",
            "RELEASE_TOOLS": str(self.tools),
        }
        for key, value in overrides.items():
            if value is None:
                env.pop(key, None)
            else:
                env[key] = value
        return env

    def run_wrapper(self, **env):
        proc = subprocess.run([BASH, "-c", wrapper_script(self.wrapper_yml)], cwd=str(self.checkout),
                              env=self.env(**env), capture_output=True, text=True, timeout=180)
        return proc.returncode, proc.stdout, proc.stderr

    def run_adapter(self, *extra, args=None, **env):
        argv = args if args is not None else [
            "--repo", REPO, "--identify-string", self.identify, "--default-branch", "master",
            "--maintainer", MAINTAINER, "--jar-prefix", PREFIX, "--helper-sha256", self.helper_sha256()]
        proc = subprocess.run([BASH, str(self.tools / "module-release.sh")] + argv + list(extra),
                              cwd=str(self.checkout), env=self.env(**env), capture_output=True, text=True,
                              timeout=180)
        return proc.returncode, proc.stdout, proc.stderr

    def calls(self, tool=None):
        path = self.state / "calls.jsonl"
        entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] \
            if path.exists() else []
        return [e for e in entries if tool is None or e["tool"] == tool]

    def install_calls(self):
        return self.calls("npm")

    def publish_calls(self):
        return [c for c in self.calls("ultikits") if c["argv"][:1] == ["publish"] and "--help" not in c["argv"]]

    def github_writes(self):
        return [c for c in self.calls("gh") if any(m in c["argv"] for m in ("PATCH", "POST", "DELETE", "PUT"))]

    def receipt(self):
        return json.loads((self.runner / "module-release-receipt.json").read_text(encoding="utf-8"))


class FixtureBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="p21-module-release-")
        self.f = PublishFixture(Path(self.tmp) / "fx")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def assert_nothing_installed_or_written(self, outcome=None):
        detail = "" if outcome is None else "\nstdout:\n%s\nstderr:\n%s" % outcome[1:]
        self.assertEqual(self.f.install_calls(), [], "no CLI installation may happen" + detail)
        self.assertEqual(self.f.calls("ultikits"), [], "the CLI may not run" + detail)
        self.assertEqual(self.f.github_writes(), [], "no GitHub write may happen" + detail)
        self.assertEqual(self.f.calls("mvn"), [], "publication never builds")
        self.assertTrue(self.f.github()["releases"][DRAFT_ID]["draft"], "the draft stays unpublished")
        self.assertEqual([v["version"] for v in self.f.catalogue()["versions"]], [PRIOR])

    def assert_refused(self, outcome, code=None):
        status, out, err = outcome
        self.assertNotEqual(status, 0, msg=out + err)
        if code is not None:
            self.assertIn(code, err, msg=out + err)
        self.assert_nothing_installed_or_written(outcome)


class SharedDigestTests(unittest.TestCase):
    """The runner-side helper serializes exactly like the private producer of the dispatch record."""

    def test_inputs_digest_matches_the_shared_fixture(self):
        self.assertEqual(load_helper().dispatch_inputs_sha256(dict(SHARED_INPUTS)), SHARED_INPUTS_SHA256)

    def test_metadata_digest_matches_the_shared_fixture(self):
        self.assertEqual(load_helper().package_metadata_sha256(metadata_doc()), SHARED_METADATA_SHA256)

    def test_digest_is_independent_of_key_order(self):
        helper = load_helper()
        reordered = dict(reversed(list(SHARED_INPUTS.items())))
        self.assertEqual(helper.dispatch_inputs_sha256(reordered), SHARED_INPUTS_SHA256)


class TracerPublishTests(FixtureBase):
    """Valid dispatch -> wrapper -> adapter -> helper -> fresh metadata -> exact retained JAR -> readbacks."""

    def test_fakes_shadow_every_real_tool(self):
        path = self.f.env()["PATH"]
        for tool in ("gh", "npm", "ultikits", "mvn", "git", "python3"):
            self.assertEqual(shutil.which(tool, path=path), str(self.f.bin / tool))

    def test_valid_dispatch_publishes_the_exact_retained_jar_to_both_sides(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        installs = self.f.install_calls()
        self.assertEqual(len(installs), 1, "exactly one pinned installation")
        self.assertEqual(installs[0]["argv"][-1], "@ultikits/cli@1.2.0")
        publishes = self.f.publish_calls()
        self.assertEqual(len(publishes), 1)
        argv = publishes[0]["argv"]
        jar_path = argv[1]
        self.assertEqual(Path(jar_path).name, self.f.jar_name, "positional exact basename, no wildcard")
        self.assertEqual(argv[2:], ["--id", IDENTIFY, "--module-version", VERSION, "--yes"])
        self.assertTrue(publishes[0]["token_present"])
        served = self.f.catalogue()["versions"]
        self.assertEqual([v["version"] for v in served], [PRIOR, VERSION])
        self.assertEqual((self.f.state / "catalogue-files" / ("%s-%s.jar" % (IDENTIFY, VERSION))).read_bytes(),
                         self.f.jar, "the catalogue serves the accepted bytes")
        release = self.f.github()["releases"][DRAFT_ID]
        self.assertFalse(release["draft"])
        self.assertEqual(self.f.github()["tags"]["v" + VERSION], self.f.head)
        self.assertEqual(len(self.f.github_writes()), 1, "one undraft write; assets are never re-uploaded")
        self.assertEqual(self.f.calls("mvn"), [])

    def test_runner_prints_and_records_the_canonical_inputs_digest_and_run_identity(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        digest = sha256_bytes(canonical(self.f.inputs).encode("ascii"))
        self.assertIn("INPUTS_SHA256=%s" % digest, out)
        receipt = self.f.receipt()
        self.assertEqual(receipt["inputs_sha256"], digest)
        self.assertEqual(receipt["run_id"], "9001")
        self.assertEqual(receipt["run_attempt"], "1")
        self.assertEqual(receipt["event"], "workflow_dispatch")
        self.assertEqual(receipt["triggering_actor"], MAINTAINER)
        self.assertEqual(receipt["installation_id"], INSTALLATION_ID)
        self.assertEqual(receipt["installed_package"], "@ultikits/cli@1.2.0")
        self.assertEqual(receipt["package_metadata_sha256"], SHARED_METADATA_SHA256)
        self.assertEqual(receipt["result"], "complete")
        self.assertEqual(receipt["release_outcome"], "published")
        self.assertEqual(receipt["catalogue_outcome"], "uploaded")
        self.assertEqual(receipt["level630_after"], VERSION)
        self.assertEqual(receipt["no_level_before"], PRIOR)
        self.assertEqual(receipt["no_level_after"], PRIOR)
        summary = (self.f.runner / "summary.md").read_text(encoding="utf-8")
        self.assertIn(digest, summary)
        self.assertIn("9001", summary)

    def test_logs_receipt_and_summary_hold_no_local_path_or_token(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        texts = [out, err, (self.f.runner / "module-release-receipt.json").read_text(encoding="utf-8"),
                 (self.f.runner / "summary.md").read_text(encoding="utf-8")]
        for text in texts:
            self.assertNotIn(self.tmp, text)
            self.assertNotIn("fixture-catalogue-token-0000", text)
            self.assertNotIn("fixture-gh-token-0000", text)

    def test_metadata_is_fetched_fresh_immediately_before_the_single_install(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        sequence = [c for c in self.f.calls() if c["tool"] in ("npm", "https", "ultikits", "gh")]
        registry = [i for i, c in enumerate(sequence) if c.get("url") == REGISTRY_URL]
        install = [i for i, c in enumerate(sequence) if c["tool"] == "npm"]
        self.assertTrue(registry and install)
        self.assertEqual(registry[-1] + 1, install[0], "the metadata check is the call right before install")

    def test_routine_push_through_the_wrapper_installs_and_publishes_nothing(self):
        status, out, err = self.f.run_wrapper(GITHUB_EVENT_NAME="push")
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(self.f.calls(), [], "a routine push makes no external call at all")


class DispatchRefusalTests(FixtureBase):
    """Every input, dispatcher, event, ref and attempt refusal happens before any install or write."""

    def test_missing_input_is_refused(self):
        inputs = dict(self.f.inputs)
        del inputs["installation_id"]
        self.f.write_event(inputs)
        self.assert_refused(self.f.run_wrapper(), "inputs_missing_key")

    def test_mismatched_artifact_digest_is_refused(self):
        self.f.write_event(dict(self.f.inputs, artifact_sha256="0" * 64))
        self.assert_refused(self.f.run_wrapper(), "artifact_sha256_mismatch")

    def test_mismatched_manifest_digest_is_refused(self):
        self.f.write_event(dict(self.f.inputs, manifest_sha256="0" * 64))
        self.assert_refused(self.f.run_wrapper(), "manifest_sha256_mismatch")

    def test_staged_asset_whose_bytes_differ_is_refused(self):
        data = bytearray(self.f.jar)
        data[len(data) // 2] ^= 0x01
        (self.f.state / "assets" / "11").write_bytes(bytes(data))
        self.assert_refused(self.f.run_wrapper(), "artifact_sha256_mismatch")

    def test_wrongly_targeted_draft_is_refused(self):
        self.f.update_github(lambda g: g["releases"][DRAFT_ID].update(target_commitish="e" * 40))
        self.assert_refused(self.f.run_wrapper(), "release_target_mismatch")

    def test_published_release_whose_tag_resolves_elsewhere_is_refused(self):
        def mutate(g):
            g["releases"][DRAFT_ID]["draft"] = False
            g["tags"]["v" + VERSION] = "e" * 40
        self.f.update_github(mutate)
        status, out, err = self.f.run_wrapper()
        self.assertNotEqual(status, 0, msg=out + err)
        self.assertIn("tag_elsewhere", err)
        self.assertEqual(self.f.install_calls(), [])
        self.assertEqual(self.f.calls("ultikits"), [])
        self.assertEqual(self.f.github_writes(), [])

    def test_another_triggering_account_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_TRIGGERING_ACTOR="someone-else"), "actor_not_maintainer")

    def test_missing_triggering_account_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_TRIGGERING_ACTOR=None), "actor_not_maintainer")

    def test_another_event_is_refused_by_the_adapter_itself(self):
        self.assert_refused(self.f.run_adapter(GITHUB_EVENT_NAME="push"), "event_not_dispatch")

    def test_a_non_default_ref_is_refused(self):
        self.f.write_event(self.f.inputs, ref="refs/heads/feature")
        self.assert_refused(self.f.run_wrapper(GITHUB_REF="refs/heads/feature"), "ref_not_default")

    def test_rerun_attempt_two_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_RUN_ATTEMPT="2"), "rerun_refused")

    def test_another_repository_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_REPOSITORY="UltiKits/Other"), "repository_mismatch")

    def test_identity_is_never_derived_from_the_repository_name(self):
        self.assert_refused(self.f.run_adapter(args=[
            "--repo", REPO, "--identify-string", "fixturemod-lowercased-repo", "--default-branch", "master",
            "--maintainer", MAINTAINER, "--jar-prefix", PREFIX, "--helper-sha256", self.f.helper_sha256()]),
            "identify_string_mismatch")


class DottedIdentityTests(unittest.TestCase):
    """UltiKits' identity is ultikits.kits; it is passed and compared exactly, never derived."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="p21-module-release-id-")
        self.f = PublishFixture(Path(self.tmp) / "fx", identify="ultikits.kits")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_exact_dotted_identity_publishes_unchanged(self):
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        argv = self.f.publish_calls()[0]["argv"]
        self.assertEqual(argv[argv.index("--id") + 1], "ultikits.kits")
        self.assertEqual(self.f.catalogue()["versions"][-1]["identify"], "ultikits.kits")


if __name__ == "__main__":
    unittest.main()
