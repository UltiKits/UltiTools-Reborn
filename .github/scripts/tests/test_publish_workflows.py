"""Hermetic execution tests for the two publication workflows.

The tests read the real workflow files, extract each job's steps with a small parser for the subset of YAML
the workflows use, and run every ``run:`` block the way GitHub Actions does (``bash --noprofile --norc -eo
pipefail``), in order, honouring ``if:`` conditions, step outputs and ``continue-on-error``. ``uses:`` steps
are simulated: the checkout is prepared by the fixture and setup-java is a no-op. An expression the harness
does not understand fails the test instead of being guessed, and a ``${{ }}`` expression inside a run block
fails it too, because a workflow must pass values to scripts through ``env``.

Every external command a step can reach is a fake placed first on PATH in a temporary directory:

- ``mvn`` logs each call. For snapshot-publish it answers ``help:evaluate`` with the fixture's project version
  and records a snapshot ``deploy``. For publish-packages it records ``deploy:deploy-file`` into a local
  GitHub Packages store, and hands the Central publisher goal to the REAL Maven in offline mode with an empty
  settings file: real Maven loads the registrar as a core extension, the registrar validates the retained
  files, and Maven then refuses the online-only goal before the mojo runs. Only after that refusal does the
  fake record a simulated successful publication. Any lifecycle build (clean, package, verify, install,
  deploy) or versions:set exits 97.
- ``gh`` serves release assets from a local state directory; anything it does not model exits 96.
- ``python3`` runs the real interpreter with ``urllib.request.urlopen`` replaced by a fake internet that
  serves Maven Central and GitHub Packages paths from the state directory.
- ``gpg``, ``git`` and ``javac`` are real, with a throwaway key in a temporary GNUPGHOME.

The actual-plugin suite compiles a small Java probe against the installed central-publishing-maven-plugin
0.9.0 and the active Maven distribution, and drives the real registrar, MavenProject, PublishMojo work
directory and artifact collection, ArtifactStagerImpl and its Maven installer, and the bundler to a LOCAL
file boundary. The mojo's client, uploader and watcher stay null, so nothing can reach the network. A missing
plugin or Maven distribution fails those tests as BLOCKED; they are never skipped.

No test contacts a network service, publishes anything or reads a credential. Standard library only.
"""

import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent.parent
WORKFLOWS = REPO_ROOT / ".github" / "workflows"
SNAPSHOT_WORKFLOW = WORKFLOWS / "snapshot-publish.yml"
PUBLISH_WORKFLOW = WORKFLOWS / "publish-packages.yml"
HELPER = SCRIPTS / "release_artifacts.py"
REGISTRAR_DIR = SCRIPTS / "maven"
REGISTRAR_SOURCE = REGISTRAR_DIR / "RetainedArtifactsParticipant.java"
REGISTRAR_DESCRIPTOR = REGISTRAR_DIR / "components.xml"
BASH = shutil.which("bash")
REAL_GIT = shutil.which("git")
REAL_GPG = shutil.which("gpg")
REAL_MVN = shutil.which("mvn")
PLUGIN_COORDS = ("org.sonatype.central", "central-publishing-maven-plugin", "0.9.0")
VERSION = "6.3.0"
PRIOR = "6.2.5"
TAG = "v" + VERSION
BASE = "UltiTools-API-" + VERSION
PAYLOADS = {"primary": BASE + ".jar", "pom": BASE + ".pom", "sources": BASE + "-sources.jar",
            "javadoc": BASE + "-javadoc.jar"}
MANIFEST = BASE + "-release-manifest.json"
SIGNED = (PAYLOADS["pom"], PAYLOADS["primary"], PAYLOADS["sources"], PAYLOADS["javadoc"])


# ------------------------------------------------------------------ workflow parsing

class WorkflowParser(object):
    """Parse the YAML subset GitHub workflow files in this repository use.

    Supported: nested block mappings, block sequences of mappings (including a sequence at the same
    indentation as its parent key), literal block scalars (``|``), plain and quoted scalars, flow sequences
    of plain scalars and full-line or trailing comments outside block scalars. Anything else raises, so the
    harness never runs a step it misread.
    """

    def __init__(self, text):
        self.lines = text.splitlines()
        self.i = 0

    @staticmethod
    def indent_of(line):
        return len(line) - len(line.lstrip(" "))

    def skip(self):
        while self.i < len(self.lines):
            stripped = self.lines[self.i].strip()
            if stripped and not stripped.startswith("#"):
                return
            self.i += 1

    def parse(self):
        self.skip()
        return self.parse_map(0)

    def parse_map(self, indent):
        result = {}
        while True:
            self.skip()
            if self.i >= len(self.lines):
                return result
            line = self.lines[self.i]
            current = self.indent_of(line)
            content = line.strip()
            if current < indent or content.startswith("- "):
                return result
            if current > indent:
                raise ValueError("unexpected indentation at line %d: %r" % (self.i + 1, line))
            key, sep, rest = content.partition(":")
            if not sep or not key:
                raise ValueError("not a mapping entry at line %d: %r" % (self.i + 1, line))
            rest = rest.strip()
            self.i += 1
            if rest == "|":
                value = self.block_scalar(current)
            elif rest == "" or rest.startswith("#"):
                self.skip()
                value = None
                if self.i < len(self.lines):
                    following = self.lines[self.i]
                    following_indent = self.indent_of(following)
                    if following.strip().startswith("- ") and following_indent >= current:
                        value = self.parse_list(following_indent)
                    elif following_indent > current:
                        value = self.parse_map(following_indent)
            else:
                value = self.scalar(rest)
            if key in result:
                raise ValueError("duplicate key %r at line %d" % (key, self.i))
            result[key] = value

    def parse_list(self, indent):
        items = []
        while True:
            self.skip()
            if self.i >= len(self.lines):
                return items
            line = self.lines[self.i]
            if self.indent_of(line) != indent or not line.strip().startswith("- "):
                return items
            self.lines[self.i] = " " * (indent + 2) + line.strip()[2:]
            items.append(self.parse_map(indent + 2))

    def block_scalar(self, key_indent):
        body = []
        while self.i < len(self.lines):
            line = self.lines[self.i]
            if line.strip() and self.indent_of(line) <= key_indent:
                break
            body.append(line)
            self.i += 1
        while body and not body[-1].strip():
            body.pop()
        return textwrap.dedent("\n".join(body)) + "\n"

    @staticmethod
    def scalar(rest):
        if rest[:1] in ("'", '"'):
            quote = rest[0]
            end = rest.index(quote, 1)
            return rest[1:end]
        if " #" in rest:
            rest = rest.split(" #", 1)[0].rstrip()
        if rest.startswith("[") and rest.endswith("]"):
            return [item.strip() for item in rest[1:-1].split(",") if item.strip()]
        if rest.startswith("{"):
            raise ValueError("flow mappings are not supported: %r" % rest)
        return rest


def load_workflow(path):
    return WorkflowParser(Path(path).read_bytes().decode("utf-8")).parse()


def step_named(job, name):
    matches = [s for s in job["steps"] if s.get("name") == name]
    if len(matches) != 1:
        raise AssertionError("expected exactly one step named %r, found %d" % (name, len(matches)))
    return matches[0]


# ------------------------------------------------------------------ expressions

EXPRESSION = re.compile(r"\$\{\{\s*(.*?)\s*\}\}")
COMPARISON = re.compile(r"^([A-Za-z_][A-Za-z0-9_.-]*)\s*(==|!=)\s*'([^']*)'$")
PATH_EXPR = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]*$")


class UnsupportedExpression(Exception):
    pass


def lookup(path, context):
    node = context
    for part in path.split("."):
        if not isinstance(node, dict):
            raise UnsupportedExpression("cannot resolve %r" % path)
        node = node.get(part, "")
    if isinstance(node, dict):
        raise UnsupportedExpression("%r resolves to a mapping" % path)
    return "" if node is None else str(node)


def evaluate(expression, context):
    expression = expression.strip()
    match = COMPARISON.match(expression)
    if match:
        left = lookup(match.group(1), context)
        equal = left == match.group(3)
        return equal if match.group(2) == "==" else not equal
    if PATH_EXPR.match(expression):
        return lookup(expression, context)
    raise UnsupportedExpression("unsupported expression %r" % expression)


def substitute(value, context):
    return EXPRESSION.sub(lambda m: str(evaluate(m.group(1), context)), str(value))


def condition_holds(condition, context):
    if condition is None:
        return True
    text = str(condition).strip()
    whole = EXPRESSION.fullmatch(text)
    if whole:
        text = whole.group(1)
    result = evaluate(text, context)
    if not isinstance(result, bool):
        raise UnsupportedExpression("condition %r is not a comparison" % condition)
    return result


# ------------------------------------------------------------------ job runner

class JobResult(object):
    def __init__(self):
        self.status = "success"
        self.steps = []          # (name, outcome, returncode)
        self.outputs = {}        # step id -> outputs
        self.step_context = {}
        self.log = ""

    def outcome(self, name):
        for step_name, outcome, _ in self.steps:
            if step_name == name:
                return outcome
        return None


def parse_output_file(path):
    outputs = {}
    if not path.exists():
        return outputs
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, _, value = line.partition("=")
            outputs[key] = value
    return outputs


def run_job(job, workspace, base_env, context, runner_temp, uses_handler=None):
    """Run a job's steps in order the way the Actions runner does; return a JobResult."""
    result = JobResult()
    context = dict(context)
    context["steps"] = {}
    result.step_context = context["steps"]
    if not condition_holds(job.get("if"), context):
        result.status = "skipped"
        return result
    for index, step in enumerate(job["steps"]):
        name = step.get("name") or step.get("uses") or "step-%d" % index
        if result.status == "failure":
            result.steps.append((name, "skipped", None))
            continue
        if not condition_holds(step.get("if"), context):
            result.steps.append((name, "skipped", None))
            continue
        if "uses" in step:
            if uses_handler is not None:
                uses_handler(step)
            elif not str(step["uses"]).split("@")[0] in ("actions/checkout", "actions/setup-java"):
                raise UnsupportedExpression("no simulation for %r" % step["uses"])
            result.steps.append((name, "success", 0))
            continue
        script = step.get("run")
        if script is None:
            raise UnsupportedExpression("step %r has neither run nor uses" % name)
        if "${{" in script:
            raise AssertionError("step %r expands ${{ }} inside its run block; pass values through env" % name)
        env = dict(base_env)
        for key, value in (step.get("env") or {}).items():
            env[key] = substitute(value, context)
        output_file = runner_temp / ("output-%d" % index)
        if output_file.exists():
            output_file.unlink()
        env["GITHUB_OUTPUT"] = str(output_file)
        env["GITHUB_STEP_SUMMARY"] = str(runner_temp / "step-summary.md")
        script_file = runner_temp / ("step-%d.sh" % index)
        script_file.write_text(script, encoding="utf-8")
        proc = subprocess.run([BASH, "--noprofile", "--norc", "-eo", "pipefail", str(script_file)],
                              cwd=str(workspace), env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=600)
        text = proc.stdout.decode("utf-8", "replace")
        result.log += "--- step: %s (exit %d)\n%s" % (name, proc.returncode, text)
        outputs = parse_output_file(output_file)
        if step.get("id"):
            context["steps"][step["id"]] = {"outputs": outputs}
            result.outputs[step["id"]] = outputs
        if proc.returncode == 0:
            result.steps.append((name, "success", 0))
        elif str(step.get("continue-on-error", "")).lower() == "true":
            result.steps.append((name, "failure-continued", proc.returncode))
        else:
            result.steps.append((name, "failure", proc.returncode))
            result.status = "failure"
    return result


