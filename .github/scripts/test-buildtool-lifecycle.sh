#!/usr/bin/env bash
# Nonpublishing lifecycle assertions. Evidence survives Maven clean outside target.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
exec python3 -I - "$ROOT" "$@" <<'PY'
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(sys.argv[1])
HELPER = Path('com/ultikits/ultitools/buildtools/deprecation/DeprecationRegistryGenerator.class')
HELPER_PACKAGE = 'com/ultikits/ultitools/buildtools/deprecation/'
FLAGS = {'normal': [], 'skip-execution': ['-DskipTests'],
         'skip-compilation': ['-Dmaven.test.skip=true']}
POSITIVES = {'main': ['com/ultikits/ultitools/UltiTools.class', 'plugin.yml'],
             'sources': ['com/ultikits/ultitools/UltiTools.java'],
             'javadoc': ['com/ultikits/ultitools/UltiTools.html']}


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


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def head():
    return subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()


def artifact_paths():
    pom = ET.parse(ROOT / 'pom.xml').getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    artifact = pom.findtext('m:artifactId', namespaces=ns)
    version = pom.findtext('m:version', namespaces=ns)
    require(artifact and version and '${' not in version, 'Unresolved artifact/version in project POM')
    final = pom.findtext('m:build/m:finalName', namespaces=ns) or artifact + '-' + version
    final = final.replace('${project.artifactId}', artifact).replace('${project.version}', version)
    require('${' not in final and Path(final).name == final, 'Unsupported finalName')
    return {kind: ROOT / 'target' / (final + suffix + '.jar')
            for kind, suffix in (('main', ''), ('sources', '-sources'), ('javadoc', '-javadoc'))}


def check_artifact(path, kind):
    require(path.is_file(), 'Missing exact %s artifact: %s' % (kind, path))
    with zipfile.ZipFile(path) as archive:
        entries = archive.namelist()
        require(archive.testzip() is None, 'Corrupt artifact: ' + str(path))
    # Positive product entries precede absence claims: an empty/wrong JAR cannot pass.
    for entry in POSITIVES[kind]:
        require(entry in entries, 'Missing product positive control: ' + entry)
    require(not any(HELPER_PACKAGE in entry for entry in entries), 'Build helper leaked into ' + str(path))
    return {'path': str(path), 'kind': kind, 'sha256': digest(path),
            'bytes': path.stat().st_size, 'positiveEntries': POSITIVES[kind], 'entries': entries}


def check_lifecycle(log, mode, root, started=None):
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
        if started is not None:
            require(output.stat().st_mtime >= started, 'Stale registry output: ' + name)
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


def run_maven(command, cwd, folder):
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    begin = time.monotonic()
    with (folder / 'maven.log').open('wb') as output:
        status = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT).returncode
    log = re.sub(r'\x1b\[[0-9;]*m', '', (folder / 'maven.log').read_text(errors='replace'))
    record = {'command': command, 'cwd': str(cwd), 'projectBasedir': str(ROOT), 'head': head(),
              'started': started, 'exitCode': status, 'elapsedSeconds': time.monotonic() - begin,
              'log': str(folder / 'maven.log'), 'status': 'failed'}
    write_json(folder / 'result.json', record)
    return record, log


def isolated_repository():
    # Trace only successful local cache accesses, then copy required files, never hardlink them.
    # No user artifacts are installed/removed and no six-checkout/full-cache fan-out is used.
    evidence = evidence_root()
    repository = evidence / 'maven-repository'
    manifest = evidence / 'maven-cache-seed.json'
    if manifest.exists():
        saved = json.loads(manifest.read_text())
        require(saved['repository'] == str(repository) and repository.is_dir(), 'Invalid isolated cache receipt')
        return repository
    require(not repository.exists(), 'Unfinished isolated-cache setup; inspect retained evidence before retrying')
    require(shutil.which('strace'), 'Blocked cache setup: strace is unavailable; no package is installed')
    cache = Path.home() / '.m2/repository'
    require(cache.is_dir(), 'Blocked cache setup: no existing local Maven cache')
    setup = Path(tempfile.mkdtemp(prefix='cache-setup-', dir=evidence))
    commands = [
        ['mvn', '-B', '-o', 'clean', 'verify', '-DskipTests'],
        ['mvn', '-B', '-o', 'test', '-Dtest=DeprecationRegistryGeneratorBaseDirTest,BuildVerificationConfigurationTest'],
        ['mvn', '-B', '-o', 'org.apache.maven.plugins:maven-install-plugin:2.4:help']]
    selected = set()
    for index, command in enumerate(commands):
        folder = setup / str(index)
        folder.mkdir()
        trace = folder / 'cache.trace'
        record, _ = run_maven(['strace', '-f', '-e', 'trace=%file', '-o', str(trace), *command], ROOT, folder)
        require(record['exitCode'] == 0, 'Blocked offline cache setup; inspect ' + record['log'])
        for name in re.findall(r'"(/[^"\n]+)"', trace.read_text(errors='replace')):
            path = Path(name)
            if path.is_file() and path.is_relative_to(cache):
                selected.add(path)
                marker = path.parent / '_remote.repositories'
                if marker.is_file():
                    selected.add(marker)
    require(any(p.name.endswith('.jar') for p in selected), 'Cache trace found no positive JAR control')
    total = sum(p.stat().st_size for p in selected)
    require(total < 512 * 1024 * 1024, 'Cache seed exceeds the 512 MiB bound')
    repository.mkdir()
    files = []
    for source in sorted(selected):
        relative = source.relative_to(cache)
        target = repository / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        files.append({'path': str(relative), 'bytes': target.stat().st_size, 'sha256': digest(target)})
    write_json(manifest, {'repository': str(repository), 'sourceCache': str(cache), 'bytes': total,
                          'files': files, 'setup': str(setup), 'status': 'seeded; offline install proof still required'})
    return repository


