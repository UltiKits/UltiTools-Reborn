"""Hermetic end-to-end tests for the retained release handoff in .github/scripts/release.sh.

The tests run the real shell entrypoint. Every external command it can reach is replaced by
an executable placed first on PATH inside a temporary directory:

- ``mvn`` is a fake that logs each call and writes real ZIP files into ``target/``. Setting
  FAKE_MVN_FORBID=1 makes any Maven call exit 99, so a test can prove that a mode never builds.
- ``gh`` is a fake that logs each call and keeps releases and asset bytes in a local state
  directory. It refuses ``--clobber`` and any call it does not model.
- ``git`` is a guarded shim. It logs each call, refuses ``push`` and forwards everything else
  to the real git. The only remote is a local bare repository: GIT_ALLOW_PROTOCOL=file makes
  git refuse every network transport, so a mistake fails the test instead of reaching a server.

No test contacts a network service, creates a real tag or release, or publishes anything.
Standard library only.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent.parent
REAL_MVN = shutil.which("mvn")
RELEASE_SH = SCRIPTS / "release.sh"
HELPER = SCRIPTS / "release_artifacts.py"
REAL_GIT = shutil.which("git")
BASH = shutil.which("bash")
REPO = "UltiKits/UltiTools-Reborn"
REPO_URL = "https://github.com/UltiKits/UltiTools-Reborn.git"
VERSION = "6.3.0"
PRIOR = "6.2.5"
TAG = "v" + VERSION
PRIMARY = "UltiTools-API-%s.jar" % VERSION
POM = "UltiTools-API-%s.pom" % VERSION
SOURCES = "UltiTools-API-%s-sources.jar" % VERSION
JAVADOC = "UltiTools-API-%s-javadoc.jar" % VERSION
# The POM for GitHub Packages, whose coordinate is the lower-case com.ultikits:ultitools-api.
GHP_POM = "UltiTools-API-%s-github-packages.pom" % VERSION
MANIFEST = "UltiTools-API-%s-release-manifest.json" % VERSION
PAYLOADS = (JAVADOC, SOURCES, PRIMARY, POM, GHP_POM)

POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <artifactId>UltiTools-API</artifactId>
    <groupId>com.ultikits</groupId>
    <version>{version}</version>
    <packaging>jar</packaging>
    <dependencies><dependency><groupId>fixture</groupId><artifactId>shaded</artifactId>
        <version>1</version><scope>compile</scope></dependency></dependencies>
</project>
"""

PLUGIN_YML = """name: UltiTools
version: '${project.version}'
main: com.ultikits.ultitools.UltiTools
api-version: '1.19'
"""

FAKE_MVN = r'''#!{python} -I
import json, os, re, shutil, sys, zipfile
from pathlib import Path
argv = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "mvn", "argv": argv}) + "\n")
if os.environ.get("FAKE_MVN_FORBID") == "1":
    sys.stderr.write("fake mvn: a Maven build is forbidden in this mode\n")
    sys.exit(99)
root = Path(os.getcwd())
version = re.search(r"<version>([^<]+)</version>", (root / "pom.xml").read_text()).group(1)
if "help:evaluate" in argv:
    print(version)
    sys.exit(0)
if "verify" in argv:
    variant = os.environ.get("FAKE_MVN_VARIANT", "normal")
    stamp = os.environ.get("FAKE_MVN_STAMP", "build-1").encode()
    target = root / "target"
    shutil.rmtree(str(target), ignore_errors=True)
    target.mkdir()

    def jar(name, entries):
        with zipfile.ZipFile(str(target / name), "w", zipfile.ZIP_DEFLATED) as zf:
            for entry, data in entries:
                zf.writestr(zipfile.ZipInfo(entry, date_time=(2026, 10, 19, 0, 0, 0)), data)

    plugin_version = "9.9.9" if variant == "wrong-plugin-version" else version
    main = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"),
            ("plugin.yml", ("name: UltiTools\nversion: '%s'\nmain: com.ultikits.ultitools.UltiTools\n"
                            "api-version: '1.19'\n" % plugin_version).encode()),
            ("com/ultikits/ultitools/UltiTools.class", b"\xca\xfe\xba\xbe" + stamp),
            ("com/ultikits/ultitools/Shaded.class", b"\xca\xfe\xba\xbe" + stamp)]
    if variant == "no-main-class":
        main = [e for e in main if e[0] != "com/ultikits/ultitools/UltiTools.class"]
    if variant == "no-plugin-yml":
        main = [e for e in main if e[0] != "plugin.yml"]
    jar("UltiTools-API-%s.jar" % version, main)
    jar("original-UltiTools-API-%s.jar" % version, [("com/ultikits/ultitools/UltiTools.class", b"\xca\xfe")])
    sources = [("com/ultikits/ultitools/UltiTools.java", b"package com.ultikits.ultitools;\nclass UltiTools {}\n")]
    if variant == "sources-without-java":
        sources = [("com/ultikits/ultitools/UltiTools.class", b"\xca\xfe\xba\xbe")]
    jar("UltiTools-API-%s-sources.jar" % version, sources)
    jar("UltiTools-API-%s-javadoc.jar" % version,
        [("index.html", b"<html></html>"), ("com/ultikits/ultitools/UltiTools.html", b"<html></html>")])
    # The publication POM the real flatten-maven-plugin made from this tree's pom.xml (see real_flattened_pom).
    shutil.copyfile(os.environ["FAKE_FLATTENED_POM"], str(root / ".flattened-pom.xml"))
    if variant == "untracked-build-file":
        build = root / "build"
        build.mkdir(exist_ok=True)
        (build / "test-output.json").write_text("{}\n")
    if variant == "test-store-output":
        store = root / os.environ["FAKE_TEST_STORE"]
        store.mkdir(parents=True, exist_ok=True)
        (store / "test-output.json").write_text("{}\n")
    if variant == "dirty-tree":
        with open(str(root / "pom.xml"), "a") as fh:
            fh.write("<!-- rewritten by the build -->\n")
    sys.exit(0)
sys.stderr.write("fake mvn: unexpected invocation %r\n" % argv)
sys.exit(96)
'''