def job_outputs(job, result):
    """Resolve a job's declared outputs from the step outputs of its run."""
    context = {"steps": result.step_context}
    return dict((key, substitute(value, context)) for key, value in (job.get("outputs") or {}).items())


def make_executable(path, text):
    path.write_text(text.replace("{python}", sys.executable), encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


def read_log(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


# ------------------------------------------------------------------ snapshot workflow

FAKE_MVN_SNAPSHOT = r'''#!{python} -I
import json, os, sys
argv = sys.argv[1:]
entry = {"tool": "mvn", "argv": argv,
         "credentials": {k: bool(os.environ.get(k)) for k in ("SONATYPE_USERNAME", "SONATYPE_PASSWORD")}}
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
    fh.write(json.dumps(entry) + "\n")
if "help:evaluate" in argv:
    if os.environ.get("FAKE_EVAL_FAIL") == "1":
        sys.stderr.write("fake mvn: could not evaluate the project version\n")
        sys.exit(1)
    sys.stdout.write(os.environ.get("FAKE_PROJECT_VERSION", ""))
    sys.exit(0)
if "deploy" in argv:
    sys.exit(0)
sys.stderr.write("fake mvn: unexpected invocation %r\n" % argv)
sys.exit(96)
'''


class SnapshotFixture(object):
    def __init__(self, root):
        self.root = Path(root)
        for name in ("bin", "workspace", "runner", "home"):
            (self.root / name).mkdir()
        self.log = self.root / "fake-log.jsonl"
        make_executable(self.root / "bin" / "mvn", FAKE_MVN_SNAPSHOT)

    def env(self, version, eval_fail=False):
        env = {"PATH": "%s:%s" % (self.root / "bin", os.environ.get("PATH", "/usr/bin:/bin")),
               "HOME": str(self.root / "home"), "FAKE_LOG": str(self.log), "FAKE_PROJECT_VERSION": version,
               "RUNNER_TEMP": str(self.root / "runner"), "GITHUB_WORKSPACE": str(self.root / "workspace"),
               "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/alpha", "LANG": "C.UTF-8"}
        if eval_fail:
            env["FAKE_EVAL_FAIL"] = "1"
        return env

    def context(self):
        return {"secrets": {"SONATYPE_USERNAME": "fixture-user", "SONATYPE_PASSWORD": "fixture-password"},
                "github": {"ref": "refs/heads/alpha", "event_name": "push"}, "vars": {}}

    def run(self, version, eval_fail=False):
        workflow = load_workflow(SNAPSHOT_WORKFLOW)
        job = workflow["jobs"]["snapshot-publish"]
        return run_job(job, self.root / "workspace", self.env(version, eval_fail), self.context(),
                       self.root / "runner")

    def deploy_calls(self):
        return [e for e in read_log(self.log) if e["tool"] == "mvn" and "deploy" in e["argv"]]


class SnapshotPublishTests(unittest.TestCase):
    """A formal version on alpha is a successful non-publication; only a SNAPSHOT reaches the deploy."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="snapshot-wf-")
        self.fixture = SnapshotFixture(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_harness_reads_the_real_workflow_and_shadows_maven(self):
        workflow = load_workflow(SNAPSHOT_WORKFLOW)
        job = workflow["jobs"]["snapshot-publish"]
        self.assertGreaterEqual(len(job["steps"]), 3)
        deploy = step_named(job, "Deploy snapshot to Central")
        raw = SNAPSHOT_WORKFLOW.read_bytes().decode("utf-8").replace("\r\n", "\n")
        for line in deploy["run"].strip().splitlines():
            self.assertIn(line.strip(), raw)
        resolved = subprocess.run([BASH, "-c", "command -v mvn"], env=self.fixture.env("1.0.0-SNAPSHOT"),
                                  stdout=subprocess.PIPE).stdout.decode().strip()
        self.assertEqual(resolved, str(Path(self.tmp) / "bin" / "mvn"))

    def test_snapshot_version_selects_the_deploy(self):
        result = self.fixture.run("6.3.1-SNAPSHOT")
        self.assertEqual(result.status, "success", result.log)
        calls = self.fixture.deploy_calls()
        self.assertEqual(len(calls), 1, result.log)
        self.assertEqual(calls[0]["argv"], ["-B", "deploy", "-DskipTests"])
        self.assertEqual(calls[0]["credentials"], {"SONATYPE_USERNAME": True, "SONATYPE_PASSWORD": True})

    def test_formal_version_is_a_successful_nonpublication(self):
        result = self.fixture.run("6.3.0")
        self.assertEqual(result.status, "success", result.log)
        self.assertEqual(self.fixture.deploy_calls(), [], result.log)
        self.assertEqual(result.outcome("Deploy snapshot to Central"), "skipped", result.log)
        self.assertRegex(result.log, r"::notice::.*6\.3\.0.*no(thing)? .*publish", result.log)

    def test_malformed_or_empty_version_fails_without_deploy(self):
        for version in ("", "6.3", "6.3.0-RC1", "v6.3.0", "06.3.0", " 6.3.0", "6.3.0\n6.3.1",
                        "x-SNAPSHOT", "6.3-SNAPSHOT"):
            with self.subTest(version=version):
                if self.fixture.log.exists():
                    self.fixture.log.unlink()
                result = self.fixture.run(version)
                self.assertEqual(result.status, "failure", result.log)
                self.assertEqual(self.fixture.deploy_calls(), [], result.log)

    def test_unevaluable_version_fails_without_deploy(self):
        result = self.fixture.run("6.3.1-SNAPSHOT", eval_fail=True)
        self.assertEqual(result.status, "failure", result.log)
        self.assertEqual(self.fixture.deploy_calls(), [], result.log)

    def test_deploy_step_keeps_its_own_snapshot_guard(self):
        # Even if the step condition were removed, the deploy step itself refuses a formal version.
        job = load_workflow(SNAPSHOT_WORKFLOW)["jobs"]["snapshot-publish"]
        deploy = dict(step_named(job, "Deploy snapshot to Central"))
        deploy.pop("if", None)
        for version in ("6.3.0", "", "x-SNAPSHOT"):
            with self.subTest(version=version):
                if self.fixture.log.exists():
                    self.fixture.log.unlink()
                result = run_job({"steps": [deploy]}, Path(self.tmp) / "workspace", self.fixture.env(version),
                                 self.fixture.context(), Path(self.tmp) / "runner")
                self.assertEqual(result.status, "failure", result.log)
                self.assertEqual(self.fixture.deploy_calls(), [], result.log)

    def test_credentials_reach_only_the_conditioned_deploy_step(self):
        job = load_workflow(SNAPSHOT_WORKFLOW)["jobs"]["snapshot-publish"]
        holders = [s.get("name") for s in job["steps"]
                   if any("secrets." in str(v) for v in (s.get("env") or {}).values())
                   or "secrets." in str(s.get("with") or "")]
        self.assertEqual(holders, ["Deploy snapshot to Central"])
        deploy = step_named(job, "Deploy snapshot to Central")
        self.assertEqual(str(deploy.get("if", "")).replace(" ", ""), "steps.version.outputs.kind=='snapshot'")
        classify = [s for s in job["steps"] if s.get("id") == "version"]
        self.assertEqual(len(classify), 1)
        self.assertNotIn("secrets.", json.dumps(classify[0]))


# ------------------------------------------------------------------ retained release fixture

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    return sha256_bytes(Path(path).read_bytes())


def digests(folder):
    """Name -> SHA-256 of every entry in a directory (a link or subdirectory is recorded as such)."""
    result = {}
    for name in sorted(os.listdir(str(folder))):
        path = Path(folder) / name
        if path.is_symlink():
            result[name] = "link"
        elif path.is_dir():
            result[name] = "dir"
        else:
            result[name] = sha256_file(path)
    return result


def local_repository():
    """The local Maven repository holding the installed plugin. settings.xml is never read."""
    override = os.environ.get("MAVEN_REPO_LOCAL")
    return Path(override) if override else Path.home() / ".m2" / "repository"


def maven_home():
    if not REAL_MVN:
        return None
    home = Path(os.path.realpath(REAL_MVN)).parent.parent
    if not list((home / "lib").glob("maven-core*.jar")) or not list((home / "boot").glob("plexus-classworlds*.jar")):
        return None
    return home


def plugin_file(extension):
    group, artifact, version = PLUGIN_COORDS
    return local_repository().joinpath(*group.split(".")) / artifact / version / ("%s-%s.%s" % (
        artifact, version, extension))


def plugin_runtime_jars():
    """The plugin JAR plus each of its directly declared compile/runtime dependencies found locally."""
    jars = [plugin_file("jar")]
    root = ET.fromstring(plugin_file("pom").read_bytes())
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for dep in root.findall("m:dependencies/m:dependency", ns):
        scope = (dep.findtext("m:scope", "", ns) or "compile").strip()
        version = (dep.findtext("m:version", "", ns) or "").strip()
        if scope not in ("compile", "runtime") or not version or "$" in version:
            continue
        group = dep.findtext("m:groupId", "", ns).strip()
        artifact = dep.findtext("m:artifactId", "", ns).strip()
        jar = local_repository().joinpath(*group.split(".")) / artifact / version / ("%s-%s.jar" % (artifact, version))
        if jar.is_file():
            jars.append(jar)
    return jars


def blocked_reason():
    """Why the actual-plugin probe cannot run here, or None. A blocked probe fails; it is never skipped."""
    if maven_home() is None:
        return "BLOCKED: no Maven distribution with lib/maven-core*.jar and boot/plexus-classworlds*.jar on PATH"
    if not plugin_file("jar").is_file() or not plugin_file("pom").is_file():
        return "BLOCKED: %s:%s:%s is not in the local repository %s" % (PLUGIN_COORDS + (local_repository(),))
    if not shutil.which("javac"):
        return "BLOCKED: no javac on PATH"
    if not REAL_GPG:
        return "BLOCKED: no gpg on PATH"
    return None


def fixture_pom_bytes():
    """The real framework pom.xml at the release version; the retained POM is pom.xml at source_head."""
    raw = (REPO_ROOT / "pom.xml").read_bytes()
    pom, count = re.subn(rb"<version>[^<]+</version>", ("<version>%s</version>" % VERSION).encode(), raw, count=1)
    if count != 1:
        raise AssertionError("pom.xml has no project version to pin")
    return pom


def zip_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries:
            archive.writestr(zipfile.ZipInfo(name, date_time=(2026, 10, 19, 0, 0, 0)), data)
    return buffer.getvalue()


PLUGIN_YML = ("name: UltiTools\nversion: '%s'\nmain: com.ultikits.ultitools.UltiTools\napi-version: '1.19'\n"
              % VERSION).encode()

PRODUCT_FILES = (".github/scripts/release_artifacts.py", ".github/scripts/check-gpg-expiry.sh",
                 ".github/scripts/maven/RetainedArtifactsParticipant.java", ".github/scripts/maven/components.xml")


def gpg(home, *args):
    proc = subprocess.run([REAL_GPG, "--homedir", str(home), "--batch", "--no-tty"] + list(args),
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise AssertionError("gpg %s failed: %s" % (args[:2], proc.stderr.decode("utf-8", "replace")))
    return proc.stdout


def make_key(home, uid, expired=False):
    Path(home).mkdir(mode=0o700, parents=True, exist_ok=True)
    extra = ["--faked-system-time", "20200101T000000!"] if expired else []
    gpg(home, *(extra + ["--passphrase", "", "--pinentry-mode", "loopback", "--quick-gen-key", uid, "ed25519",
                         "sign", "1d" if expired else "1y"]))


def sign(home, path, uid=None, armor=True, output=None):
    args = ["--pinentry-mode", "loopback", "--passphrase", "", "--detach-sign", "--output",
            str(output or (str(path) + ".asc"))]
    if armor:
        args.insert(0, "--armor")
    if uid:
        args += ["--local-user", uid]
    gpg(home, *(args + [str(path)]))


def kill_agent(home):
    if shutil.which("gpgconf"):
        subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "all"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)


def flip_byte(path, offset=None):
    """Change one byte without changing the size."""
    data = bytearray(Path(path).read_bytes())
    if offset is None:
        offset = len(data) // 2
    data[offset] = (data[offset] + 1) % 256
    os.chmod(str(path), 0o644)
    Path(path).write_bytes(bytes(data))


def flip_pom_comment(path):
    """Change one letter inside the first XML comment, so the POM still parses but its bytes differ."""
    data = bytearray(Path(path).read_bytes())
    start = data.index(b"<!--") + 4
    for offset in range(start, data.index(b"-->", start)):
        if chr(data[offset]).isalpha():
            data[offset] = ord("Q") if data[offset] != ord("Q") else ord("Z")
            os.chmod(str(path), 0o644)
            Path(path).write_bytes(bytes(data))
            return
    raise AssertionError("the POM comment has no letter to change")


class ReleaseFixture(object):
    """A framework checkout at an accepted head, one retained candidate written by the real helper's
    ``retain`` and a throwaway signing key in a temporary GNUPGHOME."""

    SIGNER = "UltiTools Fixture Signing <signing@example.invalid>"
    OTHER = "Unrelated Fixture Key <other@example.invalid>"

    def __init__(self, root):
        self.root = Path(root)
        self.checkout = self.root / "checkout"
        self.retained = self.root / "retained"
        self.gnupg = self.root / "gnupg"
        self.home = self.root / "home"
        for path in (self.checkout, self.home):
            path.mkdir(parents=True)
        self.git("init", "-q", "-b", "main")
        self.git("remote", "add", "origin", "https://github.com/UltiKits/UltiTools-Reborn.git")
        (self.checkout / "README.md").write_text("fixture\n", encoding="utf-8")
        self.git("add", "README.md")
        self.git("commit", "-q", "-m", "base")
        self.git("tag", "v" + PRIOR)
        self.base_head = self.git("rev-parse", "HEAD")
        (self.checkout / ".gitignore").write_text("target/\n", encoding="utf-8")
        (self.checkout / "pom.xml").write_bytes(fixture_pom_bytes())
        # The workflow runs scripts from its checkout. A product file that does not exist yet is copied as
        # missing, so the run itself fails instead of the fixture.
        for relative in PRODUCT_FILES:
            source = REPO_ROOT / relative
            if source.is_file():
                target = self.checkout / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(str(source), str(target))
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "candidate")
        self.head = self.git("rev-parse", "HEAD")
        target = self.checkout / "target"
        target.mkdir()
        (target / PAYLOADS["primary"]).write_bytes(zip_bytes([
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"), ("plugin.yml", PLUGIN_YML),
            ("com/ultikits/ultitools/UltiTools.class", b"\xca\xfe\xba\xbe accepted")]))
        (target / PAYLOADS["sources"]).write_bytes(zip_bytes([
            ("com/ultikits/ultitools/UltiTools.java", b"package com.ultikits.ultitools;\nclass UltiTools {}\n")]))
        (target / PAYLOADS["javadoc"]).write_bytes(zip_bytes([("index.html", b"<html></html>")]))
        proc = subprocess.run([sys.executable, "-I", str(HELPER), "retain", "--source-checkout", str(self.checkout),
                               "--source-head", self.head, "--version", VERSION, "--retain-dir", str(self.retained)],
                              env=self.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise AssertionError("retain failed: %s" % proc.stderr.decode("utf-8", "replace"))
        self.git("tag", TAG)
        make_key(self.gnupg, self.SIGNER)
        make_key(self.gnupg, self.OTHER)

    def env(self, **extra):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.home),
               "GNUPGHOME": str(self.gnupg), "LANG": "C.UTF-8", "GIT_CONFIG_NOSYSTEM": "1"}
        if os.environ.get("JAVA_HOME"):
            env["JAVA_HOME"] = os.environ["JAVA_HOME"]
        env.update(extra)
        return env

    def git(self, *args):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.home), "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "f@example.invalid", "GIT_COMMITTER_NAME": "Fixture",
               "GIT_COMMITTER_EMAIL": "f@example.invalid"}
        proc = subprocess.run([REAL_GIT, "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false",
                               "-c", "core.hooksPath=/dev/null", "-C", str(self.checkout)] + list(args),
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise AssertionError("git %s failed: %s" % (args, proc.stderr.decode("utf-8", "replace")))
        return proc.stdout.decode("utf-8").strip()

    def make_inputs(self, dest, signed=True):
        """What a publish job downloads from the release: the five retained assets, writable, then signed."""
        dest = Path(dest)
        dest.mkdir(parents=True)
        for name in list(PAYLOADS.values()) + [MANIFEST]:
            shutil.copyfile(str(self.retained / name), str(dest / name))
        if signed:
            for name in SIGNED:
                sign(self.gnupg, dest / name, uid=self.SIGNER)
        return dest

    def central_inputs(self, inputs, work, scratch, **overrides):
        args = {"--manifest": str(Path(inputs) / MANIFEST), "--source-checkout": str(self.checkout),
                "--expected-source-head": self.head, "--tag": TAG, "--maven-home": str(maven_home() or ""),
                "--work-dir": str(work), "--scratch-dir": str(scratch)}
        args.update(overrides)
        argv = [sys.executable, "-I", str(HELPER), "central-inputs"]
        for key, value in args.items():
            argv += [key, value]
        proc = subprocess.run(argv + ["--shell"], env=self.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=300)
        values = {}
        for line in proc.stdout.decode("utf-8", "replace").splitlines():
            key, sep, value = line.partition("=")
            if sep:
                values[key] = value
        return proc, values

    def close(self):
        kill_agent(self.gnupg)


def read_properties(path):
    values = {}
    for line in Path(path).read_text(encoding="ascii").splitlines():
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            values[key] = value
    return values


# The registrar entry layout: (role, type, classifier, file name).
LAYOUT = (("pom", "pom", "", PAYLOADS["pom"]), ("primary", "jar", "", PAYLOADS["primary"]),
          ("sources", "jar", "sources", PAYLOADS["sources"]), ("javadoc", "jar", "javadoc", PAYLOADS["javadoc"]),
          ("pom-signature", "pom.asc", "", PAYLOADS["pom"] + ".asc"),
          ("primary-signature", "jar.asc", "", PAYLOADS["primary"] + ".asc"),
          ("sources-signature", "jar.asc", "sources", PAYLOADS["sources"] + ".asc"),
          ("javadoc-signature", "jar.asc", "javadoc", PAYLOADS["javadoc"] + ".asc"))


# ------------------------------------------------------------------ central-inputs

class CentralInputsTests(unittest.TestCase):
    """release_artifacts.py central-inputs validates the retained files and signatures, then writes the private
    registrar list and the ephemeral helper JAR, and refuses any mismatch before creating anything."""

    @classmethod
    def setUpClass(cls):
        reason = blocked_reason()
        if reason:
            raise AssertionError(reason)
        cls.tmp = tempfile.mkdtemp(prefix="central-inputs-")
        cls.fixture = ReleaseFixture(Path(cls.tmp) / "fixture")

    @classmethod
    def tearDownClass(cls):
        cls.fixture.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def case_dir(self, name):
        path = Path(self.tmp) / ("case-%s-%s" % (self._testMethodName, name))
        path.mkdir()
        return path

    def test_valid_inputs_yield_the_registrar_list_and_helper_jar_without_touching_inputs(self):
        case = self.case_dir("ok")
        inputs = self.fixture.make_inputs(case / "inputs")
        before = digests(inputs)
        work, scratch = case / "work", case / "scratch"
        proc, values = self.fixture.central_inputs(inputs, work, scratch)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode("utf-8", "replace"))
        self.assertEqual(sorted(values), ["DEFERRED_DIR", "HELPER_JAR", "HELPER_JAR_SHA256", "OUTPUT_DIR",
                                          "PROPERTIES", "RETAINED_POM", "SIGNER_FINGERPRINT", "STAGING_DIR",
                                          "VERSION"])
        self.assertEqual(values["RETAINED_POM"], str(inputs / PAYLOADS["pom"]))
        self.assertEqual(values["VERSION"], VERSION)
        self.assertEqual([values["STAGING_DIR"], values["DEFERRED_DIR"], values["OUTPUT_DIR"]],
                         [str(scratch / "staging"), str(scratch / "deferred"), str(scratch / "output")])
        self.assertEqual(os.listdir(str(scratch)), [])
        self.assertEqual(stat.S_IMODE(work.stat().st_mode), 0o700)
        props = read_properties(values["PROPERTIES"])
        self.assertEqual(stat.S_IMODE(Path(values["PROPERTIES"]).stat().st_mode), 0o600)
        self.assertEqual(props["format"], "ultitools-retained-artifacts/1")
        self.assertEqual((props["group_id"], props["artifact_id"], props["version"], props["packaging"],
                          props["count"]), ("com.ultikits", "UltiTools-API", VERSION, "jar", "8"))
        self.assertEqual(len(props), 6 + 8 * 6)
        for index, (role, kind, classifier, name) in enumerate(LAYOUT, 1):
            prefix = "artifact.%d." % index
            self.assertEqual((props[prefix + "role"], props[prefix + "type"], props[prefix + "classifier"]),
                             (role, kind, classifier))
            self.assertEqual(props[prefix + "file"], str(inputs / name))
            data = (inputs / name).read_bytes()
            self.assertEqual((props[prefix + "bytes"], props[prefix + "sha256"]), (str(len(data)), sha256_bytes(data)))
        manifest = json.loads((inputs / MANIFEST).read_text(encoding="ascii"))
        accepted = dict((a["filename"], a["sha256"]) for a in manifest["artifacts"])
        for index, (_, _, _, name) in enumerate(LAYOUT, 1):
            if name in accepted:
                self.assertEqual(props["artifact.%d.sha256" % index], accepted[name])
        jar = Path(values["HELPER_JAR"])
        self.assertEqual(sha256_file(jar), values["HELPER_JAR_SHA256"])
        self.assertEqual(os.path.commonpath([str(jar), str(work)]), str(work))
        with zipfile.ZipFile(str(jar)) as archive:
            names = sorted(n for n in archive.namelist() if not n.endswith("/"))
            self.assertEqual(names, ["META-INF/plexus/components.xml",
                                     "com/ultikits/release/RetainedArtifactsParticipant.class"])
            self.assertEqual(archive.read("META-INF/plexus/components.xml"), REGISTRAR_DESCRIPTOR.read_bytes())
        self.assertEqual(digests(inputs), before)
        self.assertRegex(values["SIGNER_FINGERPRINT"], r"^[0-9A-F]{40,64}$")

    def test_each_refusal_happens_before_anything_is_created(self):
        fixture = self.fixture

        def remove_signature(c):
            os.unlink(str(c["inputs"] / (PAYLOADS["sources"] + ".asc")))

        def extra_file(c):
            (c["inputs"] / "notes.txt").write_text("x", encoding="ascii")

        def wrong_signature(c):
            shutil.copyfile(str(c["inputs"] / (PAYLOADS["pom"] + ".asc")), str(c["inputs"] / (PAYLOADS["primary"] + ".asc")))

        def other_signer(c):
            os.unlink(str(c["inputs"] / (PAYLOADS["javadoc"] + ".asc")))
            sign(fixture.gnupg, c["inputs"] / PAYLOADS["javadoc"], uid=ReleaseFixture.OTHER)

        def binary_signature(c):
            os.unlink(str(c["inputs"] / (PAYLOADS["primary"] + ".asc")))
            sign(fixture.gnupg, c["inputs"] / PAYLOADS["primary"], uid=ReleaseFixture.SIGNER, armor=False)

        def payload_byte(c):
            flip_byte(c["inputs"] / PAYLOADS["primary"])

        def pom_byte(c):
            flip_pom_comment(c["inputs"] / PAYLOADS["pom"])

        def head_mismatch(c):
            c["args"]["--expected-source-head"] = fixture.base_head

        def tag_version(c):
            c["args"]["--tag"] = "v" + PRIOR

        def scratch_inside_inputs(c):
            c["args"]["--scratch-dir"] = str(c["inputs"] / "scratch")

        def work_is_scratch(c):
            c["args"]["--work-dir"] = c["args"]["--scratch-dir"]

        def work_inside_inputs(c):
            c["args"]["--work-dir"] = str(c["inputs"] / "work")

        def scratch_exists(c):
            Path(c["args"]["--scratch-dir"]).mkdir()

        def linked_inputs(c):
            link = c["case"] / "linked"
            os.symlink(str(c["inputs"]), str(link))
            c["args"]["--manifest"] = str(link / MANIFEST)

        def maven_config(c):
            (c["case"] / ".mvn").mkdir()

        def no_maven(c):
            empty = c["case"] / "not-maven"
            empty.mkdir()
            c["args"]["--maven-home"] = str(empty)

        def linked_payload(c):
            target = c["case"] / "elsewhere.jar"
            shutil.copyfile(str(c["inputs"] / PAYLOADS["javadoc"]), str(target))
            os.unlink(str(c["inputs"] / PAYLOADS["javadoc"]))
            os.symlink(str(target), str(c["inputs"] / PAYLOADS["javadoc"]))

        cases = (("remove_signature", remove_signature, "inputs_listing"),
                 ("extra_file", extra_file, "inputs_listing"),
                 ("wrong_signature", wrong_signature, "signature_invalid"),
                 ("other_signer", other_signer, "signature_signer"),
                 ("binary_signature", binary_signature, "signature_format"),
                 ("payload_byte", payload_byte, "sha256_mismatch"),
                 ("pom_byte", pom_byte, "sha256_mismatch"),
                 ("head_mismatch", head_mismatch, "source_head_mismatch"),
                 ("tag_version", tag_version, "tag_mismatch"),
                 ("scratch_inside_inputs", scratch_inside_inputs, "path_overlap"),
                 ("work_is_scratch", work_is_scratch, "path_overlap"),
                 ("work_inside_inputs", work_inside_inputs, "path_overlap"),
                 ("scratch_exists", scratch_exists, "scratch_dir_exists"),
                 ("linked_inputs", linked_inputs, "path_link"),
                 ("maven_config", maven_config, "maven_config_dir"),
                 ("no_maven", no_maven, "maven_home"),
                 ("linked_payload", linked_payload, "file_not_regular"))
        for name, mutate, code in cases:
            with self.subTest(case=name):
                case = self.case_dir(name)
                inputs = fixture.make_inputs(case / "inputs")
                c = {"case": case, "inputs": inputs, "args": {"--work-dir": str(case / "work"),
                                                              "--scratch-dir": str(case / "scratch")}}
                mutate(c)
                existed = dict((k, os.path.lexists(c["args"][k])) for k in ("--work-dir", "--scratch-dir"))
                work, scratch = c["args"].pop("--work-dir"), c["args"].pop("--scratch-dir")
                proc, values = fixture.central_inputs(inputs, work, scratch, **c["args"])
                stderr = proc.stderr.decode("utf-8", "replace")
                self.assertEqual(proc.returncode, 1, stderr)
                self.assertIn("refused: %s" % code, stderr)
                self.assertEqual(values, {})
                if not existed["--work-dir"]:
                    self.assertFalse(os.path.lexists(work), "the work directory was created")
                if not existed["--scratch-dir"]:
                    self.assertFalse(os.path.lexists(scratch), "the scratch directory was created")

    def test_tag_at_another_commit_is_refused(self):
        case = self.case_dir("tag")
        inputs = self.fixture.make_inputs(case / "inputs")
        self.fixture.git("tag", "-f", TAG, self.fixture.base_head)
        try:
            proc, _ = self.fixture.central_inputs(inputs, case / "work", case / "scratch")
        finally:
            self.fixture.git("tag", "-f", TAG, self.fixture.head)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("refused: tag_mismatch", proc.stderr.decode("utf-8", "replace"))
        self.assertFalse(os.path.lexists(str(case / "work")))

    def test_recheck_confirms_unchanged_inputs_and_refuses_a_later_change(self):
        case = self.case_dir("recheck")
        inputs = self.fixture.make_inputs(case / "inputs")
        proc, values = self.fixture.central_inputs(inputs, case / "work", case / "scratch")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode("utf-8", "replace"))
        argv = [sys.executable, "-I", str(HELPER), "central-recheck", "--manifest", str(inputs / MANIFEST),
                "--properties", values["PROPERTIES"]]
        ok = subprocess.run(argv, env=self.fixture.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(ok.returncode, 0, ok.stderr.decode("utf-8", "replace"))
        self.assertIn("RECHECK=ok", ok.stdout.decode("ascii"))
        flip_byte(inputs / (PAYLOADS["sources"] + ".asc"), offset=40)
        bad = subprocess.run(argv, env=self.fixture.env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("refused: sha256_mismatch", bad.stderr.decode("utf-8", "replace"))


# ------------------------------------------------------------------ actual plugin probe

PROBE_SOURCE = r'''
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.sonatype.central.publisher.plugin.PublishMojo;
import org.sonatype.central.publisher.plugin.bundler.ArtifactBundler;
import org.sonatype.central.publisher.plugin.model.ArtifactWithFile;
import org.sonatype.central.publisher.plugin.model.BundleArtifactRequest;
import org.sonatype.central.publisher.plugin.model.ChecksumRequest;
import org.sonatype.central.publisher.plugin.stager.ArtifactStager;
import org.sonatype.central.publisher.plugin.utils.MojoUtils;
import org.sonatype.central.publisher.plugin.utils.ProjectUtils;

/**
 * Drives the real central-publishing-maven-plugin 0.9.0 components to a LOCAL file boundary. The mojo's
 * publisher client, uploader, watcher and published-checker stay null, so an attempt to reach the network
 * fails with a NullPointerException instead of opening a connection. Arguments are quadruples of
 * mode (handoff | unregistered | register-only), properties file or "-", POM file and scratch directory;
 * each scenario gets a fresh MavenSession and MavenProject inside one Plexus container.
 */
public class CentralHandoffProbe {

    /** Exposes the real protected PublishMojo methods; nothing is overridden. */
    public static class ExposedPublishMojo extends PublishMojo {
        public File workDirectory(File forced, String relative) {
            return getWorkDirectory(forced, relative);
        }

        public List<ArtifactWithFile> artifacts() throws Exception {
            return getArtifactWithFiles();
        }

        public void release(List<ArtifactWithFile> artifacts, File staging) throws Exception {
            processRelease(artifacts, staging);
        }
    }

    public static void main(String[] args) throws Exception {
        ContainerConfiguration cc = new DefaultContainerConfiguration()
                .setClassPathScanning(PlexusConstants.SCANNING_INDEX).setAutoWiring(true).setName("probe");
        DefaultPlexusContainer container = new DefaultPlexusContainer(cc);
        int errors = 0;
        for (int i = 0; i + 4 <= args.length; i += 4) {
            System.out.println("@@ SCENARIO " + (i / 4) + " " + args[i]);
            try {
                run(container, args[i], args[i + 1], new File(args[i + 2]), new File(args[i + 3]));
            } catch (Exception e) {
                errors++;
                System.out.println("RESULT ERROR " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        container.dispose();
        System.exit(errors == 0 ? 0 : 3);
    }

    static void run(DefaultPlexusContainer container, String mode, String properties, File pomFile, File scratch)
            throws Exception {
        Model model;
        try (Reader reader = Files.newBufferedReader(pomFile.toPath())) {
            model = new MavenXpp3Reader().read(reader);
        }
        MavenProject project = new MavenProject(model);
        project.setFile(pomFile);
        ArtifactFactory artifactFactory = container.lookup(ArtifactFactory.class);
        project.setArtifact(artifactFactory.createBuildArtifact(model.getGroupId(), model.getArtifactId(),
                model.getVersion(), model.getPackaging()));

        RepositorySystem repositorySystem = container.lookup(RepositorySystem.class);
        DefaultRepositorySystemSession repositorySession = MavenRepositorySystemUtils.newSession();
        repositorySession.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(repositorySession,
                new LocalRepository(new File(scratch.getParentFile(), scratch.getName() + "-unused-local-repo"))));
        repositorySession.setOffline(true);
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        Properties user = new Properties();
        if (!"-".equals(properties)) {
            user.setProperty("retainedArtifacts.properties", properties);
        }
        request.setUserProperties(user);
        request.setOffline(true);
        @SuppressWarnings("deprecation")
        MavenSession session = new MavenSession(container, repositorySession, request, new DefaultMavenExecutionResult());
        session.setProjects(Collections.singletonList(project));
        session.setCurrentProject(project);
        container.lookup(LegacySupport.class).setSession(session);

        if (!"unregistered".equals(mode)) {
            AbstractMavenLifecycleParticipant registrar =
                    container.lookup(AbstractMavenLifecycleParticipant.class, "retained-artifacts");
            System.out.println("REGISTRAR " + registrar.getClass().getName());
            try {
                registrar.afterProjectsRead(session);
            } catch (Exception e) {
                System.out.println("RESULT REGISTRAR_REFUSED " + e.getMessage());
                return;
            }
            if ("register-only".equals(mode)) {
                System.out.println("RESULT REGISTERED " + project.getAttachedArtifacts().size());
                return;
            }
        }

        ExposedPublishMojo mojo = new ExposedPublishMojo();
        MojoDescriptor descriptor = new MojoDescriptor();
        descriptor.setGoal("publish");
        set(mojo, "mavenSession", session);
        set(mojo, "mojoExecution", new MojoExecution(descriptor, "default-cli"));
        set(mojo, "artifactFactory", artifactFactory);
        set(mojo, "mojoUtils", container.lookup(MojoUtils.class));
        set(mojo, "projectUtils", container.lookup(ProjectUtils.class));
        set(mojo, "pluginGroupId", "org.sonatype.central");
        set(mojo, "pluginArtifactId", "central-publishing-maven-plugin");
        set(mojo, "pluginVersion", "0.9.0");
        set(mojo, "checksums", "all");
        set(mojo, "waitUntil", "published");
        set(mojo, "autoPublish", Boolean.TRUE);
        set(mojo, "waitMaxTime", Integer.valueOf(1800));
        set(mojo, "waitPollingInterval", Integer.valueOf(5));
        set(mojo, "publishCompletionPollInterval", Integer.valueOf(1000));
        set(mojo, "outputFilename", "central-bundle.zip");
        set(mojo, "excludeArtifacts", new ArrayList<String>());
        ArtifactStager stager = container.lookup(ArtifactStager.class);
        ArtifactBundler bundler = container.lookup(ArtifactBundler.class);
        set(mojo, "artifactStager", stager);
        set(mojo, "artifactBundler", bundler);
        mojo.validateParameters();

        File deferred = mojo.workDirectory(new File(scratch, "deferred"), "central-deferred");
        File staging = mojo.workDirectory(new File(scratch, "staging"), "central-staging");
        File output = mojo.workDirectory(new File(scratch, "output"), "central-publishing");
        for (File dir : new File[] {staging, deferred, output}) {
            String[] left = dir.list();
            System.out.println("WORKDIR " + dir.getName() + " " + (left == null ? -1 : left.length));
        }
        List<ArtifactWithFile> artifacts;
        try {
            artifacts = mojo.artifacts();
        } catch (Exception e) {
            System.out.println("RESULT COLLECT_REFUSED " + e.getMessage());
            return;
        }
        for (ArtifactWithFile a : artifacts) {
            Artifact artifact = a.getArtifact();
            System.out.println("COLLECTED " + artifact.getType() + " " + artifact.getClassifier() + " "
                    + a.getFile().getAbsolutePath());
        }
        mojo.release(artifacts, staging);
        Path bundle = bundler.bundle(new BundleArtifactRequest(project, staging, output, "central-bundle.zip",
                ChecksumRequest.ALL));
        try (Stream<Path> walk = Files.walk(staging.toPath())) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                System.out.println("STAGED " + staging.toPath().relativize(p) + " " + sha256(p.toFile()));
            }
        }
        List<String> lines = new ArrayList<String>();
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        lines.add("BUNDLED " + entry.getName() + " " + sha256(in));
                    }
                }
            }
        }
        Collections.sort(lines);
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println("RESULT STAGED_AND_BUNDLED " + bundle.toAbsolutePath());
    }

    static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // the field is declared on a superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    static String sha256(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return sha256(in);
        }
    }

    static String sha256(InputStream in) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) {
            sha.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
'''

STAGED_DIR = "com/ultikits/UltiTools-API/%s/" % VERSION
CHECKSUM_EXTENSIONS = (".md5", ".sha1", ".sha256", ".sha512")


def run_maven_respecting_gate(argv, env, cwd):
    """Run the real Maven; a local memory gate may answer 75 (retry later), which is retried, never ignored."""
    for _ in range(20):
        proc = subprocess.run(argv, env=env, cwd=str(cwd), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=600)
        if proc.returncode != 75:
            return proc
        import time
        time.sleep(15)
    raise AssertionError("BLOCKED: the local Maven memory gate kept answering 75")


class ActualCentralHandoffTests(unittest.TestCase):
    """The real plugin, the real registrar and the real Maven installer at a local, non-publishing boundary."""

    @classmethod
    def setUpClass(cls):
        reason = blocked_reason()
        if reason:
            raise AssertionError(reason)
        cls.tmp = tempfile.mkdtemp(prefix="central-probe-")
        root = Path(cls.tmp)
        cls.fixture = ReleaseFixture(root / "fixture")
        home = maven_home()
        cls.maven_lib = [str(p) for p in sorted((home / "lib").glob("*.jar"))] + \
            [str(p) for p in sorted((home / "boot").glob("*.jar"))]
        cls.plugin_jars = [str(p) for p in plugin_runtime_jars()]
        source_dir, cls.probe_classes = root / "probe-src", root / "probe-classes"
        source_dir.mkdir()
        cls.probe_classes.mkdir()
        (source_dir / "CentralHandoffProbe.java").write_text(PROBE_SOURCE, encoding="utf-8")
        compile_proc = subprocess.run(["javac", "-proc:none", "-nowarn", "-cp",
                                       os.pathsep.join(cls.maven_lib + cls.plugin_jars), "-d", str(cls.probe_classes),
                                       str(source_dir / "CentralHandoffProbe.java")],
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if compile_proc.returncode != 0:
            raise AssertionError("BLOCKED: the probe does not compile against the installed plugin and Maven "
                                 "interfaces:\n" + compile_proc.stdout.decode("utf-8", "replace"))

    @classmethod
    def tearDownClass(cls):
        cls.fixture.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def case_dir(self, name):
        path = Path(self.tmp) / ("case-%s-%s" % (self._testMethodName, name))
        path.mkdir()
        return path

    def prepare(self, inputs, case, label):
        work, scratch = case / ("work-" + label), case / ("scratch-" + label)
        proc, values = self.fixture.central_inputs(inputs, work, scratch)
        self.assertEqual(proc.returncode, 0, "central-inputs refused valid retained inputs: "
                         + proc.stderr.decode("utf-8", "replace"))
        return values

    def probe(self, scenarios, helper_jar):
        classpath = os.pathsep.join([str(self.probe_classes), helper_jar] + self.maven_lib + self.plugin_jars)
        argv = ["java", "-cp", classpath, "CentralHandoffProbe"]
        for scenario in scenarios:
            argv += [str(part) for part in scenario]
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300,
                              env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "LANG": "C.UTF-8"})
        text = proc.stdout.decode("utf-8", "replace")
        results, current = [], None
        for line in text.splitlines():
            if line.startswith("@@ SCENARIO "):
                current = {"lines": [], "result": None}
                results.append(current)
            elif current is not None:
                current["lines"].append(line)
                if line.startswith("RESULT "):
                    current["result"] = line[len("RESULT "):]
        self.assertEqual(len(results), len(scenarios), text)
        return results, text

    @staticmethod
    def tagged(result, tag):
        return [line[len(tag) + 1:] for line in result["lines"] if line.startswith(tag + " ")]

    def assert_handoff(self, result, inputs, scratch, text):
        self.assertTrue(result["result"] and result["result"].startswith("STAGED_AND_BUNDLED "), text)
        self.assertIn("REGISTRAR com.ultikits.release.RetainedArtifactsParticipant", result["lines"])
        # First-project initialization cleaned every seeded work directory.
        self.assertEqual(sorted(self.tagged(result, "WORKDIR")), ["deferred 0", "output 0", "staging 0"], text)
        for sub in ("staging", "deferred", "output"):
            self.assertFalse((scratch / sub / "SENTINEL").exists(), "%s sentinel survived" % sub)
        collected = sorted(self.tagged(result, "COLLECTED"))
        expected = sorted(["jar null %s" % (inputs / PAYLOADS["primary"]),
                           "jar sources %s" % (inputs / PAYLOADS["sources"]),
                           "jar javadoc %s" % (inputs / PAYLOADS["javadoc"]),
                           "pom.asc null %s" % (inputs / (PAYLOADS["pom"] + ".asc")),
                           "jar.asc null %s" % (inputs / (PAYLOADS["primary"] + ".asc")),
                           "jar.asc sources %s" % (inputs / (PAYLOADS["sources"] + ".asc")),
                           "jar.asc javadoc %s" % (inputs / (PAYLOADS["javadoc"] + ".asc"))])
        self.assertEqual(collected, expected, text)
        staged = dict(line.rsplit(" ", 1) for line in self.tagged(result, "STAGED"))
        retained = dict((STAGED_DIR + name, sha256_file(inputs / name)) for name in
                        list(PAYLOADS.values()) + [n + ".asc" for n in SIGNED])
        checksums = set(STAGED_DIR + name + ext for name in PAYLOADS.values() for ext in CHECKSUM_EXTENSIONS)
        self.assertEqual(set(staged), set(retained) | checksums, text)
        for name, digest in retained.items():
            self.assertEqual(staged[name], digest, "%s is not byte-equal to the retained file" % name)
        for name in PAYLOADS.values():
            sidecar = (scratch / "staging" / (STAGED_DIR + name + ".sha256")).read_text(encoding="ascii").strip()
            self.assertEqual(sidecar, retained[STAGED_DIR + name])
        bundled = dict(line.rsplit(" ", 1) for line in self.tagged(result, "BUNDLED"))
        self.assertEqual(bundled, staged, "the bundle differs from the staged repository layout")

    def test_actual_plugin_handoff_cleans_scratch_keeps_inputs_and_retries(self):
        case = self.case_dir("handoff")
        inputs = self.fixture.make_inputs(case / "inputs")
        before = digests(inputs)
        first = self.prepare(inputs, case, "first")
        retry = self.prepare(inputs, case, "retry")   # a rerun: fresh work and scratch, same retained inputs
        scratches = [Path(first["STAGING_DIR"]).parent, Path(retry["STAGING_DIR"]).parent]
        for scratch in scratches:
            for sub in ("staging", "deferred", "output"):
                (scratch / sub).mkdir()
                (scratch / sub / "SENTINEL").write_text("seeded before the publisher ran\n", encoding="ascii")
        results, text = self.probe([("handoff", first["PROPERTIES"], first["RETAINED_POM"], scratches[0]),
                                    ("handoff", retry["PROPERTIES"], retry["RETAINED_POM"], scratches[1])],
                                   first["HELPER_JAR"])
        for result, scratch in zip(results, scratches):
            self.assert_handoff(result, inputs, scratch, text)
        self.assertEqual(digests(inputs), before, "a retained input or signature changed or disappeared")
        print("ACTUAL_CENTRAL_HANDOFF_OK")
        sys.stdout.flush()

    def test_unregistered_project_is_refused_by_the_actual_collector(self):
        case = self.case_dir("unregistered")
        inputs = self.fixture.make_inputs(case / "inputs")
        before = digests(inputs)
        values = self.prepare(inputs, case, "control")
        scratch = Path(values["STAGING_DIR"]).parent
        results, text = self.probe([("unregistered", "-", values["RETAINED_POM"], scratch)], values["HELPER_JAR"])
        self.assertEqual(results[0]["result"],
                         "COLLECT_REFUSED The packaging for this project did not assign a file to the build artifact",
                         text)
        self.assertEqual(digests(inputs), before)
        staged = [p for p in (scratch / "staging").rglob("*") if p.is_file()]
        self.assertEqual(staged, [])

    def test_registrar_refuses_mutations_links_and_incomplete_lists(self):
        case = self.case_dir("mutations")
        inputs = self.fixture.make_inputs(case / "inputs")
        values = self.prepare(inputs, case, "base")
        good = Path(values["PROPERTIES"]).read_text(encoding="ascii")
        pom = values["RETAINED_POM"]
        scenarios, expected = [], []

        def add(label, properties, pom_file, code):
            scratch = case / ("scratch-" + label)
            scenarios.append(("register-only", properties, pom_file, scratch))
            expected.append((label, code))

        def copy_inputs(label):
            folder = case / ("copy-" + label)
            shutil.copytree(str(inputs), str(folder))
            for name in os.listdir(str(folder)):
                os.chmod(str(folder / name), 0o644)
            props = case / ("props-" + label)
            props.write_text(good.replace(str(inputs) + "/", str(folder) + "/"), encoding="ascii")
            return folder, props

        for index, (role, _, _, name) in enumerate(LAYOUT, 1):
            folder, props = copy_inputs("byte-%d" % index)
            if role == "pom":
                flip_pom_comment(folder / name)
            else:
                flip_byte(folder / name)
            add("byte-%s" % role, props, folder / PAYLOADS["pom"], "sha256_mismatch")
        folder, props = copy_inputs("short")
        data = (folder / (PAYLOADS["javadoc"] + ".asc")).read_bytes()
        (folder / (PAYLOADS["javadoc"] + ".asc")).write_bytes(data[:-1])
        add("short", props, folder / PAYLOADS["pom"], "bytes_mismatch")
        folder, props = copy_inputs("link")
        shutil.copyfile(str(folder / PAYLOADS["primary"]), str(case / "primary-elsewhere.jar"))
        os.unlink(str(folder / PAYLOADS["primary"]))
        os.symlink(str(case / "primary-elsewhere.jar"), str(folder / PAYLOADS["primary"]))
        add("link", props, folder / PAYLOADS["pom"], "entry_file")
        extra = case / "props-extra"
        extra.write_text(good + "unexpected=1\n", encoding="ascii")
        add("extra-key", extra, pom, "properties_keys")
        missing = case / "props-missing"
        missing.write_text("".join(l + "\n" for l in good.splitlines() if not l.startswith("artifact.8.sha256=")),
                           encoding="ascii")
        add("missing-key", missing, pom, "properties_keys")
        other_version = case / "props-version"
        other_version.write_text(good.replace("version=%s\n" % VERSION, "version=6.3.1\n"), encoding="ascii")
        add("version", other_version, pom, "properties_identity")
        elsewhere = case / "elsewhere"
        elsewhere.mkdir()
        shutil.copyfile(str(inputs / LAYOUT[7][3]), str(elsewhere / LAYOUT[7][3]))
        split = case / "props-split"
        split.write_text(good.replace("artifact.8.file=%s/" % inputs, "artifact.8.file=%s/" % elsewhere),
                         encoding="ascii")
        add("folder", split, pom, "entry_folder")
        foreign_pom_dir = case / "foreign-pom"
        foreign_pom_dir.mkdir()
        shutil.copyfile(pom, str(foreign_pom_dir / PAYLOADS["pom"]))
        add("pom-not-project-file", values["PROPERTIES"], foreign_pom_dir / PAYLOADS["pom"], "pom_not_project_file")
        add("unset", "-", pom, "properties_missing")
        snapshot_dir = case / "snapshot-pom"
        snapshot_dir.mkdir()
        (snapshot_dir / PAYLOADS["pom"]).write_bytes(Path(pom).read_bytes().replace(
            ("<version>%s</version>" % VERSION).encode(), ("<version>%s-SNAPSHOT</version>" % VERSION).encode(), 1))
        add("snapshot-project", values["PROPERTIES"], snapshot_dir / PAYLOADS["pom"], "project_identity")
        linked = case / "props-link"
        os.symlink(values["PROPERTIES"], str(linked))
        add("linked-list", linked, pom, "properties_file")
        add("control-valid", values["PROPERTIES"], pom, None)

        results, text = self.probe(scenarios, values["HELPER_JAR"])
        for (label, code), result in zip(expected, results):
            with self.subTest(case=label):
                if code is None:
                    self.assertEqual(result["result"], "REGISTERED 6", text)
                else:
                    self.assertTrue(result["result"] and result["result"].startswith(
                        "REGISTRAR_REFUSED retained-artifacts refused: %s:" % code), "%s: %s" % (label, result))

    def test_real_maven_loads_the_registrar_as_a_core_extension_and_stops_offline(self):
        case = self.case_dir("cli")
        inputs = self.fixture.make_inputs(case / "inputs")
        before = digests(inputs)
        values = self.prepare(inputs, case, "cli")
        scratch = Path(values["STAGING_DIR"]).parent
        (scratch / "staging").mkdir()
        (scratch / "staging" / "SENTINEL").write_text("the mojo never ran\n", encoding="ascii")
        settings = case / "empty-settings.xml"
        settings.write_text("<settings/>\n", encoding="ascii")

        def maven(properties, helper_jar, pom):
            argv = [REAL_MVN, "-B", "-o", "-s", str(settings), "-Dmaven.repo.local=%s" % local_repository(),
                    "-f", str(pom), "-Dmaven.ext.class.path=%s" % helper_jar,
                    "-DretainedArtifacts.properties=%s" % properties,
                    "-DstagingDirectory=%s" % values["STAGING_DIR"], "-DdeferredDirectory=%s" % values["DEFERRED_DIR"],
                    "-DoutputDirectory=%s" % values["OUTPUT_DIR"], "-DcentralBaseUrl=https://127.0.0.1:9",
                    "%s:%s:%s:publish" % PLUGIN_COORDS, "-DpublishingServerId=ultikits-sonatype",
                    "-DautoPublish=true", "-DwaitUntil=published", "-Dchecksums=all"]
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(Path.home()), "LANG": "C.UTF-8"}
            if os.environ.get("JAVA_HOME"):
                env["JAVA_HOME"] = os.environ["JAVA_HOME"]
            return run_maven_respecting_gate(argv, env, case)

        accepted = maven(values["PROPERTIES"], values["HELPER_JAR"], values["RETAINED_POM"])
        out = accepted.stdout.decode("utf-8", "replace")
        self.assertIn("[retained-artifacts] assigned %s and 6 attachments" % PAYLOADS["primary"], out)
        self.assertIn("Goal requires online mode for execution but Maven is currently offline", out)
        self.assertNotEqual(accepted.returncode, 0)
        self.assertTrue((scratch / "staging" / "SENTINEL").exists(), "the publisher mojo ran")
        self.assertEqual(digests(inputs), before)

        mutated = case / "mutated"
        shutil.copytree(str(inputs), str(mutated))
        flip_byte(mutated / PAYLOADS["sources"])
        props = case / "mutated.properties"
        props.write_text(Path(values["PROPERTIES"]).read_text(encoding="ascii").replace(
            str(inputs) + "/", str(mutated) + "/"), encoding="ascii")
        refused = maven(props, values["HELPER_JAR"], mutated / PAYLOADS["pom"])
        out = refused.stdout.decode("utf-8", "replace")
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("retained-artifacts refused: sha256_mismatch", out)
        self.assertNotIn("Goal requires online mode", out)


# ------------------------------------------------------------------ publish-packages workflow

FAKE_GH_PUBLISH = r'''#!{python} -I
import fnmatch, json, os, shutil, sys
from pathlib import Path
argv = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "gh", "argv": argv}) + "\n")
state = Path(os.environ["FAKE_STATE"])
if argv[:2] == ["release", "download"] and len(argv) >= 3:
    tag, rest, folder, patterns = argv[2], argv[3:], None, []
    while rest:
        flag, value, rest = rest[0], rest[1] if len(rest) > 1 else None, rest[2:]
        if flag == "--dir":
            folder = value
        elif flag == "--pattern":
            patterns.append(value)
        else:
            sys.stderr.write("fake gh: unmodelled flag %r\n" % flag)
            sys.exit(96)
    if tag != os.environ["FAKE_RELEASE_TAG"]:
        sys.stderr.write("release not found\n")
        sys.exit(1)
    assets = state / "release-assets"
    matched = [n for n in sorted(os.listdir(str(assets))) if any(fnmatch.fnmatchcase(n, p) for p in patterns)]
    if not matched:
        sys.stderr.write("no assets match the file pattern\n")
        sys.exit(1)
    os.makedirs(folder, exist_ok=True)
    for name in matched:
        target = Path(folder) / name
        if target.exists():
            sys.stderr.write("%s already exists\n" % name)
            sys.exit(1)
        shutil.copyfile(str(assets / name), str(target))
    sys.exit(0)
sys.stderr.write("fake gh: unmodelled call %r\n" % argv)
sys.exit(96)
'''

FAKE_MVN_PUBLISH = r'''#!{python} -I
import json, os, shutil, subprocess, sys, time
from pathlib import Path
argv = sys.argv[1:]
state = Path(os.environ["FAKE_STATE"])


def log(entry):
    with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")


log({"tool": "mvn", "argv": argv, "credentials": {k: bool(os.environ.get(k)) for k in
                                                  ("SONATYPE_USERNAME", "SONATYPE_PASSWORD", "GITHUB_TOKEN")}})
args = [a for a in argv if a != "-B"]
if args in (["-v"], ["--version"]):
    print("Apache Maven 3.9.9 (fixture)")
    print("Maven home: " + os.environ["REAL_MAVEN_HOME"])
    sys.exit(0)
goals, props, rest = [], {}, list(args)
while rest:
    item = rest.pop(0)
    if item in ("-f", "--file", "-s", "--settings"):
        rest.pop(0)
    elif item.startswith("-D") and "=" in item:
        key, value = item[2:].split("=", 1)
        props[key] = value
    elif not item.startswith("-"):
        goals.append(item)
if goals == ["deploy:deploy-file"]:
    store = state / "ghp"
    store.mkdir(exist_ok=True)
    stem = "%s-%s" % (props["artifactId"], props["version"])
    for key, extension in (("file", ".jar"), ("pomFile", ".pom")):
        target = store / (stem + extension)
        if target.exists():
            sys.stderr.write("409 Conflict\n")
            sys.exit(1)
        shutil.copyfile(props[key], str(target))
    sys.exit(0)
if goals == ["org.sonatype.central:central-publishing-maven-plugin:0.9.0:publish"]:
    real = [os.environ["REAL_MVN"], "-B", "-o", "-s", os.environ["EMPTY_SETTINGS"],
            "-Dmaven.repo.local=" + os.environ["MAVEN_LOCAL_REPO"], "-DcentralBaseUrl=https://127.0.0.1:9"] + args
    env = dict(os.environ)
    env["HOME"] = os.environ["REAL_HOME"]
    env["PATH"] = os.environ["REAL_PATH"]
    for _ in range(20):
        proc = subprocess.run(real, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if proc.returncode != 75:
            break
        time.sleep(15)
    out = proc.stdout.decode("utf-8", "replace")
    sys.stdout.write(out)
    assigned = "[retained-artifacts] assigned" in out
    offline = "Goal requires online mode for execution" in out
    log({"tool": "real-mvn", "assigned": assigned, "offline_stop": offline, "returncode": proc.returncode})
    if not (assigned and offline):
        sys.exit(1)
    published = state / "central-published"
    published.mkdir(exist_ok=True)
    for line in Path(props["retainedArtifacts.properties"]).read_text(encoding="ascii").splitlines():
        key, _, value = line.partition("=")
        if key.startswith("artifact.") and key.endswith(".file"):
            shutil.copyfile(value, str(published / Path(value).name))
    sys.exit(0)
log({"tool": "mvn-forbidden", "argv": argv})
sys.stderr.write("fake mvn: a lifecycle build or unmodelled goal is forbidden in publication: %r\n" % argv)
sys.exit(97)
'''

FAKE_PYTHON = r'''#!{python} -I
import os, sys
args = sys.argv[1:]
while args and args[0].startswith("-"):
    if args[0] in ("-I", "-B", "-u", "-E", "-s", "-S"):
        args = args[1:]
        continue
    sys.stderr.write("fake python3: unsupported flag %r\n" % args[0])
    sys.exit(95)
os.execv(sys.executable, [sys.executable, "-I", os.environ["FAKE_PY_SHIM"]] + args)
'''

PYTHON_SHIM = r'''
import email.message, io, json, os, runpy, sys, urllib.error, urllib.request
from pathlib import Path
args = sys.argv[1:]
state = Path(os.environ["FAKE_STATE"])
CENTRAL = "https://repo1.maven.org/maven2/com/ultikits/UltiTools-API/%s/" % os.environ["FAKE_VERSION"]
GHP = "https://maven.pkg.github.com/UltiKits/UltiTools-Reborn/com/ultikits/ultitools-api/%s/" % os.environ["FAKE_VERSION"]


def log(entry):
    with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")


def route(request):
    url = request.full_url
    if url.startswith(CENTRAL):
        if os.environ.get("FAKE_CENTRAL_STATUS"):
            return int(os.environ["FAKE_CENTRAL_STATUS"]), b"error"
        path = state / "central" / url[len(CENTRAL):]
        return (200, path.read_bytes()) if path.is_file() else (404, b"Not Found")
    if url.startswith(GHP):
        if request.get_header("Authorization") != "Bearer " + os.environ["FAKE_GHP_TOKEN"]:
            return 401, b"Unauthorized"
        path = state / "ghp" / url[len(GHP):]
        return (200, path.read_bytes()) if path.is_file() else (404, b"Not Found")
    log({"tool": "https-unexpected", "url": url})
    raise RuntimeError("the fake internet has no route for %s" % url)


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
    if isinstance(request, str):
        request = urllib.request.Request(request)
    status, body = route(request)
    log({"tool": "https", "url": request.full_url, "status": status,
         "auth": request.get_header("Authorization") is not None,
         "auth_redirected": "Authorization" in request.headers})
    if status >= 400:
        raise urllib.error.HTTPError(request.full_url, status, "fake", email.message.Message(), io.BytesIO(body))
    return Response(request.full_url, status, body)


urllib.request.urlopen = fake_urlopen
sys.argv = [args[0]] + args[1:]
runpy.run_path(args[0], run_name="__main__")
'''

FORBIDDEN_GOALS = ("clean", "compile", "package", "verify", "install", "deploy", "versions:set", "test")


class PublishWorkflowFixture(object):
    """One release event: the accepted checkout, a release carrying the five retained assets, a fake GitHub
    Packages store and a fake Maven Central, and fresh runner directories for each job."""

    def __init__(self, release, root):
        self.release = release
        self.root = Path(root)
        for name in ("bin", "state", "home", "runners"):
            (self.root / name).mkdir()
        self.state = self.root / "state"
        self.log = self.root / "fake-log.jsonl"
        assets = self.state / "release-assets"
        assets.mkdir()
        for name in list(PAYLOADS.values()) + [MANIFEST]:
            shutil.copyfile(str(release.retained / name), str(assets / name))
        (self.state / "central").mkdir()
        shim = self.root / "python_shim.py"
        shim.write_text(PYTHON_SHIM, encoding="utf-8")
        self.shim = shim
        for name, text in (("gh", FAKE_GH_PUBLISH), ("mvn", FAKE_MVN_PUBLISH), ("python3", FAKE_PYTHON)):
            make_executable(self.root / "bin" / name, text)
        self.settings = self.root / "empty-settings.xml"
        self.settings.write_text("<settings/>\n", encoding="ascii")
        gnupg = self.root / "home" / ".gnupg"
        shutil.copytree(str(release.gnupg), str(gnupg), ignore=shutil.ignore_patterns("S.*", "*.lock"))
        os.chmod(str(gnupg), 0o700)
        self.head = release.head
        self.vars = {"PUBLISH_TO_CENTRAL": "true"}
        self.extra_env = {}

    def env(self, runner):
        env = {"PATH": "%s:%s" % (self.root / "bin", os.environ.get("PATH", "/usr/bin:/bin")),
               "HOME": str(self.root / "home"), "LANG": "C.UTF-8", "GIT_CONFIG_NOSYSTEM": "1",
               "GITHUB_WORKSPACE": str(self.release.checkout), "GITHUB_SHA": self.head,
               "GITHUB_REF": "refs/tags/" + TAG, "GITHUB_REF_NAME": TAG, "GITHUB_EVENT_NAME": "release",
               "GITHUB_REPOSITORY": "UltiKits/UltiTools-Reborn", "GITHUB_ACTOR": "fixture-actor",
               "RUNNER_TEMP": str(runner), "FAKE_LOG": str(self.log), "FAKE_STATE": str(self.state),
               "FAKE_RELEASE_TAG": TAG, "FAKE_VERSION": VERSION, "FAKE_GHP_TOKEN": "fixture-github-token",
               "FAKE_PY_SHIM": str(self.shim), "REAL_MVN": REAL_MVN or "", "REAL_MAVEN_HOME": str(maven_home() or ""),
               "MAVEN_LOCAL_REPO": str(local_repository()), "EMPTY_SETTINGS": str(self.settings),
               "REAL_HOME": str(Path.home()), "REAL_PATH": os.environ.get("PATH", "/usr/bin:/bin")}
        if os.environ.get("JAVA_HOME"):
            env["JAVA_HOME"] = os.environ["JAVA_HOME"]
        env.update(self.extra_env)
        return env

    def context(self, version):
        return {"secrets": {"GITHUB_TOKEN": "fixture-github-token", "SONATYPE_USERNAME": "fixture-user",
                            "SONATYPE_PASSWORD": "fixture-password", "MAVEN_GPG_PASSPHRASE": "",
                            "MAVEN_GPG_PRIVATE_KEY": "fixture-key-material", "DOCS_REPO_TOKEN": "fixture-docs"},
                "vars": dict(self.vars), "github": {"token": "fixture-github-token", "ref_name": TAG},
                "needs": {"validate": {"outputs": {"version": version}}}}

    def run(self, job_name, version=VERSION):
        workflow = load_workflow(PUBLISH_WORKFLOW)
        runner = self.root / "runners" / job_name
        runner.mkdir()
        result = run_job(workflow["jobs"][job_name], self.release.checkout, self.env(runner), self.context(version),
                         runner)
        result.runner = runner
        return result

    def run_validate(self):
        workflow = load_workflow(PUBLISH_WORKFLOW)
        runner = self.root / "runners" / "validate"
        runner.mkdir()
        job = workflow["jobs"]["validate"]
        result = run_job(job, self.release.checkout, self.env(runner), self.context(""), runner)
        return result, job_outputs(job, result)

    def entries(self, tool):
        return [e for e in read_log(self.log) if e["tool"] == tool]

    def mvn_goal_calls(self, goal):
        return [e for e in self.entries("mvn") if goal in e["argv"]]


class PublishWorkflowTests(unittest.TestCase):
    """Both publish jobs consume the retained, accepted files from the release; nothing is rebuilt."""

    @classmethod
    def setUpClass(cls):
        reason = blocked_reason()
        if reason:
            raise AssertionError(reason)
        cls.tmp = tempfile.mkdtemp(prefix="publish-wf-")
        cls.release = ReleaseFixture(Path(cls.tmp) / "release")

    @classmethod
    def tearDownClass(cls):
        cls.release.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def setUp(self):
        self.fixture = PublishWorkflowFixture(self.release, tempfile.mkdtemp(prefix="wf-", dir=self.tmp))

    def tearDown(self):
        kill_agent(self.fixture.root / "home" / ".gnupg")

    def retained_digest(self, name):
        return sha256_file(self.release.retained / name)

    def assert_no_lifecycle_build(self):
        for entry in self.fixture.entries("mvn"):
            for goal in FORBIDDEN_GOALS:
                self.assertNotIn(goal, entry["argv"], "publication ran a lifecycle build: %r" % entry["argv"])
            self.assertFalse(any(a.startswith("-P") for a in entry["argv"]), entry["argv"])
        self.assertEqual(self.fixture.entries("mvn-forbidden"), [])
        for entry in self.fixture.entries("gh"):
            self.assertEqual(entry["argv"][:2], ["release", "download"], entry["argv"])

    def test_both_jobs_publish_the_retained_bytes_without_a_lifecycle_build(self):
        validate, outputs = self.fixture.run_validate()
        self.assertEqual(validate.status, "success", validate.log)
        self.assertEqual(outputs["version"], VERSION)
        github = self.fixture.run("publish-github", outputs["version"])
        self.assertEqual(github.status, "success", github.log)
        central = self.fixture.run("publish-central", outputs["version"])
        self.assertEqual(central.status, "success", central.log)

        deploys = self.fixture.mvn_goal_calls("deploy:deploy-file")
        self.assertEqual(len(deploys), 1, github.log)
        gh_inputs = github.runner / "retained-inputs"
        argv = deploys[0]["argv"]
        for expected in ("-DgroupId=com.ultikits", "-DartifactId=ultitools-api", "-Dversion=" + VERSION,
                         "-Dpackaging=jar", "-Dfile=%s" % (gh_inputs / PAYLOADS["primary"]),
                         "-DpomFile=%s" % (gh_inputs / PAYLOADS["pom"]), "-DgeneratePom=false",
                         "-DrepositoryId=github", "-Durl=https://maven.pkg.github.com/UltiKits/UltiTools-Reborn"):
            self.assertIn(expected, argv)
        self.assertTrue(deploys[0]["credentials"]["GITHUB_TOKEN"])
        ghp = self.fixture.state / "ghp"
        self.assertEqual(sha256_file(ghp / ("ultitools-api-%s.jar" % VERSION)), self.retained_digest(PAYLOADS["primary"]))
        self.assertEqual(sha256_file(ghp / ("ultitools-api-%s.pom" % VERSION)), self.retained_digest(PAYLOADS["pom"]))
        ghp_reads = [e for e in self.fixture.entries("https") if "maven.pkg.github.com" in e["url"]]
        self.assertTrue(ghp_reads and all(e["auth"] and not e["auth_redirected"] for e in ghp_reads), ghp_reads)
        self.assertTrue(any(e["status"] == 200 for e in ghp_reads), "GitHub Packages was not read back")

        publishes = self.fixture.mvn_goal_calls("org.sonatype.central:central-publishing-maven-plugin:0.9.0:publish")
        self.assertEqual(len(publishes), 1, central.log)
        central_inputs = central.runner / "retained-inputs"
        argv = publishes[0]["argv"]
        self.assertEqual(argv[argv.index("-f") + 1], str(central_inputs / PAYLOADS["pom"]))
        joined = "\n".join(argv)
        self.assertRegex(joined, r"(?m)^-Dmaven\.ext\.class\.path=%s/.+\.jar$" % re.escape(str(central.runner)))
        self.assertRegex(joined, r"(?m)^-DretainedArtifacts\.properties=%s/.+$" % re.escape(str(central.runner)))
        for name in ("staging", "deferred", "output"):
            self.assertRegex(joined, r"(?m)^-D%sDirectory=%s/.+/%s$" % (name, re.escape(str(central.runner)), name))
        for expected in ("-DpublishingServerId=ultikits-sonatype", "-DautoPublish=true", "-DwaitUntil=published",
                         "-Dchecksums=all"):
            self.assertIn(expected, argv)
        self.assertEqual(publishes[0]["credentials"]["SONATYPE_USERNAME"], True)
        real = self.fixture.entries("real-mvn")
        self.assertEqual(len(real), 1)
        self.assertTrue(real[0]["assigned"] and real[0]["offline_stop"], central.log)
        published = self.fixture.state / "central-published"
        expected = dict((name, sha256_file(central_inputs / name)) for name in
                        list(PAYLOADS.values()) + [n + ".asc" for n in SIGNED])
        self.assertEqual(digests(published), expected)
        for name in PAYLOADS.values():
            self.assertEqual(expected[name], self.retained_digest(name))
            self.assertEqual(sha256_file(gh_inputs / name), self.retained_digest(name))
        verify = subprocess.run([REAL_GPG, "--homedir", str(self.fixture.root / "home" / ".gnupg"), "--batch",
                                 "--verify", str(central_inputs / (PAYLOADS["primary"] + ".asc")),
                                 str(central_inputs / PAYLOADS["primary"])], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        self.assertEqual(verify.returncode, 0)
        self.assertIn("RECHECK=ok", central.log)
        self.assert_no_lifecycle_build()

    def test_missing_or_mutated_asset_blocks_both_jobs_before_any_write(self):
        def remove_sources(assets):
            os.unlink(str(assets / PAYLOADS["sources"]))

        def mutate_primary(assets):
            flip_byte(assets / PAYLOADS["primary"])

        def mutate_pom(assets):
            flip_pom_comment(assets / PAYLOADS["pom"])

        def mutate_manifest(assets):
            path = assets / MANIFEST
            os.chmod(str(path), 0o644)
            path.write_bytes(path.read_bytes().replace(b'"schema_version": 1', b'"schema_version": 1 '))

        for label, mutate in (("remove_sources", remove_sources), ("mutate_primary", mutate_primary),
                              ("mutate_pom", mutate_pom), ("mutate_manifest", mutate_manifest)):
            with self.subTest(case=label):
                fixture = PublishWorkflowFixture(self.release, tempfile.mkdtemp(prefix="wf-" + label, dir=self.tmp))
                self.fixture = fixture
                mutate(fixture.state / "release-assets")
                for job in ("publish-github", "publish-central"):
                    result = fixture.run(job)
                    self.assertEqual(result.status, "failure", result.log)
                self.assertEqual(fixture.mvn_goal_calls("deploy:deploy-file"), [])
                self.assertEqual(fixture.entries("real-mvn"), [])
                self.assertFalse((fixture.state / "ghp").exists())
                self.assert_no_lifecycle_build()
                kill_agent(fixture.root / "home" / ".gnupg")

    def test_source_head_mismatch_blocks_both_jobs(self):
        self.fixture.head = self.release.base_head
        for job in ("publish-github", "publish-central"):
            result = self.fixture.run(job)
            self.assertEqual(result.status, "failure", result.log)
            self.assertIn("source_head_mismatch", result.log)
        self.assertEqual(self.fixture.mvn_goal_calls("deploy:deploy-file"), [])
        self.assertEqual(self.fixture.entries("real-mvn"), [])

    def test_identical_remotes_are_reconciled_and_different_or_unreadable_remotes_refused(self):
        ghp = self.fixture.state / "ghp"
        ghp.mkdir()
        shutil.copyfile(str(self.release.retained / PAYLOADS["primary"]), str(ghp / ("ultitools-api-%s.jar" % VERSION)))
        shutil.copyfile(str(self.release.retained / PAYLOADS["pom"]), str(ghp / ("ultitools-api-%s.pom" % VERSION)))
        github = self.fixture.run("publish-github")
        self.assertEqual(github.status, "success", github.log)
        self.assertEqual(self.fixture.mvn_goal_calls("deploy:deploy-file"), [])
        for name in (PAYLOADS["primary"], PAYLOADS["pom"]):
            shutil.copyfile(str(self.release.retained / name), str(self.fixture.state / "central" / name))
        central = self.fixture.run("publish-central")
        self.assertEqual(central.status, "success", central.log)
        self.assertEqual(self.fixture.entries("real-mvn"), [])
        self.assertFalse(list((central.runner / "retained-inputs").glob("*.asc")), "signed although published")

        different = PublishWorkflowFixture(self.release, tempfile.mkdtemp(prefix="wf-diff", dir=self.tmp))
        (different.state / "central" / PAYLOADS["primary"]).write_bytes(b"other bytes")
        (different.state / "central" / PAYLOADS["pom"]).write_bytes(b"<project/>")
        (different.state / "ghp").mkdir()
        (different.state / "ghp" / ("ultitools-api-%s.jar" % VERSION)).write_bytes(b"other bytes")
        for job in ("publish-github", "publish-central"):
            result = different.run(job)
            self.assertEqual(result.status, "failure", result.log)
            self.assertIn("remote_", result.log)
        self.assertEqual(different.mvn_goal_calls("deploy:deploy-file"), [])
        self.assertEqual(different.entries("real-mvn"), [])
        kill_agent(different.root / "home" / ".gnupg")

        outage = PublishWorkflowFixture(self.release, tempfile.mkdtemp(prefix="wf-outage", dir=self.tmp))
        outage.extra_env["FAKE_CENTRAL_STATUS"] = "503"
        result = outage.run("publish-central")
        self.assertEqual(result.status, "failure", result.log)
        self.assertEqual(outage.entries("real-mvn"), [])
        kill_agent(outage.root / "home" / ".gnupg")

    def test_central_variable_and_key_expiry_guards_are_kept(self):
        self.fixture.vars = {}
        skipped = self.fixture.run("publish-central")
        self.assertEqual(skipped.status, "skipped")
        expired = PublishWorkflowFixture(self.release, tempfile.mkdtemp(prefix="wf-expired", dir=self.tmp))
        gnupg = expired.root / "home" / ".gnupg"
        kill_agent(gnupg)
        shutil.rmtree(str(gnupg))
        make_key(gnupg, "Expired Fixture <expired@example.invalid>", expired=True)
        result = expired.run("publish-central")
        self.assertEqual(result.status, "failure", result.log)
        names = [name for name, outcome, _ in result.steps if outcome == "failure"]
        self.assertEqual(len(names), 1)
        self.assertIn("expiry", names[0].lower())
        self.assertEqual(expired.entries("real-mvn"), [])
        kill_agent(gnupg)

    def test_workflow_structure_keeps_guards_and_drops_rebuild_and_duplicate_docs_issue(self):
        workflow = load_workflow(PUBLISH_WORKFLOW)
        jobs = workflow["jobs"]
        self.assertEqual(sorted(jobs), ["publish-central", "publish-github", "validate"])
        self.assertEqual(str(jobs["publish-central"].get("if", "")).replace(" ", ""),
                         "${{vars.PUBLISH_TO_CENTRAL=='true'}}")
        self.assertEqual(jobs["publish-github"]["permissions"], {"contents": "read", "packages": "write"})
        self.assertEqual(jobs["publish-central"]["permissions"], {"contents": "read"})
        runs = "\n".join(s.get("run") or "" for job in jobs.values() for s in job["steps"])
        for forbidden in ("gh issue create", "gh release upload", "versions:set", "clean verify", "mvn -B deploy ",
                          "--clobber", "generatePom=true"):
            self.assertNotIn(forbidden, runs)
        central_names = [s.get("name") or "" for s in jobs["publish-central"]["steps"]]
        expiry = [i for i, n in enumerate(central_names) if "expiry" in n.lower()]
        publish = [i for i, n in enumerate(central_names) if "Publish" in n]
        self.assertEqual(len(expiry), 1)
        self.assertTrue(publish and expiry[0] < publish[0])
        for job in jobs.values():
            for step in job["steps"]:
                holds = [v for v in (step.get("env") or {}).values() if "secrets." in str(v)]
                if holds and step.get("run"):
                    self.assertTrue(any(word in step["run"] for word in ("mvn", "gpg", "repository-state")),
                                    "%s receives a secret it does not use" % step.get("name"))


if __name__ == "__main__":
    unittest.main()