def cell(goal, mode, cwd=ROOT, external=False):
    require(goal in ('verify', 'install') and mode in FLAGS, 'Unsupported lifecycle cell')
    run = Path(tempfile.mkdtemp(prefix=goal + '-' + mode + '-', dir=evidence_root()))
    command = ['mvn', '-B']
    repository = isolated_repository() if goal == 'install' else None
    if repository:
        command += ['-o', '-Dmaven.repo.local=' + str(repository)]
    if external:
        command += ['-f', str(ROOT / 'pom.xml')]
    command += ['clean', goal] + FLAGS[mode]
    record, log = run_maven(command, cwd, run)
    record['mode'] = mode
    try:
        require(record['exitCode'] == 0, 'Maven lifecycle failed (exit %d); inspect %s' % (record['exitCode'], record['log']))
        started = datetime.datetime.fromisoformat(record['started']).timestamp()
        record['order'] = check_lifecycle(log, mode, ROOT, started)
        record['artifacts'] = [check_artifact(path, kind) for kind, path in artifact_paths().items()]
        for artifact in record['artifacts']:
            require(Path(artifact['path']).stat().st_mtime >= started, 'Stale packaged artifact')
        if repository:
            pom = ET.parse(ROOT / 'pom.xml').getroot()
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            group = pom.findtext('m:groupId', namespaces=ns)
            artifact = pom.findtext('m:artifactId', namespaces=ns)
            version = pom.findtext('m:version', namespaces=ns)
            installed = repository / group.replace('.', '/') / artifact / version / (artifact + '-' + version + '.jar')
            require(installed.is_file() and digest(installed) == record['artifacts'][0]['sha256'],
                    'Isolated install did not write the exact main artifact')
            record['installedArtifact'] = str(installed)
        record['testSummaries'] = re.findall(r'Tests run: [^\r\n]+', log)
        record['status'] = 'passed'
        write_json(evidence_root() / 'lifecycle-latest.json', record)
    finally:
        write_json(run / 'result.json', record)
        print(json.dumps({k: v for k, v in record.items() if k != 'artifacts'}), flush=True)
    return record


def artifacts():
    receipt = json.loads((evidence_root() / 'lifecycle-latest.json').read_text())
    require(receipt['status'] == 'passed' and receipt['head'] == head(), 'Missing/stale lifecycle receipt for this HEAD')
    observed = [check_artifact(path, kind) for kind, path in artifact_paths().items()]
    require(observed == receipt['artifacts'], 'Artifacts differ from the latest successful clean lifecycle')
    folder = evidence_root() / 'artifacts'
    folder.mkdir(exist_ok=True)
    write_json(folder / 'latest.json', {'head': head(), 'status': 'passed', 'artifacts': observed})
    print('ARTIFACTS PASSED: main, sources, javadoc product positives and helper isolation')


def external_cwd():
    folder = Path(tempfile.mkdtemp(prefix='external-cwd-', dir=evidence_root()))
    caller = folder / 'caller'
    (caller / 'compatibility').mkdir(parents=True)
    sentinels = {'compatibility/deprecations.json': b'["caller sentinel"]\r\n',
                 'compatibility/DEPRECATIONS.md': b'# caller sentinel\r\n',
                 'pom.xml': b'<project><version>caller-sentinel</version></project>\n'}
    for name, value in sentinels.items():
        (caller / name).write_bytes(value)
    record = None
    try:
        record = cell('verify', 'skip-compilation', caller, True)
    finally:
        comparisons = {name: (caller / name).read_bytes() == value for name, value in sentinels.items()}
        write_json(folder / 'sentinels.json', {'caller': str(caller), 'projectBasedir': str(ROOT),
                                              'comparisons': comparisons, 'lifecycle': record})
        require(all(comparisons.values()), 'External caller sentinel bytes changed')
    print('EXTERNAL-CWD PASSED: actual mvn -f writes only the selected project')


