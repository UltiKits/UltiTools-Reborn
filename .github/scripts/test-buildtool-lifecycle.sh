#!/usr/bin/env bash
# Nonpublishing lifecycle assertions. Evidence survives Maven clean outside target.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
exec python3 -I - "$ROOT" "$@" <<'PY'
import datetime
import copy
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
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(sys.argv[1])
HELPER = Path('com/ultikits/ultitools/buildtools/deprecation/DeprecationRegistryGenerator.class')
HELPER_PACKAGE = 'com/ultikits/ultitools/buildtools/deprecation/'
OBSERVER_PACKAGE = 'lifecycle/observer/'
OBSERVER_SOURCE = r'''package lifecycle.observer;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Invocation-local observer, never compiled into the product or test classpath. */
public final class LifecycleObserver implements EventSpy {
    private BufferedWriter writer;
    private Path finalPath;
    private Path partialPath;
    private String token;
    private int sequence;
    private MavenSession outer;
    private boolean failed;

    public synchronized void init(Context context) throws Exception {
        Properties properties = (Properties) context.getData().get("userProperties");
        token = properties.getProperty("p20.lifecycle.token");
        String path = properties.getProperty("p20.lifecycle.events");
        if (token == null || path == null) {
            throw new IllegalStateException("Missing lifecycle observer invocation identity");
        }
        if (System.getProperties().putIfAbsent("p20.lifecycle.claim." + token, "claimed") != null) {
            System.setProperty("p20.lifecycle.duplicate." + token, "true");
            throw new IllegalStateException("Duplicate lifecycle observer");
        }
        finalPath = Paths.get(path).toAbsolutePath().normalize();
        partialPath = finalPath.resolveSibling(finalPath.getFileName() + ".partial");
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Lifecycle final file already exists");
        }
        writer = Files.newBufferedWriter(partialPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        emit("ObserverStarted", new LinkedHashMap<String, String>());
    }

    public synchronized void onEvent(Object object) throws Exception {
        if (!(object instanceof ExecutionEvent)) {
            return;
        }
        try {
            ExecutionEvent event = (ExecutionEvent) object;
            MavenSession session = event.getSession();
            if (event.getType() == ExecutionEvent.Type.SessionStarted) {
                if (outer != null) {
                    throw new IllegalStateException("Duplicate Maven session");
                }
                outer = session;
            }
            // ProjectDiscoveryStarted precedes SessionStarted and has no selected root.
            if (outer == null) {
                return;
            }
            if (outer != session) {
                throw new IllegalStateException("Foreign Maven session");
            }
            Map<String, String> data = new LinkedHashMap<String, String>();
            data.put("session", "outer");
            File root = session.getTopLevelProject().getBasedir().getCanonicalFile();
            data.put("root", root.toString());
            data.put("project", event.getProject() == null ? "" : event.getProject().getBasedir().getCanonicalPath());
            data.put("skipTests", session.getUserProperties().getProperty("skipTests", "false"));
            data.put("mavenTestSkip", session.getUserProperties().getProperty("maven.test.skip", "false"));
            data.put("sessionErrors", Boolean.toString(session.getResult().hasExceptions()));
            MojoExecution mojo = event.getMojoExecution();
            if (mojo != null) {
                data.put("groupId", mojo.getGroupId());
                data.put("artifactId", mojo.getArtifactId());
                data.put("version", mojo.getVersion());
                data.put("goal", mojo.getGoal());
                data.put("executionId", mojo.getExecutionId());
                Xpp3Dom config = mojo.getConfiguration();
                for (String key : new String[] {"outputDirectory", "mainClass", "skip", "skipTests"}) {
                    Xpp3Dom child = config == null ? null : config.getChild(key);
                    data.put("config." + key, child == null || child.getValue() == null ? "" : child.getValue());
                }
                Xpp3Dom arguments = config == null ? null : config.getChild("arguments");
                StringBuilder values = new StringBuilder();
                if (arguments != null) {
                    for (Xpp3Dom argument : arguments.getChildren("argument")) {
                        if (values.length() > 0) {
                            values.append('\n');
                        }
                        values.append(argument.getValue());
                    }
                }
                data.put("arguments", values.toString());
                if ("generate-deprecation-registry".equals(mojo.getExecutionId())
                        && event.getType() == ExecutionEvent.Type.MojoSucceeded) {
                    for (String name : new String[] {"deprecations.json", "DEPRECATIONS.md"}) {
                        File output = new File(root, "compatibility/" + name).getCanonicalFile();
                        data.put(name + ".path", output.toString());
                        data.put(name + ".mtime", Long.toString(output.lastModified()));
                        byte[] bytes = Files.readAllBytes(output.toPath());
                        StringBuilder hash = new StringBuilder();
                        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                            hash.append(String.format("%02x", part & 255));
                        }
                        data.put(name + ".sha256", hash.toString());
                    }
                }
            }
            emit(event.getType().name(), data);
        } catch (Exception exception) {
            failed = true;
            throw exception;
        }
    }

    public synchronized void close() throws Exception {
        if (writer != null) {
            writer.close();
            if (failed || outer == null || Boolean.getBoolean("p20.lifecycle.duplicate." + token)) {
                throw new IllegalStateException("Unhealthy lifecycle observer; partial retained");
            }
            // The final name is the publication signal, not an in-stream close claim.
            // A same-directory native link atomically creates a name WITHOUT replacement;
            // ATOMIC_MOVE can overwrite an existing target on supported JDK providers.
            // Both names retain the same closed bytes. No fallible cleanup follows publish,
            // and unsupported link creation fails closed without a weaker fallback.
            Files.createLink(finalPath, partialPath);
        }
    }

    private void emit(String type, Map<String, String> data) throws Exception {
        data.put("type", type);
        data.put("token", token);
        data.put("sequence", Integer.toString(sequence++));
        data.put("timeMillis", Long.toString(System.currentTimeMillis()));
        StringBuilder line = new StringBuilder("{");
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (line.length() > 1) {
                line.append(',');
            }
            line.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        writer.write(line.append('}').toString());
        writer.newLine();
        writer.flush();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            if (character < 32 || character == '"' || character == '\\') {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
'''
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
    require(not any(package in entry for entry in entries for package in (HELPER_PACKAGE, OBSERVER_PACKAGE)), 'Build helper leaked into ' + str(path))
    return {'path': str(path), 'kind': kind, 'sha256': digest(path),
            'bytes': path.stat().st_size, 'positiveEntries': POSITIVES[kind], 'entries': entries}