FAKE_GH = r'''#!{python} -I
import json, os, shutil, subprocess, sys
from pathlib import Path
argv = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "gh", "argv": argv}) + "\n")
state_dir = Path(os.environ["FAKE_GH_STATE"])
state_file = state_dir / "state.json"
state = json.loads(state_file.read_text()) if state_file.exists() else {"releases": {}}


def save():
    state_file.write_text(json.dumps(state, indent=2, sort_keys=True))


def fail(message, code=1):
    sys.stderr.write("fake gh: %s\n" % message)
    sys.exit(code)


def origin_has_tag(tag):
    return subprocess.run([os.environ["REAL_GIT"], "--git-dir", os.environ["FAKE_ORIGIN"], "rev-parse", "-q",
                           "--verify", "refs/tags/" + tag], stdout=subprocess.DEVNULL).returncode == 0


def tag_origin(tag, target):
    if not origin_has_tag(tag):
        subprocess.run([os.environ["REAL_GIT"], "--git-dir", os.environ["FAKE_ORIGIN"], "tag", tag, target],
                       check=True)


def parse(rest, with_value, booleans):
    values, positional, i = {}, [], 0
    while i < len(rest):
        arg = rest[i]
        if arg in with_value:
            values[arg] = rest[i + 1]
            i += 2
        elif arg in booleans:
            values[arg] = True
            i += 1
        elif arg.startswith("--") and "=" in arg:
            key, value = arg.split("=", 1)
            values[key] = value
            i += 1
        elif arg.startswith("-"):
            fail("unexpected flag %s" % arg, 96)
        else:
            positional.append(arg)
            i += 1
    return values, positional


if argv[:2] == ["auth", "status"]:
    sys.exit(0)
if len(argv) < 3 or argv[0] != "release":
    fail("unexpected gh call %r" % argv, 96)
verb, tag, rest = argv[1], argv[2], argv[3:]
if "--clobber" in rest:
    fail("--clobber is forbidden", 98)
releases = state["releases"]
rel = releases.get(tag)
assets = state_dir / "assets" / tag
move_to = os.environ.get("FAKE_GH_VIEW_MOVE_MAIN_TO")
marker = state_dir / "moved-main"
if verb == "view" and move_to and not marker.exists():
    # Another actor moves origin/main while this run is between its checks and its write.
    marker.write_text(move_to)
    subprocess.run([os.environ["REAL_GIT"], "--git-dir", os.environ["FAKE_ORIGIN"], "update-ref", "refs/heads/main",
                    move_to], check=True)


def store(path, name):
    shutil.copyfile(path, str(assets / name))
    if os.environ.get("FAKE_GH_CORRUPT_UPLOAD") == name:
        with open(str(assets / name), "r+b") as handle:
            first = handle.read(1)
            handle.seek(0)
            handle.write(bytes([first[0] ^ 0xFF]))


if verb == "view":
    if rel is None:
        fail("release not found")
    values, _ = parse(rest, ("--json", "--jq"), ())
    if "--json" in values:
        print(json.dumps({"tagName": tag, "targetCommitish": rel["target"], "isDraft": rel["draft"],
                          "isPrerelease": rel["prerelease"],
                          "assets": [{"name": n, "size": (assets / n).stat().st_size} for n in rel["assets"]]}))
    else:
        print("title:\t" + rel["title"])
    sys.exit(0)
if verb == "create":
    if rel is not None:
        fail("a release with the same tag name already exists")
    values, files = parse(rest, ("--target", "--title", "--notes", "--notes-file"), ("--prerelease", "--draft"))
    if "--target" not in values:
        fail("create without --target", 96)
    rel = {"target": values["--target"], "draft": True, "prerelease": bool(values.get("--prerelease")),
           "title": values.get("--title", ""), "notes": values.get("--notes", ""), "assets": []}
    releases[tag] = rel
    save()
    assets.mkdir(parents=True, exist_ok=True)
    limit = os.environ.get("FAKE_GH_FAIL_AFTER_ASSETS")
    for index, path in enumerate(files):
        if limit is not None and index >= int(limit):
            fail("simulated interruption after %s assets" % limit)
        store(path, Path(path).name)
        rel["assets"].append(Path(path).name)
        save()
    if not values.get("--draft"):
        rel["draft"] = False
        save()
        tag_origin(tag, rel["target"])
    print("https://github.com/UltiKits/UltiTools-Reborn/releases/tag/" + tag)
    sys.exit(0)
if verb == "upload":
    if rel is None:
        fail("release not found")
    _, files = parse(rest, (), ())
    for path in files:
        name = Path(path).name
        if name in rel["assets"]:
            fail("asset %s already exists" % name)
        store(path, name)
        rel["assets"].append(name)
        save()
    sys.exit(0)
if verb == "download":
    if rel is None:
        fail("release not found")
    values, _ = parse(rest, ("--dir",), ())
    dest = Path(values.get("--dir", "."))
    dest.mkdir(parents=True, exist_ok=True)
    for name in rel["assets"]:
        if (dest / name).exists():
            fail("%s already exists locally" % name)
        shutil.copyfile(str(assets / name), str(dest / name))
    sys.exit(0)
if verb == "edit":
    if rel is None:
        fail("release not found")
    values, _ = parse(rest, (), ())
    if values == {"--draft": "false"}:
        rel["draft"] = False
        save()
        tag_origin(tag, rel["target"])
        sys.exit(0)
fail("unexpected gh call %r" % argv, 96)
'''

FAKE_GIT = r'''#!{python} -I
import json, os, sys
argv = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "git", "argv": argv}) + "\n")
if "push" in argv:
    sys.stderr.write("fake git: push is refused in tests\n")
    sys.exit(97)
os.execv(os.environ["REAL_GIT"], ["git"] + argv)
'''


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    return sha256_bytes(Path(path).read_bytes())


_REAL_FLATTENED = {}


