"""
Tests for tools/uat/ci-drift-guard.sh (Phase 10 plan 10-04, Task 2).

This suite exercises the script's own control-flow logic -- the pinned classpath-plugin
invocation, the missing-framework-jar check, the git-trackedness check (T-10-14), and the
byte-identity diff -- against a fake `mvn`/`java` toolchain on PATH, so it runs without a real
Maven/Java build. The plan's own manual `<verify>` commands separately exercise the script
against a real UltiChat build; this suite is the fast, hermetic complement.
"""
import os
import shlex
import stat
# Every subprocess.run call below invokes git/bash with a fixed, hardcoded argument list
# built by this test suite itself, never external or attacker-supplied input; see the
# per-call-site B603 tags.
import subprocess  # nosec B404
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'ci-drift-guard.sh'

DEFAULT_CP_LINE = '/fake/repo/com/ultikits/UltiTools-API/6.3.0-SNAPSHOT/UltiTools-API-6.3.0-SNAPSHOT.jar:/fake/gson.jar'
DEFAULT_SURFACE = '{"schema_version": 1, "items": [], "config_entities": []}\n'


def make_executable(path, content):
    path.write_text(content, encoding='utf-8')
    mode = path.stat().st_mode
    path.chmod(mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


def init_repo(repo_dir):
    subprocess.run(['git', 'init', '-q'], cwd=repo_dir, check=True)  # nosec B603 -- fixed git/bash args, never external input
    subprocess.run(['git', 'config', 'user.email', 'test@example.com'], cwd=repo_dir, check=True)  # nosec B603 -- fixed git/bash args, never external input
    subprocess.run(['git', 'config', 'user.name', 'Test'], cwd=repo_dir, check=True)  # nosec B603 -- fixed git/bash args, never external input


def stub_toolchain(tmp_path, cp_line=DEFAULT_CP_LINE, surface_content=DEFAULT_SURFACE):
    """
    Install fake `mvn` and `java` executables on a directory to prepend to PATH.

    The fake `mvn` writes `cp_line` to whatever `-Dmdep.outputFile=...` path it is given. The
    fake `java` writes `surface_content` to whatever `--output ...` path it is given, and also
    appends its full argv (one arg per line, blank-line separated per invocation) to
    `java-invocations.log` in the same directory, so a test can assert on --module/--classes.
    """
    bin_dir = tmp_path / 'fakebin'
    bin_dir.mkdir(exist_ok=True)

    mvn_script = (
        '#!/usr/bin/env bash\n'
        'set -euo pipefail\n'
        'OUT=""\n'
        'for arg in "$@"; do\n'
        '  case "$arg" in\n'
        '    -Dmdep.outputFile=*) OUT="${arg#-Dmdep.outputFile=}" ;;\n'
        '  esac\n'
        'done\n'
        'mkdir -p "$(dirname "$OUT")"\n'
        'printf %s ' + shlex.quote(cp_line) + ' > "$OUT"\n'
    )
    make_executable(bin_dir / 'mvn', mvn_script)

    java_script = (
        '#!/usr/bin/env bash\n'
        'set -euo pipefail\n'
        'LOG="' + str(bin_dir / 'java-invocations.log') + '"\n'
        'printf "%s\\n" "$@" >> "$LOG"\n'
        'printf "\\n" >> "$LOG"\n'
        'OUT=""\n'
        'prev=""\n'
        'for arg in "$@"; do\n'
        '  if [ "$prev" = "--output" ]; then OUT="$arg"; fi\n'
        '  prev="$arg"\n'
        'done\n'
        'mkdir -p "$(dirname "$OUT")"\n'
        'printf %s ' + shlex.quote(surface_content) + ' > "$OUT"\n'
    )
    make_executable(bin_dir / 'java', java_script)

    return bin_dir


def run_guard(repo_dir, bin_dir, args=None):
    env = dict(os.environ)
    env['PATH'] = str(bin_dir) + os.pathsep + env['PATH']
    cmd = ['bash', str(SCRIPT)] + (args or [])
    return subprocess.run(cmd, cwd=repo_dir, env=env, capture_output=True, text=True)  # nosec B603 -- fixed git/bash args, never external input


class TestSyntax:

    def test_script_is_syntactically_valid_bash(self):
        result = subprocess.run(['bash', '-n', str(SCRIPT)], capture_output=True, text=True)  # nosec B603 -- fixed git/bash args, never external input
        assert result.returncode == 0, result.stderr

    def test_pins_the_dependency_plugin_coordinate_to_3_11_0(self):
        text = SCRIPT.read_text(encoding='utf-8')
        assert 'maven-dependency-plugin:${DEPENDENCY_PLUGIN_VERSION}:build-classpath' in text
        assert 'DEPENDENCY_PLUGIN_VERSION="3.11.0"' in text


class TestFrameworkJarMissing:

    def test_classpath_without_the_framework_jar_fails_named(self, tmp_path):
        init_repo(tmp_path)
        bin_dir = stub_toolchain(tmp_path, cp_line='/fake/some-other-lib.jar')

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode != 0
        assert 'UltiTools-API' in result.stderr

    def test_empty_classpath_file_fails_named(self, tmp_path):
        init_repo(tmp_path)
        bin_dir = stub_toolchain(tmp_path, cp_line='')

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode != 0


class TestUntrackedSurfaceFile:

    def test_regenerating_into_a_never_added_path_fails_named_not_tracked(self, tmp_path):
        init_repo(tmp_path)
        bin_dir = stub_toolchain(tmp_path)

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode != 0
        assert 'not tracked' in result.stderr.lower()

    def test_a_completely_untracked_file_would_otherwise_look_clean_to_plain_git_diff(self, tmp_path):
        """
        Positive control for T-10-14's own premise.

        Prove that WITHOUT the trackedness check, `git diff --exit-code` really does report
        a brand new untracked file as clean (exit 0) -- confirming the vulnerability this
        guard's check closes is real, not a hypothetical.
        """
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')

        result = subprocess.run(['git', 'diff', '--exit-code', '--', 'uat/surface.json'],  # nosec B603 -- fixed git/bash args, never external input
                                 cwd=tmp_path, capture_output=True, text=True)

        assert result.returncode == 0


class TestFirstTimeGeneration:

    def test_intent_to_add_with_no_real_baseline_passes(self, tmp_path):
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text('placeholder', encoding='utf-8')
        subprocess.run(['git', 'add', '-N', 'uat/surface.json'], cwd=tmp_path, check=True)  # nosec B603 -- fixed git/bash args, never external input
        bin_dir = stub_toolchain(tmp_path)

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode == 0
        assert 'no real baseline' in result.stderr.lower()


class TestByteIdentityDrift:

    def test_regeneration_matching_the_committed_baseline_is_clean(self, tmp_path):
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        subprocess.run(['git', 'add', 'uat/surface.json'], cwd=tmp_path, check=True)  # nosec B603 -- fixed git/bash args, never external input
        bin_dir = stub_toolchain(tmp_path, surface_content=DEFAULT_SURFACE)

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode == 0
        assert 'byte-identical' in result.stderr.lower()

    def test_regeneration_differing_from_the_committed_baseline_fails_and_prints_the_diff(self, tmp_path):
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        subprocess.run(['git', 'add', 'uat/surface.json'], cwd=tmp_path, check=True)  # nosec B603 -- fixed git/bash args, never external input
        drifted = '{"schema_version": 1, "items": [{"id": "COM-ffffffff"}], "config_entities": []}\n'
        bin_dir = stub_toolchain(tmp_path, surface_content=drifted)

        result = run_guard(tmp_path, bin_dir, args=['TestModule'])

        assert result.returncode != 0
        assert 'COM-ffffffff' in result.stdout
        assert 'different file' in result.stderr.lower()

    def test_hand_editing_the_committed_baseline_is_visible_to_plain_git_diff(self, tmp_path):
        """
        Cover the other half of the guard's byte-identity claim.

        A hand edit to an already genuinely-tracked file (real staged content, not
        intent-to-add) is a real diff under plain git tooling -- proving the guard's
        underlying diff mechanism has a real signal to react to, not just a name-matching
        heuristic.
        """
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        subprocess.run(['git', 'add', 'uat/surface.json'], cwd=tmp_path, check=True)  # nosec B603 -- fixed git/bash args, never external input

        (tmp_path / 'uat' / 'surface.json').write_text(
            DEFAULT_SURFACE.replace('"schema_version": 1', '"schema_version": 2'), encoding='utf-8')
        dirty = subprocess.run(['git', 'diff', '--exit-code', '--', 'uat/surface.json'],  # nosec B603 -- fixed git/bash args, never external input
                                cwd=tmp_path, capture_output=True, text=True)
        assert dirty.returncode != 0

        # Restoring the original content (what a correct regeneration would produce) is
        # clean again -- this is the "regenerating makes it green" half.
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        clean = subprocess.run(['git', 'diff', '--exit-code', '--', 'uat/surface.json'],  # nosec B603 -- fixed git/bash args, never external input
                                cwd=tmp_path, capture_output=True, text=True)
        assert clean.returncode == 0


class TestArguments:

    def test_module_name_defaults_to_the_checkout_directory_basename(self, tmp_path):
        module_dir = tmp_path / 'SomeModuleName'
        module_dir.mkdir()
        init_repo(module_dir)
        (module_dir / 'uat').mkdir()
        (module_dir / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        subprocess.run(['git', 'add', 'uat/surface.json'], cwd=module_dir, check=True)  # nosec B603 -- fixed git/bash args, never external input
        bin_dir = stub_toolchain(module_dir)

        result = run_guard(module_dir, bin_dir)

        assert result.returncode == 0
        invocations = (bin_dir / 'java-invocations.log').read_text(encoding='utf-8')
        assert 'SomeModuleName' in invocations

    def test_explicit_module_name_and_classes_path_are_honored(self, tmp_path):
        init_repo(tmp_path)
        (tmp_path / 'uat').mkdir()
        (tmp_path / 'uat' / 'surface.json').write_text(DEFAULT_SURFACE, encoding='utf-8')
        subprocess.run(['git', 'add', 'uat/surface.json'], cwd=tmp_path, check=True)  # nosec B603 -- fixed git/bash args, never external input
        bin_dir = stub_toolchain(tmp_path)

        result = run_guard(tmp_path, bin_dir, args=['UltiBot', 'ultibot-dist/target/UltiBot-1.0.0.jar'])

        assert result.returncode == 0
        invocations = (bin_dir / 'java-invocations.log').read_text(encoding='utf-8')
        assert 'UltiBot' in invocations
        assert 'ultibot-dist/target/UltiBot-1.0.0.jar' in invocations