def prepare_observer(folder):
    # Bind to the APIs of the Maven executable being invoked, not a project dependency.
    maven = Path(shutil.which('mvn')).resolve()
    libraries = maven.parent.parent / 'lib'
    require(libraries.is_dir(), 'Cannot locate installed Maven observer API')
    build = folder / 'observer'
    build.mkdir()
    classes = build / 'classes'
    classes.mkdir()
    java = build / 'LifecycleObserver.java'
    java.write_text(OBSERVER_SOURCE, encoding='utf-8')
    command = ['javac', '-proc:none', '-classpath', str(libraries / '*'), '-d', str(classes), str(java)]
    with (build / 'javac.log').open('wb') as output:
        status = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT).returncode
    require(status == 0, 'Observer compilation failed; inspect ' + str(build / 'javac.log'))
    jar = build / 'observer.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, str(path.relative_to(classes)))
        archive.writestr('META-INF/plexus/components.xml',
                         '<component-set><components><component>'
                         '<role>org.apache.maven.eventspy.EventSpy</role>'
                         '<role-hint>lifecycle-observer</role-hint>'
                         '<implementation>lifecycle.observer.LifecycleObserver</implementation>'
                         '</component></components></component-set>')
    return {'token': uuid.uuid4().hex, 'events': str(folder / 'events.jsonl'),
            'partial': str(folder / 'events.jsonl.partial'),
            'jar': str(jar), 'jarSha256': digest(jar), 'compileCommand': command}


