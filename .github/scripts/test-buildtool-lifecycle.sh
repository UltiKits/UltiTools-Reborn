#!/usr/bin/env bash
# Nonpublishing lifecycle assertions. Evidence survives Maven clean outside target.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
exec python3 -I - "$ROOT" "$@" <<'PY'
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(sys.argv[1])
HELPER = Path('com/ultikits/ultitools/buildtools/deprecation/DeprecationRegistryGenerator.class')
FLAGS = {'normal': [], 'skip-execution': ['-DskipTests'],
         'skip-compilation': ['-Dmaven.test.skip=true']}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def evidence_root():
    value = os.environ.get('P20_1_EVIDENCE')
    require(value, 'P20_1_EVIDENCE must name an existing private evidence directory')
    path = Path(value).resolve(strict=True)
    require(path.is_dir() and (path.stat().st_mode & 0o077) == 0,
            'Evidence directory must be private')
    require('target' not in path.parts and not path.is_relative_to(ROOT),
            'Evidence must be outside the project and target')
    return path


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def check_lifecycle(log, mode, root):
    compiler = log.find('maven-compiler-plugin:3.11.0:compile (compile-deprecation-buildtools)')
    japicmp = log.find('japicmp-maven-plugin:')
    generator = log.find('exec-maven-plugin:3.6.3:java (generate-deprecation-registry)')
    wrote = log.find('DeprecationRegistryGenerator: wrote ')
    require(0 <= compiler < japicmp < generator < wrote,
            'Dedicated compiler, japicmp, generator and wrote message must occur in order')
    require((root / 'target/buildtools-classes' / HELPER).is_file(), 'Missing dedicated helper output')
    require(not (root / 'target/classes' / HELPER).exists(), 'Build helper leaked into product classes')
    for name in ('deprecations.json', 'DEPRECATIONS.md'):
        output = root / 'compatibility' / name
        require(output.is_file() and output.stat().st_size > 0, 'Missing registry output: ' + name)
        require(str(output) in log, 'Generator did not attribute output to selected project: ' + name)
    if mode == 'skip-compilation':
        require('Not compiling test sources' in log and 'Tests are skipped.' in log,
                'Skip-compilation flag did not skip compilation and execution')
        require(not (root / 'target/test-classes').exists(), 'Clean skip-compilation used test output')
    else:
        require((root / 'target/test-classes' / HELPER).is_file(), 'Expected normal test compilation')
        if mode == 'skip-execution':
            require('Tests are skipped.' in log, 'Test execution was not skipped')
        else:
            counts = re.findall(r'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0', log)
            require(counts and max(map(int, counts)) > 0, 'No successful nonempty test execution')
    return {'compiler': compiler, 'japicmp': japicmp, 'generator': generator, 'wrote': wrote}


def cell(goal, mode):
    require(goal in ('verify', 'install') and mode in FLAGS, 'Unsupported lifecycle cell')
    require(goal == 'verify', 'Isolated install support is not configured yet')
    run = Path(tempfile.mkdtemp(prefix=goal + '-' + mode + '-', dir=evidence_root()))
    command = ['mvn', '-B', 'clean', goal] + FLAGS[mode]
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    head = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    begin = time.monotonic()
    with (run / 'maven.log').open('wb') as output:
        status = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT).returncode
    log = re.sub(r'\x1b\[[0-9;]*m', '', (run / 'maven.log').read_text(errors='replace'))
    record = {'command': command, 'cwd': str(ROOT), 'head': head, 'mode': mode,
              'started': started, 'exitCode': status, 'elapsedSeconds': time.monotonic() - begin,
              'log': str(run / 'maven.log'), 'status': 'failed'}
    try:
        require(status == 0, 'Maven lifecycle failed (exit %d); inspect %s' % (status, run / 'maven.log'))
        record['order'] = check_lifecycle(log, mode, ROOT)
        record['status'] = 'passed'
    finally:
        write_json(run / 'result.json', record)
        print(json.dumps(record), flush=True)
    return record


try:
    args = sys.argv[2:]
    require(len(args) == 3 and args[0] == '--cell', 'Usage: --cell {verify|install} {normal|skip-execution|skip-compilation}')
    cell(args[1], args[2])
except (AssertionError, OSError, ValueError) as error:
    print('Lifecycle check failed: ' + str(error), file=sys.stderr)
    sys.exit(1)
PY