def real_flattened_pom():
    """Run the real flatten-maven-plugin, as this tree's pom.xml configures it, offline on that pom.xml at the
    release version; return the flattened bytes. Cached per process. A run that cannot flatten fails."""
    if "pom" not in _REAL_FLATTENED:
        raw = (REPO_ROOT / "pom.xml").read_bytes()
        pom, count = re.subn(rb"<version>[^<]+</version>", ("<version>%s</version>" % VERSION).encode(), raw, count=1)
        if count != 1:
            raise AssertionError("pom.xml has no project version to pin")
        work = Path(tempfile.mkdtemp(prefix="real-flatten-"))
        try:
            (work / "pom.xml").write_bytes(pom)
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(Path.home()), "LANG": "C.UTF-8"}
            if os.environ.get("JAVA_HOME"):
                env["JAVA_HOME"] = os.environ["JAVA_HOME"]
            repository = os.environ.get("MAVEN_REPO_LOCAL")
            repo_args = ["-Dmaven.repo.local=" + repository] if repository else []
            proc = subprocess.run([REAL_MVN or "mvn", "-o", "-B", "-q"] + repo_args +
                                  ["org.codehaus.mojo:flatten-maven-plugin:flatten"], cwd=str(work), env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
            flattened = work / ".flattened-pom.xml"
            if proc.returncode != 0 or not flattened.is_file():
                raise AssertionError("BLOCKED: the real flatten-maven-plugin did not run offline:\n"
                                     + proc.stdout.decode("utf-8", "replace")[-2000:])
            _REAL_FLATTENED["pom"] = flattened.read_bytes()
        finally:
            shutil.rmtree(str(work), ignore_errors=True)
    return _REAL_FLATTENED["pom"]


class ReleaseFixture(object):
    """A temporary framework checkout, its local bare origin and the fake tools."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="release-handoff-"))
        self.bin = self.root / "bin"
        self.home = self.root / "home"
        self.origin = self.root / "origin.git"
        self.work = self.root / "work"
        self.gh_state = self.root / "gh-state"
        self.log = self.root / "calls.jsonl"
        self.receipts = self.root / "receipts"
        for path in (self.bin, self.home, self.gh_state, self.receipts):
            path.mkdir(parents=True)
        self.flattened = self.root / "real-flattened-pom.xml"
        self.flattened.write_bytes(real_flattened_pom())
        self.log.write_text("")
        for name, source in (("mvn", FAKE_MVN), ("gh", FAKE_GH), ("git", FAKE_GIT)):
            exe = self.bin / name
            exe.write_text(source.replace("{python}", sys.executable))
            exe.chmod(0o755)
        (self.home / ".gitconfig").write_text("[user]\n\tname = Release Test\n\temail = release@test.invalid\n"
                                              "[init]\n\tdefaultBranch = main\n")
        self.env = {
            "PATH": os.pathsep.join([str(self.bin), str(Path(sys.executable).parent), "/usr/bin", "/bin"]),
            "HOME": str(self.home),
            "LANG": "C.UTF-8",
            "NO_COLOR": "1",
            "FAKE_LOG": str(self.log),
            "FAKE_GH_STATE": str(self.gh_state),
            "FAKE_ORIGIN": str(self.origin),
            "FAKE_FLATTENED_POM": str(self.flattened),
            "REAL_GIT": REAL_GIT,
            "GIT_ALLOW_PROTOCOL": "file",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": str(self.home / ".gitconfig"),
            "GIT_TERMINAL_PROMPT": "0",
            "GH_CONFIG_DIR": str(self.home / "gh"),
            "GH_TOKEN": "",
            "GH_PROMPT_DISABLED": "1",
        }
        self.git_raw("init", "--bare", "-q", "-b", "main", str(self.origin), cwd=self.root)
        self.git_raw("init", "-q", "-b", "main", str(self.work), cwd=self.root)
        self.git_raw("remote", "add", "origin", REPO_URL)
        self.git_raw("config", "url.file://%s.insteadOf" % self.origin, REPO_URL)
        (self.work / ".gitignore").write_text("target/\n.flattened-pom.xml\n")
        (self.work / "src/main/resources").mkdir(parents=True)
        (self.work / "src/main/resources/plugin.yml").write_text(PLUGIN_YML)
        self.commit_pom(PRIOR, "release " + PRIOR)
        self.git_raw("tag", "v" + PRIOR)
        self.commit_pom(VERSION, "prepare " + VERSION)
        self.git_raw("push", "-q", "origin", "main", "--tags")
        self.git_raw("fetch", "-q", "origin")
        self.git_raw("branch", "-q", "--set-upstream-to=origin/main", "main")

    # ------------------------------------------------------------- repository helpers

    def git_raw(self, *args, **kwargs):
        cwd = kwargs.get("cwd", self.work)
        result = subprocess.run([REAL_GIT] + list(args), cwd=str(cwd), env=self.env, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        if result.returncode != 0:
            raise AssertionError("git %s failed: %s" % (" ".join(args), result.stderr.decode()))
        return result.stdout.decode().strip()

    def commit_pom(self, version, message):
        (self.work / "pom.xml").write_text(POM_TEMPLATE.format(version=version))
        self.git_raw("add", "-A")
        self.git_raw("commit", "-q", "-m", message)

    def head(self):
        return self.git_raw("rev-parse", "HEAD")

    def advance_origin(self):
        """Another actor moves origin/main after preparation."""
        other = self.root / "other"
        self.git_raw("clone", "-q", str(self.origin), str(other), cwd=self.root)
        (other / "NOTICE").write_text("moved\n")
        self.git_raw("add", "NOTICE", cwd=other)
        self.git_raw("commit", "-q", "-m", "move main", cwd=other)
        self.git_raw("push", "-q", "origin", "main", cwd=other)

    def tag_origin(self, tag, commit):
        self.git_raw("--git-dir", str(self.origin), "tag", tag, commit, cwd=self.root)

    # ------------------------------------------------------------------ fake GitHub

    def gh_state_data(self):
        path = self.gh_state / "state.json"
        return json.loads(path.read_text()) if path.exists() else {"releases": {}}

    def add_release(self, tag, target, assets, draft=False):
        state = self.gh_state_data()
        state["releases"][tag] = {"target": target, "draft": draft, "prerelease": False, "title": "pre-existing",
                                  "notes": "", "assets": sorted(assets)}
        folder = self.gh_state / "assets" / tag
        folder.mkdir(parents=True, exist_ok=True)
        for name, data in assets.items():
            (folder / name).write_bytes(data)
        (self.gh_state / "state.json").write_text(json.dumps(state, indent=2, sort_keys=True))

    def release_assets(self, tag=TAG):
        folder = self.gh_state / "assets" / tag
        rel = self.gh_state_data()["releases"].get(tag)
        if rel is None:
            return None
        return {name: sha256_file(folder / name) for name in rel["assets"]}

    # ----------------------------------------------------------------------- calls

    def calls(self, tool=None):
        entries = [json.loads(line) for line in self.log.read_text().splitlines() if line.strip()]
        return [e["argv"] for e in entries if tool is None or e["tool"] == tool]

    def gh_writes(self):
        return [argv for argv in self.calls("gh")
                if len(argv) > 1 and argv[0] == "release" and argv[1] in ("create", "upload", "edit", "delete")]

    def run_release(self, *args, **kwargs):
        env = dict(self.env)
        env.update(kwargs.get("env", {}))
        return subprocess.run([BASH, str(RELEASE_SH)] + list(args), cwd=str(self.work), env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=180)

    def run_helper(self, *args):
        return subprocess.run([sys.executable, "-I", str(HELPER)] + list(args), cwd=str(self.work),
                              env=self.env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=60)

    # -------------------------------------------------------------------- receipts

    def write_receipts(self, manifest_path, authority_overrides=None, scope_overrides=None, uat_overrides=None,
                       gate_overrides=None, response_text=None):
        """Write a framework-publish authority receipt and a gates record bound to the manifest."""
        manifest_path = Path(manifest_path)
        manifest = json.loads(manifest_path.read_text())
        manifest_sha = sha256_file(manifest_path)
        primary_sha = manifest["sha256"]
        head = manifest["source_head"]
        text = response_text or ("Question: publish UltiTools-API 6.3.0 exactly as prepared?\n"
                                 "Answer: publish these exact five files.\n")
        response = self.receipts / "response-framework-publish.txt"
        response.write_text(text)
        evidence = self.receipts / "evidence.txt"
        evidence.write_text("retained gate evidence\n")
        ref = {"path": "evidence.txt", "sha256": sha256_file(evidence)}
        scope = {
            "repo": REPO, "source_head": head, "product_tree": manifest["product_tree"],
            "version": manifest["version"], "manifest_sha256": manifest_sha,
            "artifacts": [{"filename": a["filename"], "bytes": a["bytes"], "sha256": a["sha256"]}
                          for a in manifest["artifacts"]],
            "destinations": ["GitHub", "MavenCentral"],
            "allowed_actions": ["central-publish", "release", "tag"],
        }
        scope.update(scope_overrides or {})
        authority = {
            "schema": "phase21-authority/1", "evidence_state": "observed", "action": "framework-publish",
            "repo": REPO, "question": "Publish UltiTools-API 6.3.0 exactly as prepared?", "decision": "publish",
            "response_decision_quote": "publish these exact five files",
            "human_response": {"path": response.name, "sha256": sha256_file(response),
                               "received_at": "2026-10-19T00:00:00Z"},
            "scope": scope,
        }
        authority.update(authority_overrides or {})
        gates = [
            {"gate": "ci", "status": "pass", "head": head, "evidence": ref, "configured_checks": ["test"],
             "observed_checks": [{"name": "test", "head": head, "conclusion": "success"}]},
            {"gate": "codacy", "status": "pass", "head": head, "evidence": ref, "configured": True},
            {"gate": "codex-review", "status": "pass", "head": head, "evidence": ref, "model": "gpt-6.1-sol",
             "effort": "high", "runs": 1},
            {"gate": "own-review", "status": "pass", "head": head, "evidence": ref, "independent": True},
            {"gate": "tests", "status": "pass", "head": head, "evidence": ref},
            {"gate": "uat", "status": "pass", "head": head, "evidence": ref, "independent": True,
             "saw_review_output": False, "artifact_sha256": primary_sha, "release_authorized": False},
            {"gate": "verification", "status": "pass", "head": head, "evidence": ref, "independent": True},
        ]
        for name, change in (gate_overrides or {}).items():
            for entry in gates:
                if entry["gate"] == name:
                    if change is None:
                        gates.remove(entry)
                    else:
                        entry.update(change)
                    break
        uat = {"schema": "phase21-gates/1", "evidence_state": "observed", "repo": REPO, "source_head": head,
               "candidate_sha256": primary_sha, "manifest_sha256": manifest_sha, "release_authorized": False,
               "gates": gates}
        uat.update(uat_overrides or {})
        authority_path = self.receipts / "framework-publish.json"
        uat_path = self.receipts / "gates.json"
        authority_path.write_text(json.dumps(authority, indent=2, sort_keys=True))
        uat_path.write_text(json.dumps(uat, indent=2, sort_keys=True))
        return authority_path, uat_path

    def cleanup(self):
        for path in self.root.rglob("*"):
            if not path.is_symlink():
                try:
                    path.chmod(0o700 if path.is_dir() else 0o600)
                except OSError:
                    pass
        shutil.rmtree(str(self.root), ignore_errors=True)


class HandoffTestCase(unittest.TestCase):

    def setUp(self):
        self.fx = ReleaseFixture()
        self.addCleanup(self.fx.cleanup)
        self.retain = self.fx.root / "retained" / VERSION
        self.retain.parent.mkdir()

    def prepare(self, **kwargs):
        return self.fx.run_release("--prepare", str(self.retain), **kwargs)

    def prepared(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stdout.decode())
        return self.retain / MANIFEST

    def publish(self, manifest, authority, uat, *extra, **kwargs):
        env = {"FAKE_MVN_FORBID": "1"}
        env.update(kwargs.pop("env", {}))
        return self.fx.run_release("--publish-approved", str(manifest), "--authority-receipt", str(authority),
                                   "--uat-receipt", str(uat), "--notes", "Release notes.", "--yes", *extra,
                                   env=env, **kwargs)

    def assert_no_build(self):
        builds = [argv for argv in self.fx.calls("mvn") if "verify" in argv or "package" in argv
                  or "deploy" in argv or "install" in argv]
        self.assertEqual(builds, [], "publish-approved must never run a Maven build")
        self.assertEqual(self.fx.calls("mvn"), [], "publish-approved must not invoke Maven at all")

    def assert_blocked_without_writes(self, result, needle=None):
        output = result.stdout.decode()
        self.assertNotEqual(result.returncode, 0, output)
        self.assertEqual(self.fx.gh_writes(), [], "a blocked run must not write to GitHub: " + output)
        if needle is not None:
            self.assertIn(needle, output)


class TracerHandoffTests(HandoffTestCase):
    """Task 1: one real prepare and retained-asset publication path under a fake remote."""

    def test_fakes_shadow_real_tools(self):
        result = subprocess.run([BASH, "-c", "command -v mvn; command -v gh; command -v git"], env=self.fx.env,
                                stdout=subprocess.PIPE, timeout=30)
        resolved = result.stdout.decode().split()
        self.assertEqual(resolved, [str(self.fx.bin / "mvn"), str(self.fx.bin / "gh"), str(self.fx.bin / "git")])

    def test_prepare_retains_exact_files_and_creates_no_release(self):
        result = self.prepare()
        output = result.stdout.decode()
        self.assertEqual(result.returncode, 0, output)
        verify_calls = [argv for argv in self.fx.calls("mvn") if "verify" in argv]
        self.assertEqual(verify_calls, [["-B", "clean", "verify"]])
        self.assertEqual(sorted(p.name for p in self.retain.iterdir()), sorted(PAYLOADS + (MANIFEST,)))
        target = self.fx.work / "target"
        self.assertEqual((self.retain / PRIMARY).read_bytes(), (target / PRIMARY).read_bytes())
        self.assertEqual((self.retain / SOURCES).read_bytes(), (target / SOURCES).read_bytes())
        self.assertEqual((self.retain / JAVADOC).read_bytes(), (target / JAVADOC).read_bytes())
        retained_pom = (self.retain / POM).read_bytes()
        self.assertEqual(retained_pom, (self.fx.work / ".flattened-pom.xml").read_bytes())
        self.assertNotEqual(retained_pom, (self.fx.work / "pom.xml").read_bytes())
        self.assertNotIn(b"<dependencies>", retained_pom)
        # GitHub Packages gets the flattened POM with only the project artifactId lower-cased.
        lower = retained_pom.replace(b"<artifactId>UltiTools-API</artifactId>",
                                     b"<artifactId>ultitools-api</artifactId>")
        self.assertNotEqual(lower, retained_pom)
        self.assertEqual((self.retain / GHP_POM).read_bytes(), lower)
        self.assertEqual(self.fx.gh_writes(), [])
        self.assertIsNone(self.fx.release_assets())
        self.assertEqual(self.fx.git_raw("tag", "-l", TAG), "")
        self.assertEqual(self.fx.git_raw("--git-dir", str(self.fx.origin), "tag", "-l", TAG, cwd=self.fx.root), "")

    def test_prepare_refuses_hidden_untracked_files_before_build(self):
        self.fx.git_raw("config", "status.showUntrackedFiles", "no")
        (self.fx.work / "untracked-source.txt").write_text("not accepted\n")
        result = self.prepare()
        self.assert_blocked_without_writes(result, "The working tree is not clean")
        self.assertIn("?? untracked-source.txt", result.stdout.decode())
        self.assertEqual(self.fx.calls("mvn"), [])
        self.assertFalse(self.retain.exists())

    def test_prepare_refuses_untracked_output_and_names_path(self):
        self.fx.git_raw("config", "status.showUntrackedFiles", "no")
        result = self.prepare(env={"FAKE_MVN_VARIANT": "untracked-build-file"})
        self.assert_blocked_without_writes(result, "The build changed the working tree")
        self.assertIn("?? build/", result.stdout.decode())
        self.assertFalse(self.retain.exists())

    def test_prepare_then_publish_same_checkout_with_real_test_store_path(self):
        source = (REPO_ROOT / "src/test/java/com/ultikits/ultitools/manager/PluginManagerAopWiringTest.java")
        store = re.search(r'new JsonStore\("([^"]+)"\)', source.read_text()).group(1)
        result = self.prepare(env={"FAKE_MVN_VARIANT": "test-store-output", "FAKE_TEST_STORE": store})
        self.assertEqual(result.returncode, 0, result.stdout.decode())
        manifest = self.retain / MANIFEST
        authority, uat = self.fx.write_receipts(manifest)
        result = self.publish(manifest, authority, uat)
        self.assertEqual(result.returncode, 0, result.stdout.decode())
        self.assertEqual(self.fx.git_raw("status", "--porcelain"), "")
        self.assertEqual(sorted(self.fx.release_assets()), sorted(PAYLOADS + (MANIFEST,)))

    def test_prepare_refuses_tracked_changes_made_by_build(self):
        result = self.prepare(env={"FAKE_MVN_VARIANT": "dirty-tree"})
        self.assert_blocked_without_writes(result, "The build changed the working tree")
        self.assertIn(" M pom.xml", result.stdout.decode())
        self.assertFalse(self.retain.exists())
        self.assertEqual(self.fx.git_raw("status", "--porcelain"), "M pom.xml")

    def test_dry_run_refuses_untracked_output_and_names_path(self):
        result = self.fx.run_release("--dry-run", env={"FAKE_MVN_VARIANT": "untracked-build-file"})
        self.assert_blocked_without_writes(result, "?? build/")
        self.assertFalse(self.retain.exists())

    def test_prepare_manifest_binds_source_and_raw_bytes(self):
        manifest_path = self.prepared()
        manifest = json.loads(manifest_path.read_text())
        self.assertEqual(manifest["schema_version"], 1)
        self.assertEqual(manifest["repo"], REPO)
        self.assertEqual(manifest["group_id"], "com.ultikits")
        self.assertEqual(manifest["artifact_id"], "UltiTools-API")
        self.assertEqual(manifest["version"], VERSION)
        self.assertEqual(manifest["source_head"], self.fx.head())
        self.assertEqual(manifest["product_tree"], self.fx.git_raw("rev-parse", "HEAD^{tree}"))
        self.assertEqual(manifest["prior_served_version"], PRIOR)
        self.assertIsNone(manifest["identify_string"])
        self.assertEqual(manifest["plugin_api_version"], "1.19")
        self.assertEqual(manifest["filename"], PRIMARY)
        self.assertEqual(manifest["sha256"], sha256_file(self.retain / PRIMARY))
        self.assertEqual(manifest["bytes"], (self.retain / PRIMARY).stat().st_size)
        roles = {a["role"]: a for a in manifest["artifacts"]}
        self.assertEqual(sorted(roles), ["github-packages-pom", "javadoc", "pom", "primary", "sources"])
        self.assertEqual([a["filename"] for a in manifest["artifacts"]], sorted(PAYLOADS))
        for artifact in manifest["artifacts"]:
            path = self.retain / artifact["filename"]
            self.assertEqual(artifact["sha256"], sha256_file(path))
            self.assertEqual(artifact["bytes"], path.stat().st_size)
        raw = manifest_path.read_text()
        self.assertNotIn(str(self.fx.root), raw, "the public manifest must never carry a private path")
        self.assertEqual(raw, json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=True) + "\n")

    def test_prepare_never_selects_a_wildcard_neighbour(self):
        manifest = json.loads(self.prepared().read_text())
        original = self.fx.work / "target" / ("original-" + PRIMARY)
        self.assertTrue(original.exists())
        self.assertNotEqual(manifest["sha256"], sha256_file(original))
        names = [a["filename"] for a in manifest["artifacts"]]
        self.assertNotIn("original-" + PRIMARY, names)

    def test_prepare_refuses_jar_without_main_class(self):
        result = self.prepare(env={"FAKE_MVN_VARIANT": "no-main-class"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("main_class_missing", result.stdout.decode())
        self.assertFalse(self.retain.exists(), "nothing may be retained from a rejected build")

    def test_prepare_refuses_sources_jar_without_java_sources(self):
        result = self.prepare(env={"FAKE_MVN_VARIANT": "sources-without-java"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sources_without_java", result.stdout.decode())
        self.assertFalse(self.retain.exists())

    def test_publish_approved_uploads_retained_bytes_without_building(self):
        manifest_path = self.prepared()
        head = self.fx.head()
        expected = {name: sha256_file(self.retain / name) for name in PAYLOADS + (MANIFEST,)}
        authority, uat = self.fx.write_receipts(manifest_path)
        self.fx.log.write_text("")
        result = self.publish(manifest_path, authority, uat)
        output = result.stdout.decode()
        self.assertEqual(result.returncode, 0, output)
        self.assert_no_build()
        creates = [argv for argv in self.fx.calls("gh") if argv[:2] == ["release", "create"]]
        self.assertEqual(len(creates), 1, output)
        create = creates[0]
        self.assertEqual(create[2], TAG)
        self.assertEqual(create[create.index("--target") + 1], head)
        uploaded = [arg for arg in create if arg.startswith(str(self.retain))]
        self.assertEqual(sorted(Path(p).name for p in uploaded), sorted(expected))
        self.assertEqual(self.fx.release_assets(), expected)
        self.assertFalse(self.fx.gh_state_data()["releases"][TAG]["draft"])

    def test_default_invocation_refuses_to_build_and_publish(self):
        result = self.fx.run_release("--yes")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([argv for argv in self.fx.calls("mvn") if "verify" in argv], [])
        self.assertEqual(self.fx.gh_writes(), [])

    def test_dry_run_is_nonpublishing_and_not_acceptance_evidence(self):
        result = self.fx.run_release("--dry-run")
        output = result.stdout.decode()
        self.assertEqual(result.returncode, 0, output)
        self.assertEqual(self.fx.gh_writes(), [])
        self.assertIn("not acceptance evidence", output)
        self.assertFalse(self.retain.exists())


def rewrite_manifest(path, mutate):
    """Rewrite a retained manifest canonically after applying mutate(data)."""
    path = Path(path)
    path.chmod(0o644)
    data = json.loads(path.read_text())
    mutate(data)
    path.write_text(json.dumps(data, indent=2, sort_keys=True, ensure_ascii=True) + "\n")
    return path


def flip_last_byte(path):
    path = Path(path)
    path.chmod(0o644)
    data = bytearray(path.read_bytes())
    data[-1] ^= 0x01
    path.write_bytes(bytes(data))


def lock_path(fx):
    return Path(fx.git_raw("rev-parse", "--absolute-git-dir")) / ("ultitools-release-%s.lock" % VERSION)


class EdgeHandoffTests(HandoffTestCase):
    """Task 2: mutation, stale authorization, collisions and interrupted repeats all block or reconcile."""

    def approved(self):
        manifest = self.prepared()
        authority, uat = self.fx.write_receipts(manifest)
        self.fx.log.write_text("")
        return manifest, authority, uat

    def expected_assets(self):
        return {name: sha256_file(self.retain / name) for name in PAYLOADS + (MANIFEST,)}

    # ------------------------------------------------------------------- empty

    def test_empty_manifest_blocks_publication(self):
        manifest, authority, uat = self.approved()
        manifest.chmod(0o644)
        manifest.write_bytes(b"")
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "record_empty")
        self.assert_no_build()

    def test_empty_artifact_list_blocks_publication(self):
        manifest, _, _ = self.approved()
        rewrite_manifest(manifest, lambda m: m.update(artifacts=[]))
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "artifacts_empty")

    def test_missing_receipts_block_publication(self):
        manifest, authority, _ = self.approved()
        result = self.fx.run_release("--publish-approved", str(manifest), "--authority-receipt", str(authority),
                                     "--yes", env={"FAKE_MVN_FORBID": "1"})
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.fx.gh_writes(), [])
        missing = self.fx.receipts / "absent.json"
        result = self.publish(manifest, authority, missing)
        self.assert_blocked_without_writes(result, "file_missing")

    # ------------------------------------------------------------ byte mutation

    def test_one_flipped_byte_in_the_primary_jar_blocks(self):
        manifest, authority, uat = self.approved()
        flip_last_byte(self.retain / PRIMARY)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "sha256_mismatch")

    def test_pom_byte_mutation_blocks(self):
        manifest, authority, uat = self.approved()
        pom = self.retain / POM
        pom.chmod(0o644)
        data = pom.read_bytes()
        changed = data.replace(b"</description>", b" </description>", 1)
        self.assertNotEqual(changed, data)
        pom.write_bytes(changed)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "sha256_mismatch")

    def test_github_packages_pom_differing_beyond_the_artifact_id_is_refused(self):
        # Synthetic refusal fixture: one extra byte in the lower-case POM, with the manifest and receipts
        # recomputed so only the derivation check can catch it.
        manifest, _, _ = self.approved()
        pom = self.retain / GHP_POM
        pom.chmod(0o644)
        changed = pom.read_bytes().replace(b"</description>", b" </description>", 1)
        pom.write_bytes(changed)

        def mutate(m):
            for artifact in m["artifacts"]:
                if artifact["role"] == "github-packages-pom":
                    artifact["bytes"] = len(changed)
                    artifact["sha256"] = sha256_bytes(changed)
        rewrite_manifest(manifest, mutate)
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "github_packages_pom_mismatch")

    def test_retained_compile_dependency_is_refused_even_with_consistent_receipts(self):
        manifest, _, _ = self.approved()
        pom = self.retain / POM
        pom.chmod(0o644)
        changed = pom.read_bytes().replace(b"</project>", b"<dependencies><dependency>"
                                           b"<groupId>fixture</groupId><artifactId>shaded</artifactId>"
                                           b"<version>1</version><scope>compile</scope>"
                                           b"</dependency></dependencies></project>")
        pom.write_bytes(changed)

        def mutate(m):
            for artifact in m["artifacts"]:
                if artifact["role"] == "pom":
                    artifact["bytes"] = len(changed)
                    artifact["sha256"] = sha256_bytes(changed)
        rewrite_manifest(manifest, mutate)
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "pom_publication_section")

    def test_detached_signature_may_be_added_but_payloads_stay_exact(self):
        manifest, authority, uat = self.approved()
        (self.retain / (PRIMARY + ".asc")).write_text("detached signature\n")
        result = self.publish(manifest, authority, uat)
        self.assertEqual(result.returncode, 0, result.stdout.decode())
        self.assertEqual(self.fx.release_assets(), self.expected_assets())

    # ------------------------------------------------------------ names and source

    def test_wrong_exact_filename_blocks(self):
        manifest, _, _ = self.approved()
        lower = PRIMARY.lower()
        (self.retain / PRIMARY).rename(self.retain / lower)

        def mutate(m):
            m["filename"] = lower
            for artifact in m["artifacts"]:
                if artifact["role"] == "primary":
                    artifact["filename"] = lower
            m["artifacts"].sort(key=lambda a: a["filename"])
        rewrite_manifest(manifest, mutate)
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "artifact_filename")

    def test_wrong_source_tree_blocks(self):
        manifest, _, _ = self.approved()
        rewrite_manifest(manifest, lambda m: m.update(product_tree="0" * 40))
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "product_tree_mismatch")

    def test_moving_remote_head_after_preparation_blocks(self):
        manifest, authority, uat = self.approved()
        self.fx.advance_origin()
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat))

    def test_remote_head_moving_just_before_the_write_blocks(self):
        manifest, authority, uat = self.approved()
        side = self.fx.root / "side"
        self.fx.git_raw("clone", "-q", str(self.fx.origin), str(side), cwd=self.fx.root)
        (side / "NOTICE").write_text("side\n")
        self.fx.git_raw("add", "NOTICE", cwd=side)
        self.fx.git_raw("commit", "-q", "-m", "side", cwd=side)
        self.fx.git_raw("push", "-q", "origin", "HEAD:refs/heads/side", cwd=side)
        side_sha = self.fx.git_raw("rev-parse", "HEAD", cwd=side)
        result = self.publish(manifest, authority, uat, env={"FAKE_GH_VIEW_MOVE_MAIN_TO": side_sha})
        self.assert_blocked_without_writes(result, "moved")

    # ---------------------------------------------------------- ordering/canonical

    def test_unsorted_or_noncanonical_manifest_blocks(self):
        manifest, _, _ = self.approved()
        rewrite_manifest(manifest, lambda m: m["artifacts"].reverse())
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "artifacts_not_sorted")
        data = json.loads(manifest.read_text())
        data["artifacts"].sort(key=lambda a: a["filename"])
        manifest.write_text(json.dumps(data, sort_keys=True))
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "manifest_not_canonical")

    def test_manifest_cannot_carry_permission_data_or_private_paths(self):
        manifest, _, _ = self.approved()
        rewrite_manifest(manifest, lambda m: m.update(authority_receipt=str(self.fx.receipts / "framework-publish.json")))
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "manifest_unknown_field")

    # -------------------------------------------------------------- stale authority

    def test_stale_or_insufficient_authority_blocks(self):
        cases = {
            "other manifest digest": ({}, {"manifest_sha256": "f" * 64}, None, "authority_stale"),
            "hold": ({"decision": "hold"}, {}, None, "authority_not_publish"),
            "boolean approval": ({"approved": True}, {}, None, "boolean_approval_rejected"),
            "tag not granted": ({}, {"allowed_actions": ["central-publish", "release"]}, None, "authority_actions"),
            "quote not in response": ({"response_decision_quote": "ship anything"}, {}, None,
                                      "decision_not_in_response"),
            "other source": ({}, {"source_head": "1" * 40}, None, "authority_stale"),
        }
        manifest, _, _ = self.approved()
        for label, (top, scope, text, code) in cases.items():
            with self.subTest(label):
                self.fx.log.write_text("")
                authority, uat = self.fx.write_receipts(manifest, authority_overrides=top, scope_overrides=scope,
                                                        response_text=text)
                self.assert_blocked_without_writes(self.publish(manifest, authority, uat), code)

    def test_tampered_retained_response_blocks(self):
        manifest, authority, uat = self.approved()
        (self.fx.receipts / "response-framework-publish.txt").write_text("Answer: publish these exact five files. "
                                                                         "Also anything else.\n")
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "evidence_sha256_mismatch")

    def test_stale_or_incomplete_uat_blocks(self):
        cases = {
            "uat on other bytes": ({}, {"uat": {"artifact_sha256": "e" * 64}}, "uat_stale"),
            "uat missing": ({}, {"uat": None}, "uat_gate_missing"),
            "uat saw review": ({}, {"uat": {"saw_review_output": True}}, "uat_not_independent"),
            "ci pending": ({}, {"ci": {"status": "pending"}}, "uat_gate_not_passed"),
            "other manifest": ({"manifest_sha256": "d" * 64}, {}, "uat_stale"),
        }
        manifest, _, _ = self.approved()
        for label, (top, gates, code) in cases.items():
            with self.subTest(label):
                self.fx.log.write_text("")
                authority, uat = self.fx.write_receipts(manifest, uat_overrides=top, gate_overrides=gates)
                self.assert_blocked_without_writes(self.publish(manifest, authority, uat), code)

    # ------------------------------------------------------------- adjacency

    def test_equal_served_version_is_a_collision(self):
        manifest, _, _ = self.approved()
        rewrite_manifest(manifest, lambda m: m.update(prior_served_version=VERSION))
        authority, uat = self.fx.write_receipts(manifest)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "version_collision")

    def test_prepare_refuses_an_occupied_version(self):
        self.fx.tag_origin(TAG, self.fx.git_raw("rev-parse", "HEAD~1"))
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.retain.exists())
        self.assertEqual([argv for argv in self.fx.calls("mvn") if "verify" in argv], [])

    def test_tag_at_another_commit_blocks_publication(self):
        manifest, authority, uat = self.approved()
        self.fx.tag_origin(TAG, self.fx.git_raw("rev-parse", "HEAD~1"))
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), TAG)

    def test_existing_release_with_different_bytes_is_never_replaced(self):
        manifest, authority, uat = self.approved()
        head = self.fx.head()
        other = (self.retain / PRIMARY).read_bytes() + b"different build"
        self.fx.add_release(TAG, head, {PRIMARY: other})
        before = self.fx.release_assets()
        result = self.publish(manifest, authority, uat)
        self.assert_blocked_without_writes(result, "mismatch")
        self.assertEqual(self.fx.release_assets(), before)

    def test_existing_release_on_another_target_is_refused(self):
        manifest, authority, uat = self.approved()
        self.fx.add_release(TAG, self.fx.git_raw("rev-parse", "HEAD~1"), {}, draft=True)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "target")

    def test_existing_release_with_an_unexpected_asset_is_refused(self):
        manifest, authority, uat = self.approved()
        self.fx.add_release(TAG, self.fx.head(), {"extra.jar": b"PK"}, draft=True)
        self.assert_blocked_without_writes(self.publish(manifest, authority, uat), "unexpected")

    def test_publish_dry_run_rechecks_without_writing(self):
        manifest, authority, uat = self.approved()
        result = self.publish(manifest, authority, uat, "--dry-run")
        output = result.stdout.decode()
        self.assertEqual(result.returncode, 0, output)
        self.assertIn("not acceptance evidence", output)
        self.assertEqual(self.fx.gh_writes(), [])
        self.assertIsNone(self.fx.release_assets())
        self.assert_no_build()

    # ---------------------------------------------------------- idempotency

    def test_interrupted_publication_is_reconciled_without_replacement(self):
        manifest, authority, uat = self.approved()
        first = self.publish(manifest, authority, uat, env={"FAKE_GH_FAIL_AFTER_ASSETS": "2"})
        self.assertNotEqual(first.returncode, 0)
        partial = self.fx.release_assets()
        self.assertEqual(len(partial), 2)
        self.assertTrue(self.fx.gh_state_data()["releases"][TAG]["draft"])
        self.fx.log.write_text("")
        second = self.publish(manifest, authority, uat)
        output = second.stdout.decode()
        self.assertEqual(second.returncode, 0, output)
        self.assert_no_build()
        gh = self.fx.calls("gh")
        self.assertEqual([a for a in gh if a[:2] == ["release", "create"]], [], "a retry never creates twice")
        uploads = [a for a in gh if a[:2] == ["release", "upload"]]
        self.assertEqual(len(uploads), 1, output)
        uploaded = sorted(Path(p).name for p in uploads[0][3:])
        self.assertEqual(uploaded, sorted(set(self.expected_assets()) - set(partial)))
        self.assertEqual(self.fx.release_assets(), self.expected_assets())
        self.assertFalse(self.fx.gh_state_data()["releases"][TAG]["draft"])
        self.assertEqual(self.fx.git_raw("--git-dir", str(self.fx.origin), "rev-parse", TAG + "^{commit}",
                                         cwd=self.fx.root), self.fx.head())
        self.assertFalse(any("--clobber" in a for a in gh))

    def test_repeat_after_success_changes_nothing(self):
        manifest, authority, uat = self.approved()
        self.assertEqual(self.publish(manifest, authority, uat).returncode, 0)
        published = self.fx.release_assets()
        self.fx.log.write_text("")
        again = self.publish(manifest, authority, uat)
        self.assertEqual(again.returncode, 0, again.stdout.decode())
        self.assertEqual(self.fx.gh_writes(), [])
        self.assertEqual(self.fx.release_assets(), published)
        self.assertIn("already published", again.stdout.decode())

    def test_readback_detects_a_corrupted_upload(self):
        manifest, authority, uat = self.approved()
        result = self.publish(manifest, authority, uat, env={"FAKE_GH_CORRUPT_UPLOAD": PRIMARY})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("readback", result.stdout.decode())

    # ---------------------------------------------------------- concurrency

    def test_concurrent_operation_on_the_same_version_is_rejected(self):
        manifest, authority, uat = self.approved()
        lock = lock_path(self.fx)
        lock.mkdir()
        result = self.publish(manifest, authority, uat)
        self.assert_blocked_without_writes(result, "in progress")
        self.assertTrue(lock.exists(), "a run must never remove a lock it did not take")
        lock.rmdir()
        self.assertEqual(self.publish(manifest, authority, uat).returncode, 0)
        self.assertFalse(lock.exists(), "the lock is released after the run")

    def test_prepare_is_rejected_while_the_version_is_locked(self):
        lock = lock_path(self.fx)
        lock.mkdir()
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("in progress", result.stdout.decode())
        self.assertFalse(self.retain.exists())

    # ------------------------------------------------------------- helper model

    def test_compare_classifies_remote_assets(self):
        manifest = self.prepared()
        remote = self.fx.root / "remote"
        remote.mkdir()
        result = self.fx.run_helper("compare", "--manifest", str(manifest), "--remote-dir", str(remote))
        self.assertEqual(result.returncode, 3, result.stderr.decode())
        self.assertEqual(json.loads(result.stdout.decode())["status"], "absent")
        shutil.copyfile(str(self.retain / PRIMARY), str(remote / PRIMARY))
        result = self.fx.run_helper("compare", "--manifest", str(manifest), "--remote-dir", str(remote))
        self.assertEqual(result.returncode, 3)
        self.assertEqual(json.loads(result.stdout.decode())["status"], "partial")
        for name in PAYLOADS + (MANIFEST,):
            shutil.copyfile(str(self.retain / name), str(remote / name))
        result = self.fx.run_helper("compare", "--manifest", str(manifest), "--remote-dir", str(remote))
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout.decode())["status"], "identical")
        flip_last_byte(remote / SOURCES)
        result = self.fx.run_helper("compare", "--manifest", str(manifest), "--remote-dir", str(remote))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout.decode())["mismatched"], [SOURCES])


if __name__ == "__main__":
    unittest.main()
