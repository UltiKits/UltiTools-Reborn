"""Hermetic execution tests for the two publication workflows.

The tests read the real workflow files, extract each job's steps with a small parser for the subset of YAML
the workflows use, and run every ``run:`` block the way GitHub Actions does (``bash --noprofile --norc -eo
pipefail``), in order, honouring ``if:`` conditions, step outputs and ``continue-on-error``. ``uses:`` steps
are simulated: the checkout is prepared by the fixture and setup-java is a no-op. An expression the harness
does not understand fails the test instead of being guessed, and a ``${{ }}`` expression inside a run block
fails it too, because a workflow must pass values to scripts through ``env``.

Every external command a step can reach is a fake placed first on PATH in a temporary directory:

- ``mvn`` logs each call. It answers ``help:evaluate`` with the fixture's project version and records a
  snapshot ``deploy`` without contacting anything. Any other invocation exits 96.

No test contacts a network service, publishes anything or reads a credential. Standard library only.
"""

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
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
REPO_ROOT = SCRIPTS.parent.parent
WORKFLOWS = REPO_ROOT / ".github" / "workflows"
SNAPSHOT_WORKFLOW = WORKFLOWS / "snapshot-publish.yml"
BASH = shutil.which("bash")


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


if __name__ == "__main__":
    unittest.main()