def read_observation(observation, root):
    require(observation is not None, 'Missing native lifecycle observation')
    path = Path(observation['events'])
    require(path.is_file(), 'Missing native event file')
    events = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    require(len(events) >= 5, 'Incomplete native observation')
    require(all(isinstance(event, dict) for event in events), 'Invalid native event')
    require([event.get('sequence') for event in events] == [str(i) for i in range(len(events))],
            'Missing or duplicate native event sequence')
    require(all(event.get('token') == observation['token'] for event in events), 'Wrong invocation token')
    require([event.get('type') for event in events].count('ObserverStarted') == 1
            and events[0]['type'] == 'ObserverStarted', 'Missing or duplicate observer start')
    # Only close-and-health-success can create the final name. Never inspect partials
    # or trust a success line emitted before the final I/O operation.
    body = events[1:]
    require(all(event.get('session') == 'outer' for event in body), 'Wrong native session')
    require(all(event.get('root') == str(root.resolve()) for event in body), 'Wrong native root')
    require(all(event.get('project') in ('', str(root.resolve())) for event in body), 'Wrong native project')
    require(all(event.get('sessionErrors') == 'false' for event in body), 'Native session failed')
    types = [event['type'] for event in body]
    for name in ('SessionStarted', 'SessionEnded', 'ProjectStarted', 'ProjectSucceeded'):
        require(types.count(name) == 1, 'Missing or duplicate ' + name)
    require(body[0]['type'] == 'SessionStarted' and body[-1]['type'] == 'SessionEnded',
            'Wrong native session boundaries')
    require(types.index('SessionStarted') < types.index('ProjectStarted')
            < types.index('ProjectSucceeded') < types.index('SessionEnded'), 'Wrong native project order')
    require(not any(event['type'].endswith(('Failed', 'Skipped')) for event in body),
            'Native execution failed or skipped')
    return events


def mojo_pair(events, identity):
    fields = ('groupId', 'artifactId', 'version', 'goal', 'executionId')
    selected = [event for event in events if tuple(event.get(key) for key in fields) == identity]
    require([event['type'] for event in selected] == ['MojoStarted', 'MojoSucceeded'],
            'Missing, duplicate or unsuccessful native mojo: ' + ':'.join(identity))
    return selected