def matrix():
    folder = evidence_root() / 'build-lifecycle'
    folder.mkdir(exist_ok=True)
    report = {'head': head(), 'status': 'running', 'cells': []}
    try:
        for goal in ('verify', 'install'):
            for mode in FLAGS:
                report['cells'].append(cell(goal, mode))
                write_json(folder / 'matrix.json', report)
        report['status'] = 'passed'
    finally:
        if report['status'] != 'passed':
            report['status'] = 'failed'
        write_json(folder / 'matrix.json', report)


def self_test():
    positives = 0
    negatives = []

    def rejects(name, action):
        try:
            action()
        except (AssertionError, OSError, zipfile.BadZipFile):
            negatives.append(name)
        else:
            raise AssertionError('Negative control unexpectedly passed: ' + name)

    with tempfile.TemporaryDirectory(prefix='buildtool-controls-') as temporary:
        fixture = Path(temporary)
        jars = {}
        for kind, entries in POSITIVES.items():
            path = fixture / (kind + '.jar')
            with zipfile.ZipFile(path, 'w') as archive:
                for entry in entries:
                    archive.writestr(entry, b'product positive control')
            check_artifact(path, kind)
            positives += 1
            jars[kind] = path
        rejects('missing artifact', lambda: check_artifact(fixture / 'missing.jar', 'main'))
        rejects('wrong artifact', lambda: check_artifact(jars['sources'], 'main'))
        for kind, path in jars.items():
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr(HELPER_PACKAGE + 'Leaked.' + ('java' if kind == 'sources' else 'class'), b'leak')
            rejects(kind + ' helper leakage', lambda p=path, k=kind: check_artifact(p, k))
        product = fixture / 'project'
        helper = product / 'target/buildtools-classes' / HELPER
        helper.parent.mkdir(parents=True)
        helper.write_bytes(b'compiled helper positive control')
        (product / 'compatibility').mkdir()
        for name in ('deprecations.json', 'DEPRECATIONS.md'):
            (product / 'compatibility' / name).write_text('positive generated output')
        log = '\n'.join([
            'maven-compiler-plugin:3.11.0:compile (compile-deprecation-buildtools)',
            'japicmp-maven-plugin:0.26.1:cmp',
            'exec-maven-plugin:3.6.3:java (generate-deprecation-registry)',
            'DeprecationRegistryGenerator: wrote ' + str(product / 'compatibility/deprecations.json')
            + ' and ' + str(product / 'compatibility/DEPRECATIONS.md'),
            'Not compiling test sources', 'Tests are skipped.'])
        # Ordinary tests may print Maven-like banners and generator diagnostics.
        # These lines must neither reorder nor manufacture outer lifecycle events.
        noise = '\n'.join([
            'DeprecationRegistryGenerator: wrote fixture output',
            '[INFO] --- exec-maven-plugin:3.6.3:java (generate-deprecation-registry) @ fixture ---',
            '[INFO] --- japicmp-maven-plugin:0.26.1:cmp (default) @ fixture ---'])
        check_lifecycle(noise + '\n' + log, 'skip-compilation', product)
        positives += 1
        check_lifecycle(log, 'skip-compilation', product)
        positives += 1
        rejects('missing generator', lambda: check_lifecycle(log.replace('exec-maven-plugin:', 'missing:'), 'skip-compilation', product))
        rejects('wrong order', lambda: check_lifecycle('DeprecationRegistryGenerator: wrote \n' + log, 'skip-compilation', product))
        rejects('wrong cwd attribution', lambda: check_lifecycle(log.replace(str(product), str(fixture / 'wrong')), 'skip-compilation', product))
        rejects('stale registry', lambda: check_lifecycle(log, 'skip-compilation', product, time.time() + 10))
        test_output = product / 'target/test-classes'
        test_output.mkdir()
        rejects('stale test output', lambda: check_lifecycle(log, 'skip-compilation', product))
        test_output.rmdir()
        helper.unlink()
        rejects('missing helper output', lambda: check_lifecycle(log, 'skip-compilation', product))
    print('SELF-TEST PASSED: %d positive controls, %d negative controls: %s' %
          (positives, len(negatives), ', '.join(negatives)))


try:
    args = sys.argv[2:]
    if len(args) == 3 and args[0] == '--cell':
        cell(args[1], args[2])
    elif args == ['--matrix']:
        matrix()
    elif args == ['--external-cwd']:
        external_cwd()
    elif args == ['--artifacts']:
        artifacts()
    elif args == ['--self-test']:
        self_test()
    else:
        raise AssertionError('Usage: --cell {verify|install} {normal|skip-execution|skip-compilation} | --matrix | --external-cwd | --artifacts | --self-test')
except (AssertionError, OSError, ValueError, zipfile.BadZipFile) as error:
    print('Lifecycle check failed: ' + str(error), file=sys.stderr)
    sys.exit(1)
PY
