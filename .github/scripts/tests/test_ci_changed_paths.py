"""Real Git range controls and structural checks for the required main-only CI check."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_publish_workflows import SCRIPTS, WORKFLOWS, WorkflowParser, load_workflow, step_named


class ChangedPathsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ci-paths-")
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.base = self.commit("README.md")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root).decode().strip()

    def commit(self, path, text="fixture"):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        self.git("add", "--", path)
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def classify(self, event, before=None, head=None, payload=None, ok=True):
        event_file = self.root / "event.json"
        event_file.write_text(json.dumps(payload if payload is not None else {"before": before or self.base}))
        output = self.root / "output.txt"
        output.unlink(missing_ok=True)
        env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_SHA=head or self.git("rev-parse", "HEAD"),
                   GITHUB_EVENT_PATH=str(event_file), GITHUB_OUTPUT=str(output))
        process = subprocess.run([sys.executable, "-I", str(SCRIPTS / "ci_changed_paths.py")],
                                 cwd=self.root, env=env, capture_output=True, text=True)
        if not ok:
            self.assertNotEqual(process.returncode, 0, process.stdout + process.stderr)
            self.assertFalse(output.exists())
            return {}
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
        return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def test_docs_only_push_is_fast(self):
        self.commit("README.md", "new docs")
        self.assertEqual(self.classify("push"), {"product": "false", "build": "false", "tooling": "false"})

    def test_entire_push_range_not_just_last_commit(self):
        self.commit("src/main/java/Example.java")
        self.commit("README.md", "last commit is docs")
        self.assertEqual(self.classify("push"), {"product": "true", "build": "true", "tooling": "false"})

    def test_test_only_change_builds_but_does_not_publish(self):
        self.commit("src/test/java/ExampleTest.java")
        self.assertEqual(self.classify("push"), {"product": "false", "build": "true", "tooling": "false"})

    def test_tooling_changes_only_run_release_lane(self):
        for path in (".github/scripts/new.py", ".github/workflows/new.yml"):
            with self.subTest(path=path):
                before = self.git("rev-parse", "HEAD")
                self.commit(path)
                self.assertEqual(self.classify("push", before=before),
                                 {"product": "false", "build": "false", "tooling": "true"})

    def test_product_deletion_and_rename_out_of_scope_still_build(self):
        before = self.commit("src/main/java/Example.java")
        self.git("mv", "src/main/java/Example.java", "Example.java")
        self.git("commit", "-qm", "move out of product")
        self.assertEqual(self.classify("push", before=before)["product"], "true")
        before = self.commit("pom.xml")
        self.git("rm", "pom.xml")
        self.git("commit", "-qm", "remove pom")
        self.assertEqual(self.classify("push", before=before)["product"], "true")

    def test_new_branch_zero_before_uses_full_tree(self):
        self.commit("pom.xml")
        self.assertEqual(self.classify("push", before="0" * 40)["product"], "true")

    def test_pr_compares_merge_base_not_unrelated_base_changes(self):
        self.git("checkout", "-qb", "feature")
        head = self.commit("README.md", "feature docs")
        self.git("checkout", "-qb", "trunk", self.base)
        base = self.commit("src/main/java/Unrelated.java")
        payload = {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}
        self.assertEqual(self.classify("pull_request", payload=payload),
                         {"product": "false", "build": "false", "tooling": "false"})

    def test_unreadable_or_malformed_endpoints_fail_closed(self):
        for before in ("f" * 40, "HEAD~1", "--help", None):
            with self.subTest(before=before):
                self.classify("push", payload={"before": before}, ok=False)
        self.classify("schedule", ok=False)

    def test_manual_dispatch_can_request_product_build(self):
        self.assertEqual(self.classify("workflow_dispatch")["product"], "true")


class MainOnlyWorkflowTests(unittest.TestCase):
    def test_main_job_always_reports_and_heavy_steps_are_gated(self):
        workflow = load_workflow(WORKFLOWS / "maven-ci.yml")
        self.assertEqual(workflow["on"], {"push": {"branches": ["main"]},
                                          "pull_request": {"branches": ["main"]}})
        job = workflow["jobs"]["test"]
        self.assertEqual(job["name"], "🧪 Test & Coverage")
        self.assertNotIn("if", job)
        self.assertNotIn("needs", job)
        for name in ("☕ Set up JDK 21", "🧪 Build & Test with Coverage", "🧪 Run isolated-tagged tests",
                     "📤 Upload Coverage Report"):
            self.assertEqual(step_named(job, name)["if"], "steps.changes.outputs.build == 'true'")
        isolated = step_named(job, "🧪 Run isolated-tagged tests")["run"]
        self.assertIn("-Dgroups=isolated -DexcludedGroups=", isolated)
        self.assertIn("mv target/surefire-reports target/regular-surefire-reports", isolated)
        self.assertIn("target/regular-surefire-reports/", step_named(job, "📤 Upload Test Results")["with"]["path"])
        self.assertEqual(step_named(job, "Non-product fast path")["if"], "steps.changes.outputs.build != 'true'")
        self.assertNotIn("build-snapshot", workflow["jobs"])
        self.assertFalse(any("badge" in str(step) or "Coverage Gate Check" in str(step) for step in job["steps"]))
        codacy = step_named(job, "📤 Upload Coverage to Codacy")
        self.assertEqual(codacy["if"], "github.event_name == 'push' && github.ref == 'refs/heads/main' && steps.changes.outputs.build == 'true'")
        self.assertIn("/14.1.3/codacy-coverage-reporter-linux", codacy["run"])
        self.assertIn("sha256sum --check --strict", codacy["run"])
        self.assertNotIn("get.sh", codacy["run"])
        for name, other in workflow["jobs"].items():
            for step in other["steps"]:
                if "CODACY_API_TOKEN" in str(step):
                    self.assertEqual(name, "test")
                    self.assertEqual(step, codacy)

    def test_snapshot_main_only_and_product_range_guards(self):
        workflow = load_workflow(WORKFLOWS / "snapshot-publish.yml")
        self.assertEqual(workflow["on"]["push"], {"branches": ["main"]})
        job = workflow["jobs"]["snapshot-publish"]
        self.assertEqual(job["if"], "github.ref == 'refs/heads/main'")
        for name in ("Set up JDK 21", "Classify the project version"):
            self.assertEqual(step_named(job, name)["if"], "steps.changes.outputs.product == 'true'")
        self.assertEqual(step_named(job, "Deploy snapshot to Central")["if"],
                         "steps.changes.outputs.product == 'true' && steps.version.outputs.kind == 'snapshot'")
        self.assertEqual(step_named(job, "Classify changed paths")["run"],
                         "python3 -I .github/scripts/ci_changed_paths.py")

    def test_all_workflow_jobs_have_timeouts_and_all_actions_are_pinned(self):
        for path in WORKFLOWS.glob("*.yml"):
            # The runner parser models job steps, not GPG's scalar trigger path list.
            workflow = WorkflowParser("jobs:" + path.read_text().split("\njobs:", 1)[1]).parse()
            for job in workflow["jobs"].values():
                self.assertGreater(int(job["timeout-minutes"]), 0, str(path))
                for step in job["steps"]:
                    if "uses" in step:
                        self.assertRegex(step["uses"], r"^[^@]+@[0-9a-f]{40}$")
                    if str(step.get("uses", "")).startswith("actions/upload-artifact@"):
                        self.assertEqual(step["with"]["retention-days"], "7")
            for line in path.read_text().splitlines():
                if "uses:" in line:
                    self.assertRegex(line, r"@[0-9a-f]{40} # v[0-9]+\.[0-9]+\.[0-9]+$")
        validate = load_workflow(WORKFLOWS / "publish-packages.yml")["jobs"]["validate"]
        self.assertEqual(validate["permissions"], {})


if __name__ == "__main__":
    unittest.main()
