#!/usr/bin/env bash
# Nonpublishing, offline real-Maven proof. Fake-Maven control flow lives in JUnit fixtures.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
exec python3 -I - "$ROOT" "$@" <<'PY'
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(sys.argv[1])
FQN = 'com.ultikits.ultitools.manager.UltiPanelLogTransmitter'
SCRIPT = Path('.github/scripts/japicmp-rehearsal.sh')


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(argv, cwd, folder, name, env=None):
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    begin = time.monotonic()
    with (folder / (name + '.log')).open('wb') as out:
        code = subprocess.run(argv, cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT).returncode
    row = {'command': argv, 'cwd': str(cwd), 'started': start, 'exitCode': code,
           'elapsedSeconds': time.monotonic() - begin}
    save(folder / (name + '.result.json'), row)
    return row


def manifest(repo, local):
    result = {}
    for label, base in [('repo', repo), ('local', local)]:
        for directory, dirs, files in os.walk(base):
            here = Path(directory)
            if label == 'repo' and here == repo:
                dirs[:] = [d for d in dirs if d not in ('.git', 'target')]
            for path in [here, *[here / name for name in files]]:
                require(not path.is_symlink(), 'Unexpected symlink in isolated proof: ' + str(path))
                info = {'mode': stat.S_IMODE(path.stat().st_mode),
                        'type': 'directory' if path.is_dir() else 'file'}
                if path.is_file():
                    info.update(bytes=path.stat().st_size, sha256=sha(path))
                result[label + '/' + str(path.relative_to(base))] = info
    index = repo / '.git/index'
    result['repo/.git/index'] = {'mode': stat.S_IMODE(index.stat().st_mode), 'bytes': index.stat().st_size,
                                 'sha256': sha(index), 'type': 'file'}
    return result


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0'})


def seed_cache(evidence, run, repo, local):
    # Reuse the earlier bounded dependency inventory, not a recursive copy of the user's .m2.
    receipt = json.loads((evidence / 'maven-cache-seed.json').read_text())
    seed = Path(receipt['repository'])
    require(seed.is_dir() and seed.is_relative_to(evidence), 'Missing bounded cache receipt: BLOCKED')
    selected = {row['path']: seed / row['path'] for row in receipt['files']}
    # The resolver is a read-only Maven help goal. Trace its actual offline cache reads in this
    # disposable source root, then add only those files to the isolated repository inventory.
    require(shutil.which('strace'), 'Offline resolver-cache preparation BLOCKED: strace unavailable')
    trace = run / 'resolver-cache.trace'
    row = command(['strace', '-f', '-e', 'trace=%file', '-o', str(trace),
                   'mvn', '-B', '-o', 'help:evaluate', '-Dexpression=settings.localRepository',
                   '-q', '-DforceStdout'], repo, run, 'resolver-cache')
    require(row['exitCode'] == 0, 'Offline resolver cache unavailable: BLOCKED; see resolver-cache.log')
    source = Path(receipt['sourceCache']).resolve(strict=True)
    for name in re.findall(r'"(/[^"\n]+)"', trace.read_text(errors='replace')):
        path = Path(name)
        if path.is_relative_to(source) and path.is_file():
            selected[str(path.relative_to(source))] = path
            marker = path.parent / '_remote.repositories'
            if marker.is_file():
                selected[str(marker.relative_to(source))] = marker
    require(all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in selected),
            'Cache receipt escaped its bounded root')
    require(all(path.is_file() and not path.is_symlink() for path in selected.values()),
            'Offline seed missing a required ordinary cache file: BLOCKED')
    total = sum(path.stat().st_size for path in selected.values())
    require(total < 512 * 1024 * 1024, 'Offline seed exceeds 512 MiB bound')
    rows = []
    for name, path in sorted(selected.items()):
        dest = local / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        rows.append({'path': name, 'bytes': dest.stat().st_size, 'sha256': sha(dest)})
    save(run / 'cache-seed.json', {'bytes': total, 'files': rows,
                                  'meaning': 'Finite cached dependencies only; offline rehearsal still must pass'})


