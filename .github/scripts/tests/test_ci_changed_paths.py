"""Real Git range controls and structural checks for the required main-only CI check."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_publish_workflows import SCRIPTS, WORKFLOWS, WorkflowParser, condition_holds, fixture_git, fixture_git_env, load_workflow, step_named


class ChangedPathsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ci-paths-")
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.base = self.commit("README.md")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return fixture_git(self.root, self.home, *args)

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
        env = dict(fixture_git_env(self.home), GITHUB_EVENT_NAME=event, GITHUB_SHA=head or self.git("rev-parse", "HEAD"),
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

    def test_tooling_changes_run_both_lanes(self):
        for path in (".github/scripts/new.py", ".github/workflows/new.yml", "pom.xml"):
            with self.subTest(path=path):
                before = self.git("rev-parse", "HEAD")
                self.commit(path)
                self.assertEqual(self.classify("push", before=before),
                                 {"product": str(path == "pom.xml").lower(), "build": "true", "tooling": "true"})

    def test_only_plain_documentation_uses_the_fast_path(self):
        cases = {"README.md": False, "guide/setup.md": False, "docs/diagram.png": False,
                 ".github/dependabot.yml": True, ".github/pull_request_template.md": True,
                 "compatibility/deprecations.json": True, "compatibility/DEPRECATIONS.md": True,
                 "compatibility/records/6.3.0.md": True, "DEPRECATIONS.md": True, "PROJECT.md": True,
                 "src/test/resources/config-golden/MANIFEST.md": True,
                 "src/test/resources/framework-config/MANIFEST.md": True,
                 "src/main/resources/README.md": True, "src/README.md": True,
                 "LICENSE": True, ".mvn/maven.config": True, "diagram.png": True,
                 "docs/script.py": True}
        for path, build in cases.items():
            with self.subTest(path=path):
                before = self.git("rev-parse", "HEAD")
                self.commit(path, "changed documentation or build input")
                self.assertEqual(self.classify("push", before=before)["build"], str(build).lower())

    def test_plain_docs_cannot_hide_an_asserted_markdown_change_in_the_range(self):
        self.commit("PROJECT.md")
        self.commit("README.md", "last commit is plain docs")
        self.assertEqual(self.classify("push")["build"], "true")

    def test_workflow_permission_and_dependabot_mutations_run_java_assertions(self):
        cases = ((".github/workflows/maven-ci.yml", "contents: read", "contents: write"),
                 (".github/dependabot.yml", 'target-branch: "main"', 'target-branch: "obsolete"'))
        for path, valid, invalid in cases:
            with self.subTest(path=path):
                original = (WORKFLOWS.parent.parent / path).read_text()
                self.assertIn(valid, original, "positive control: mutation must change the actual configuration")
                before = self.commit(path, original)
                self.commit(path, original.replace(valid, invalid, 1))
                result = self.classify("pull_request", payload={
                    "pull_request": {"base": {"sha": before}, "head": {"sha": self.git("rev-parse", "HEAD")}}})
                self.assertEqual(result["build"], "true", "the Java configuration assertions must run")

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


class FixtureGitIsolationTests(unittest.TestCase):
    def test_new_fixtures_ignore_contributor_signing_and_hooks(self):
        targets = ("test_ci_changed_paths.ChangedPathsTests.test_docs_only_push_is_fast",
                   "test_publish_workflows.SnapshotPublishTests.test_docs_only_push_does_not_even_evaluate_the_version")
        runner = ("import sys, unittest; sys.path.insert(0, sys.argv[1]); "
                  "unittest.main(module=None, argv=['unittest', sys.argv[2]], verbosity=2)")
        for pollution in ("global-signing", "global-hooks", "environment-signing", "environment-hooks"):
            for target in targets:
                with self.subTest(pollution=pollution, target=target), tempfile.TemporaryDirectory(prefix="git-pollution-") as folder:
                    root = Path(folder)
                    home = root / "home"
                    home.mkdir()
                    marker = root / "unexpected-execution"
                    failing = root / "unavailable-signer"
                    failing.write_text("#!/bin/sh\nprintf 'reached\\n' >> '" + str(marker) + "'\nexit 73\n")
                    failing.chmod(0o700)
                    hooks = root / "hooks"
                    hooks.mkdir()
                    hook = hooks / "pre-commit"
                    hook.write_bytes(failing.read_bytes())
                    hook.chmod(0o700)
                    config = root / "contributor.gitconfig"
                    config.touch()
                    base = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(home),
                            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": str(config),
                            "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                            "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid"}
                    if pollution.endswith("signing"):
                        settings = {"commit.gpgsign": "true", "tag.gpgsign": "true", "gpg.program": str(failing)}
                    else:
                        settings = {"core.hooksPath": str(hooks)}
                    env = dict(base)
                    for index, (key, value) in enumerate(settings.items()):
                        if pollution.startswith("global"):
                            subprocess.run(["git", "config", "--file", str(config), key, value], env=base, check=True)
                        else:
                            env["GIT_CONFIG_KEY_%d" % index] = key
                            env["GIT_CONFIG_VALUE_%d" % index] = value
                    if pollution.startswith("environment"):
                        env["GIT_CONFIG_COUNT"] = str(len(settings))
                    # Positive control: this exact subprocess-only pollution really blocks a normal commit.
                    control = root / "control"
                    control.mkdir()
                    subprocess.run(["git", "init", "-q"], cwd=control, env=base, check=True)
                    blocked = subprocess.run(["git", "commit", "--allow-empty", "-qm", "control"],
                                             cwd=control, env=env, capture_output=True, text=True)
                    self.assertNotEqual(blocked.returncode, 0, pollution)
                    self.assertTrue(marker.exists(), blocked.stderr)
                    marker.unlink()
                    before = config.read_bytes()
                    process = subprocess.run([sys.executable, "-B", "-I", "-c", runner, str(SCRIPTS / "tests"), target],
                                             cwd=root, env=env, capture_output=True, text=True)
                    self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
                    self.assertFalse(marker.exists(), "fixture must not execute contributor signer/hooks")
                    self.assertEqual(config.read_bytes(), before, "fixture must not rewrite contributor configuration")


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
        self.assertEqual(step_named(job, "Plain-documentation fast path")["if"], "steps.changes.outputs.build != 'true'")
        self.assertNotIn("build-snapshot", workflow["jobs"])
        self.assertFalse(any("badge" in str(step) or "Coverage Gate Check" in str(step) for step in job["steps"]))
        codacy = step_named(job, "📤 Upload Coverage to Codacy")
        self.assertEqual(codacy["if"],
                         "steps.changes.outputs.build == 'true' && env.CODACY_TOKEN_PRESENT == 'true' && "
                         "github.event_name == 'push' && github.ref == 'refs/heads/main' || "
                         "steps.changes.outputs.build == 'true' && env.CODACY_TOKEN_PRESENT == 'true' && "
                         "github.event_name == 'pull_request' && "
                         "github.event.pull_request.head.repo.full_name == github.repository")
        self.assertEqual(workflow["jobs"]["test"]["env"],
                         {"CODACY_TOKEN_PRESENT": "${{ secrets.CODACY_API_TOKEN != '' }}"})
        self.assertIn("/14.1.3/codacy-coverage-reporter-linux", codacy["run"])
        self.assertIn("sha256sum --check --strict", codacy["run"])
        self.assertNotIn("get.sh", codacy["run"])
        for name, other in workflow["jobs"].items():
            for step in other["steps"]:
                if "CODACY_API_TOKEN" in str(step):
                    self.assertEqual(name, "test")
                    self.assertEqual(step, codacy)

    def test_codacy_guard_allows_only_built_main_pushes_and_same_repo_prs(self):
        guard = step_named(load_workflow(WORKFLOWS / "maven-ci.yml")["jobs"]["test"],
                           "📤 Upload Coverage to Codacy")["if"]
        for event in ("push", "pull_request", "workflow_dispatch"):
            for ref in ("refs/heads/main", "refs/pull/725/merge"):
                for repository in ("UltiKits/UltiTools-Reborn", "contributor/fork"):
                    for build in ("true", "false"):
                      for token in ("true", "false"):
                        with self.subTest(event=event, ref=ref, repository=repository, build=build, token=token):
                            context = {"steps": {"changes": {"outputs": {"build": build}}},
                                       "env": {"CODACY_TOKEN_PRESENT": token},
                                       "github": {"event_name": event, "ref": ref,
                                                  "repository": "UltiKits/UltiTools-Reborn",
                                                  "event": {"pull_request": {"head": {"repo": {"full_name": repository}}}}}}
                            expected = build == "true" and token == "true" and (
                                (event == "push" and ref == "refs/heads/main") or
                                (event == "pull_request" and repository == "UltiKits/UltiTools-Reborn"))
                            self.assertEqual(condition_holds(guard, context), expected)
        # Push events do not contain pull_request; short-circuit evaluation must still work.
        context = {"steps": {"changes": {"outputs": {"build": "true"}}}, "env": {"CODACY_TOKEN_PRESENT": "true"},
                   "github": {"event_name": "push", "ref": "refs/heads/main"}}
        self.assertTrue(condition_holds(guard, context))

    def test_codacy_upload_is_skipped_without_a_token_for_every_actor(self):
        workflow = load_workflow(WORKFLOWS / "maven-ci.yml")
        guard = step_named(workflow["jobs"]["test"], "📤 Upload Coverage to Codacy")["if"]
        same_repo = {"head": {"repo": {"full_name": "UltiKits/UltiTools-Reborn"}}}
        cases = (
            ("same-repo PR, empty token", "pull_request", "refs/pull/725/merge", "dependabot-free-actor", "false", False),
            ("dependabot PR, empty token", "pull_request", "refs/pull/726/merge", "dependabot[bot]", "false", False),
            ("main push, token present", "push", "refs/heads/main", "maintainer", "true", True),
        )
        for label, event, ref, actor, token, expected in cases:
            with self.subTest(label):
                context = {"steps": {"changes": {"outputs": {"build": "true"}}},
                           "env": {"CODACY_TOKEN_PRESENT": token},
                           "github": {"event_name": event, "ref": ref, "actor": actor,
                                      "repository": "UltiKits/UltiTools-Reborn",
                                      "event": {"pull_request": same_repo}}}
                self.assertEqual(condition_holds(guard, context), expected)

    def test_pr_concurrency_is_cancellable_but_each_push_has_its_own_group(self):
        concurrency = load_workflow(WORKFLOWS / "maven-ci.yml")["concurrency"]
        self.assertEqual(concurrency, {
            "group": "${{ github.workflow }}-${{ github.event_name == 'pull_request' && github.ref || github.sha }}",
            "cancel-in-progress": "${{ github.event_name == 'pull_request' }}"})
        self.assertNotIn("concurrency", load_workflow(WORKFLOWS / "snapshot-publish.yml"))

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