def check_lifecycle(log, mode, root, started=None, observation=None):
    # log is retained for diagnostics ONLY. Test stdout cannot create execution evidence.
    events = read_observation(observation, root)
    compiler = mojo_pair(events, ('org.apache.maven.plugins', 'maven-compiler-plugin', '3.11.0',
                                  'compile', 'compile-deprecation-buildtools'))
    japicmp = mojo_pair(events, ('com.github.siom79.japicmp', 'japicmp-maven-plugin', '0.26.1', 'cmp', 'default'))
    generator = mojo_pair(events, ('org.codehaus.mojo', 'exec-maven-plugin', '3.6.3',
                                   'java', 'generate-deprecation-registry'))
    positions = [int(event['sequence']) for pair in (compiler, japicmp, generator) for event in pair]
    project_start = next(int(event['sequence']) for event in events if event['type'] == 'ProjectStarted')
    project_end = next(int(event['sequence']) for event in events if event['type'] == 'ProjectSucceeded')
    require(project_start < positions[0] and positions == sorted(set(positions))
            and positions[-1] < project_end, 'Wrong native lifecycle order')
    require(compiler[0].get('config.outputDirectory') in (
            str(root / 'target/buildtools-classes'), '${project.build.directory}/buildtools-classes'),
            'Wrong dedicated compiler output')
    require(generator[0].get('config.mainClass') ==
            'com.ultikits.ultitools.buildtools.deprecation.DeprecationRegistryGenerator', 'Wrong generator main class')
    require(generator[0].get('arguments') in (str(root), '${project.basedir}'), 'Wrong generator basedir argument')
    require((root / 'target/buildtools-classes' / HELPER).is_file(), 'Missing dedicated helper output')
    for package in (HELPER_PACKAGE, OBSERVER_PACKAGE):
        require(not (root / 'target/classes' / package).exists(), 'Build helper leaked into product classes')
    for name in ('deprecations.json', 'DEPRECATIONS.md'):
        output = root / 'compatibility' / name
        require(output.is_file() and output.stat().st_size > 0, 'Missing registry output: ' + name)
        success = generator[1]
        require(success.get(name + '.path') == str(output.resolve()), 'Wrong native registry path: ' + name)
        require(success.get(name + '.sha256') == digest(output), 'Registry differs from native mojo output: ' + name)
        require(int(success[name + '.mtime']) >= int(generator[0]['timeMillis']), 'Stale native registry output: ' + name)
        if started is not None:
            require(output.stat().st_mtime >= started, 'Stale registry output: ' + name)
    expected_flags = ('true' if mode == 'skip-execution' else 'false',
                      'true' if mode == 'skip-compilation' else 'false')
    require(all((event.get('skipTests'), event.get('mavenTestSkip')) == expected_flags
                for event in events[1:]), 'Wrong native test flags')
    mojo_pair(events, ('org.apache.maven.plugins', 'maven-surefire-plugin', '3.2.5', 'test', 'default-test'))
    reports = list((root / 'target/surefire-reports').glob('TEST-*.xml'))
    if mode == 'skip-compilation':
        require(not (root / 'target/test-classes').exists(), 'Clean skip-compilation used test output')
    else:
        require((root / 'target/test-classes' / HELPER).is_file(), 'Expected normal test compilation')
    if mode != 'normal':
        require(not reports, 'Skipped execution produced test reports')
    else:
        require(reports, 'No actual Surefire test reports')
        count = 0
        for report in reports:
            if started is not None:
                require(report.stat().st_mtime >= started, 'Stale Surefire report')
            suite = ET.parse(report).getroot()
            require(suite.tag == 'testsuite' and len(suite.findall('testcase')) == int(suite.attrib['tests']),
                    'Incomplete Surefire report')
            require(all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped')),
                    'Unsuccessful actual test execution')
            count += int(suite.attrib['tests'])
        require(count > 0, 'No successful nonempty test execution')
    return {'source': 'Maven ExecutionEvent via invocation-local EventSpy',
            'compiler': positions[:2], 'japicmp': positions[2:4], 'generator': positions[4:],
            'eventFile': observation['events'], 'eventSha256': digest(Path(observation['events'])),
            'actualTests': count if mode == 'normal' else 0}


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
    observation = prepare_observer(run)
    command += ['-Dmaven.ext.class.path=' + observation['jar'],
                '-Dp20.lifecycle.events=' + observation['events'],
                '-Dp20.lifecycle.token=' + observation['token']]
    command += ['clean', goal] + FLAGS[mode]
    record, log = run_maven(command, cwd, run)
    record['mode'] = mode
    record['observation'] = observation
    try:
        require(record['exitCode'] == 0, 'Maven lifecycle failed (exit %d); inspect %s' % (record['exitCode'], record['log']))
        started = datetime.datetime.fromisoformat(record['started']).timestamp()
        record['order'] = check_lifecycle(log, mode, ROOT, started, observation)
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

    def rejects(name, action, reason):
        try:
            action()
        except (AssertionError, OSError, ValueError, zipfile.BadZipFile) as error:
            require(reason in str(error), 'Wrong negative-control failure for ' + name + ': ' + str(error))
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
        rejects('missing artifact', lambda: check_artifact(fixture / 'missing.jar', 'main'), 'Missing exact')
        rejects('wrong artifact', lambda: check_artifact(jars['sources'], 'main'), 'Missing product positive')
        for package in (HELPER_PACKAGE, OBSERVER_PACKAGE):
            for kind, path in jars.items():
                with zipfile.ZipFile(path, 'a') as archive:
                    archive.writestr(package + 'Leaked.class', b'leak')
                rejects(kind + ' ' + package + ' leakage', lambda p=path, k=kind: check_artifact(p, k), 'Build helper leaked')
                # Re-establish a positive JAR before the next leakage mutation.
                with zipfile.ZipFile(path, 'w') as archive:
                    for entry in POSITIVES[kind]:
                        archive.writestr(entry, b'product positive control')
                check_artifact(path, kind)
        product = fixture / 'project'
        helper = product / 'target/buildtools-classes' / HELPER
        helper.parent.mkdir(parents=True)
        helper.write_bytes(b'compiled helper positive control')
        (product / 'compatibility').mkdir()
        # Explicit virtual causal order: generator starts at T, outputs are written at
        # T+1 ms. Fixture construction time and filesystem scheduling are irrelevant.
        now = '1600000000000'
        output_ns = (int(now) + 1) * 1000000
        for name in ('deprecations.json', 'DEPRECATIONS.md'):
            output = product / 'compatibility' / name
            output.write_text('positive generated output')
            os.utime(output, ns=(output_ns, output_ns))
        observation = {'events': str(fixture / 'events.jsonl'), 'token': 'control-invocation'}
        common = {'session': 'outer', 'root': str(product), 'project': str(product),
                  'skipTests': 'false', 'mavenTestSkip': 'true', 'sessionErrors': 'false'}
        events = [{'type': 'ObserverStarted'}, dict(common, type='SessionStarted'),
                  dict(common, type='ProjectStarted')]
        for group, artifact, version, goal, execution in (
                ('org.apache.maven.plugins', 'maven-compiler-plugin', '3.11.0', 'compile', 'compile-deprecation-buildtools'),
                ('org.apache.maven.plugins', 'maven-surefire-plugin', '3.2.5', 'test', 'default-test'),
                ('com.github.siom79.japicmp', 'japicmp-maven-plugin', '0.26.1', 'cmp', 'default'),
                ('org.codehaus.mojo', 'exec-maven-plugin', '3.6.3', 'java', 'generate-deprecation-registry')):
            mojo = dict(common, groupId=group, artifactId=artifact, version=version, goal=goal, executionId=execution,
                        arguments=str(product), **{'config.outputDirectory': str(product / 'target/buildtools-classes'),
                        'config.mainClass': 'com.ultikits.ultitools.buildtools.deprecation.DeprecationRegistryGenerator'})
            events.extend([dict(mojo, type='MojoStarted'), dict(mojo, type='MojoSucceeded')])
        for name in ('deprecations.json', 'DEPRECATIONS.md'):
            output = product / 'compatibility' / name
            events[-1].update({name + '.path': str(output), name + '.mtime': str(output.stat().st_mtime_ns // 1000000),
                               name + '.sha256': digest(output)})
        events += [dict(common, type='ProjectSucceeded'), dict(common, type='SessionEnded')]

        def save(rows):
            for i, row in enumerate(rows):
                row.update(sequence=str(i), token=row.get('token', observation['token']), timeMillis=now)
            Path(observation['events']).write_text(''.join(json.dumps(row) + '\n' for row in rows))

        save(events)
        noise = '\n'.join([
            'DeprecationRegistryGenerator: wrote fixture output',
            '[INFO] --- exec-maven-plugin:3.6.3:java (generate-deprecation-registry) @ fixture ---',
            '[INFO] --- japicmp-maven-plugin:0.26.1:cmp (default) @ fixture ---',
            'maven-compiler-plugin:3.11.0:compile (compile-deprecation-buildtools)',
            'Tests run: 99999, Failures: 0, Errors: 0, Skipped: 0',
            json.dumps(events[-1])])
        check_lifecycle(noise, 'skip-compilation', product, observation=observation)
        check_lifecycle('', 'skip-compilation', product, observation=observation)
        positives += 2
        rejects('stdout cannot replace observer', lambda: check_lifecycle(noise, 'skip-compilation', product), 'Missing native')
        final = Path(observation['events'])
        partial = final.with_suffix('.partial')
        final.rename(partial)
        rejects('unpublished partial', lambda: check_lifecycle(noise, 'skip-compilation', product,
                observation=observation), 'Missing native event file')
        partial.rename(final)

        def mutation(name, change, reason):
            rows = copy.deepcopy(events)
            change(rows)
            save(rows)
            rejects(name, lambda: check_lifecycle(noise, 'skip-compilation', product, observation=observation), reason)
            save(events)
            check_lifecycle(noise, 'skip-compilation', product, observation=observation)

        mutation('missing generator', lambda rows: rows.__delitem__(slice(9, 11)), 'native mojo')
        mutation('wrong order', lambda rows: rows.__setitem__(slice(3, 5), [rows[4], rows[3]]), 'native mojo')
        mutation('wrong cross-mojo order', lambda rows: rows.__setitem__(slice(7, 11), rows[9:11] + rows[7:9]), 'lifecycle order')
        mutation('wrong root', lambda rows: rows[3].update(root=str(fixture / 'wrong')), 'Wrong native root')
        mutation('wrong project', lambda rows: rows[3].update(project=str(fixture / 'wrong')), 'Wrong native project')
        mutation('wrong session', lambda rows: rows[3].update(session='inner'), 'Wrong native session')
        mutation('wrong token', lambda rows: rows[3].update(token='old-invocation'), 'Wrong invocation token')
        mutation('missing session end', lambda rows: rows.pop(), 'Missing or duplicate SessionEnded')
        mutation('failed observer session', lambda rows: rows[-1].update(sessionErrors='true'), 'Native session failed')
        mutation('duplicate observer', lambda rows: rows.insert(1, dict(rows[0])), 'duplicate observer start')
        mutation('duplicate session', lambda rows: rows.insert(2, dict(rows[1])), 'duplicate SessionStarted')
        mutation('duplicate generator', lambda rows: rows.insert(11, dict(rows[10])), 'native mojo')
        mutation('failed generator', lambda rows: rows[10].update(type='MojoFailed'), 'failed or skipped')
        mutation('wrong compiler identity', lambda rows: rows[3].update(version='other'), 'native mojo')
        mutation('wrong compiler output', lambda rows: rows[3].update(**{'config.outputDirectory': str(fixture)}), 'compiler output')
        mutation('wrong generator argument', lambda rows: rows[9].update(arguments=str(fixture)), 'basedir argument')
        mutation('wrong output attribution', lambda rows: rows[10].update(**{'deprecations.json.path': str(fixture)}), 'registry path')
        mutation('stale native output', lambda rows: rows[10].update(**{'deprecations.json.mtime': '1'}), 'Stale native')
        mutation('wrong output bytes', lambda rows: rows[10].update(**{'deprecations.json.sha256': 'old'}), 'Registry differs')
        mutation('wrong flags', lambda rows: rows[3].update(skipTests='true'), 'test flags')
        rejects('stale registry', lambda: check_lifecycle(noise, 'skip-compilation', product,
                time.time() + 10, observation), 'Stale registry')
        test_output = product / 'target/test-classes'
        test_output.mkdir()
        rejects('stale test output', lambda: check_lifecycle(noise, 'skip-compilation', product,
                observation=observation), 'skip-compilation used test output')
        test_output.rmdir()
        helper.unlink()
        rejects('missing helper output', lambda: check_lifecycle(noise, 'skip-compilation', product,
                observation=observation), 'Missing dedicated helper')
        helper.write_bytes(b'compiled helper positive control')
        # Boundary neighbors: both other flag modes, real report positives, fake summary rejection.
        compiled_test = product / 'target/test-classes' / HELPER
        compiled_test.parent.mkdir(parents=True)
        compiled_test.write_bytes(b'test compiled helper')
        for mode in ('normal', 'skip-execution'):
            for row in events[1:]:
                row.update(skipTests='true' if mode == 'skip-execution' else 'false', mavenTestSkip='false')
            save(events)
            if mode == 'normal':
                rejects('fake test summary', lambda: check_lifecycle(noise, mode, product,
                        observation=observation), 'No actual Surefire')
                reports = product / 'target/surefire-reports'
                reports.mkdir()
                report = reports / 'TEST-control.xml'
                report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="positive"/></testsuite>')
            check_lifecycle(noise, mode, product, observation=observation)
            positives += 1
            if mode == 'normal':
                report.unlink()
                reports.rmdir()
    print('SELF-TEST PASSED: %d positive controls, %d negative controls: %s; stdout noise ignored' %
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