def actual():
    value = os.environ.get('P20_1_EVIDENCE')
    require(value, 'P20_1_EVIDENCE must name a private evidence directory')
    evidence = Path(value).resolve(strict=True)
    require(evidence.is_dir() and stat.S_IMODE(evidence.stat().st_mode) & 0o077 == 0,
            'Evidence must be private')
    require(not evidence.is_relative_to(ROOT) and 'target' not in evidence.parts,
            'Evidence must survive target cleanup outside the checkout')
    parent = evidence / 'plan05'
    parent.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='actual-', dir=parent))
    scratch = run / 'scratch'
    repo = scratch / 'repo'
    repo.mkdir(parents=True)
    local = scratch / 'local-repository'
    local.mkdir()
    head = git(ROOT, 'rev-parse', 'HEAD').decode().strip()
    summary = {'sourceHead': head, 'scriptSha256': sha(ROOT / SCRIPT), 'status': 'failed',
               'release_authorized': False, 'run': str(run), 'cases': []}
    save(run / 'result.json', summary)
    try:
        # Only committed build inputs, never ignored docs/server/credential trees. The tested
        # script is deliberately overlaid so this harness can verify its own precommit change.
        archive = git(ROOT, 'archive', '--format=tar', head, 'pom.xml', 'src', 'compatibility', '.github', '.gitignore')
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(repo, filter='data')
        shutil.copy2(ROOT / SCRIPT, repo / SCRIPT)
        git(repo, 'init', '-q')
        git(repo, 'config', 'user.name', 'Rehearsal Fixture')
        git(repo, 'config', 'user.email', 'rehearsal@example.invalid')
        git(repo, 'add', 'pom.xml', 'src', 'compatibility', '.github', '.gitignore')
        git(repo, 'commit', '-q', '-m', 'isolated rehearsal inputs')
        seed_cache(evidence, run, repo, local)
        # A wrapper delegates EVERY invocation to real Maven; it only archives the three legs
        # and injects the isolated local-repository selection, including the help resolver.
        real_mvn = shutil.which('mvn')
        require(real_mvn is not None, 'Maven unavailable: BLOCKED')
        wrapper = scratch / 'bin'
        wrapper.mkdir()
        (wrapper / 'mvn').write_text('''#!/usr/bin/env -S python3 -I
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
root = Path.cwd()
out = Path(os.environ['REHEARSAL_ACTUAL_CASE'])
args = sys.argv[1:]
if any('help:evaluate' in a for a in args):
    os.execv(os.environ['REHEARSAL_REAL_MVN'], [os.environ['REHEARSAL_REAL_MVN'],
               '-Dmaven.repo.local=' + os.environ['REHEARSAL_LOCAL'], *args])
leg = 'candidate' if 'install' in args else ('red' if 'public final class UltiPanelLogTransmitter'
       in (root / 'src/main/java/com/ultikits/ultitools/manager/UltiPanelLogTransmitter.java').read_text() else 'green')
argv = [os.environ['REHEARSAL_REAL_MVN'], '-Dmaven.repo.local=' + os.environ['REHEARSAL_LOCAL'], *args]
start = time.time()
with (out / (leg + '.log')).open('wb') as log:
    rc = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT).returncode
sys.stdout.write((out / (leg + '.log')).read_text(errors='replace'))
xml = root / 'target/japicmp/japicmp.xml'
if xml.is_file():
    shutil.copy2(xml, out / (leg + '-japicmp.xml'))
(out / (leg + '.result.json')).write_text(json.dumps({'command': argv, 'exitCode': rc,
    'startedEpoch': start, 'elapsedSeconds': time.time()-start}, indent=2) + '\\n')
sys.exit(rc)
''', encoding='utf-8')
        (wrapper / 'mvn').chmod(0o700)
        # Pre-existing candidate bytes and coordinate metadata must survive, even though install
        # overwrites them during the rehearsal. An unrelated cache sentinel is outside that family.
        family = local / 'com/ultikits/UltiTools-API'
        candidate = family / '6.3.0'
        candidate.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(candidate / 'UltiTools-API-6.3.0.jar', 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nFixture: pre-existing\n')
        (candidate / 'original-sentinel').write_bytes(b'pre-existing coordinate subtree\r\n')
        metadata = family / 'maven-metadata-local.xml'
        metadata.write_text('<metadata><groupId>com.ultikits</groupId><artifactId>UltiTools-API</artifactId>'
                            '<versioning><release>6.3.0</release><versions><version>6.3.0</version></versions>'
                            '<lastUpdated>20260101000000</lastUpdated></versioning></metadata>\n')
        metadata.chmod(0o640)
        (local / 'unrelated-sentinel').write_bytes(b'not owned by rehearsal\n')
        for variant in ('dirty', 'absent'):
            case = run / variant
            case.mkdir()
            json_path = repo / 'compatibility/deprecations.json'
            md_path = repo / 'compatibility/DEPRECATIONS.md'
            # Whitespace is valid JSON; two distinct states prove partial staging survives.
            json_path.write_bytes(json_path.read_bytes().rstrip() + b' \n')
            git(repo, 'add', 'compatibility/deprecations.json')
            json_path.write_bytes(json_path.read_bytes() + b'\r\n  ')
            json.loads(json_path.read_text())
            if variant == 'dirty':
                md_path.write_bytes(md_path.read_bytes() + b'\n<!-- operator draft, preserve literally -->\r\n')
                md_path.chmod(0o640)
                (repo / '.flattened-pom.xml').write_bytes(b'pre-existing root flatten sentinel\r\n')
                (repo / '.flattened-pom.xml').chmod(0o600)
            else:
                md_path.unlink()
                (repo / '.flattened-pom.xml').unlink()
            before = manifest(repo, local)
            save(case / 'before.json', before)
            env = {**os.environ, 'GIT_OPTIONAL_LOCKS': '0', 'PATH': str(wrapper) + os.pathsep + os.environ['PATH'],
                   'REHEARSAL_ACTUAL_CASE': str(case), 'REHEARSAL_REAL_MVN': real_mvn,
                   'REHEARSAL_LOCAL': str(local), 'TMPDIR': str(case)}
            row = command(['bash', str(repo / SCRIPT)], repo, case, 'rehearsal', env)
            after = manifest(repo, local)
            save(case / 'after.json', after)
            row['variant'] = variant
            row['restoredExactly'] = after == before
            summary['cases'].append(row)
            save(run / 'result.json', summary)
            require(after == before, 'Actual rehearsal changed protected bytes/existence/modes/index or cache; see manifests')
            require(row['exitCode'] == 0, 'Actual rehearsal did not prove GREEN/RED; inspect ' + str(case))
            legs = {leg: json.loads((case / (leg + '.result.json')).read_text()) for leg in ('candidate', 'green', 'red')}
            require(legs['candidate']['exitCode'] == 0 and legs['green']['exitCode'] == 0
                    and legs['red']['exitCode'] != 0, 'Actual Maven legs did not have required exits')
            xml = ET.parse(case / 'red-japicmp.xml').getroot()
            classes = [c for c in xml.findall('./classes/class') if c.get('fullyQualifiedName') == FQN]
            require(len(classes) == 1 and classes[0].get('binaryCompatible') == 'false'
                    and any(c.get('type') == 'CLASS_NOW_NOT_EXTENDABLE' and c.get('binaryCompatible') == 'false'
                            for c in classes[0].findall('./compatibilityChanges/compatibilityChange')),
                    'Actual XML did not prove the exact intended binary break')
            log = re.sub(r'\x1b\[[0-9;]*m', '', (case / 'red.log').read_text())
            require('Compiling ' in log and 'with javac' in log and 'COMPILATION ERROR' not in log
                    and re.search(r'Failed to execute goal [^\s]*japicmp-maven-plugin:[^\s:]+:cmp', log),
                    'Actual RED was not a compiled japicmp failure')
            require('Attributed RED: ' + FQN + ' CLASS_NOW_NOT_EXTENDABLE' in (case / 'rehearsal.log').read_text(),
                    'Real script did not acknowledge parsed attribution')
            row['legs'] = legs
            row['exactBinaryBreak'] = FQN + ' CLASS_NOW_NOT_EXTENDABLE'
            save(case / 'result.json', row)
        summary['status'] = 'passed'
        summary['scratchBytesBeforeDisposal'] = sum(p.stat().st_size for p in scratch.rglob('*') if p.is_file())
        shutil.rmtree(scratch)
        summary['scratchDisposed'] = True
        print('ACTUAL REHEARSAL PASSED: real offline candidate / GREEN 0 / attributed RED; exact restoration in both variants')
    except Exception as exc:
        summary['error'] = str(exc)
        summary['scratchRetainedForDiagnosis'] = str(scratch)
        raise
    finally:
        save(run / 'result.json', summary)
        print('Evidence: ' + str(run), flush=True)


if sys.argv[2:] != ['--actual']:
    print('Usage: test-japicmp-rehearsal.sh --actual', file=sys.stderr)
    sys.exit(64)
actual()
PY
