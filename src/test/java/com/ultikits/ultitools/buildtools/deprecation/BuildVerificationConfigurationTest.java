package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.yaml.snakeyaml.Yaml;

/** Configuration and isolated controls only: no publication scripts or product Maven recursion. */
class BuildVerificationConfigurationTest {
    private static final Pattern MAVEN_CALL = Pattern.compile(
            "(?m)(?:^\\h*(?:if\\h+)?|\\$\\()mvn\\h+([^\\r\\n]+)");
    private static final String COMPILER = "/project/build/plugins/plugin[artifactId='maven-compiler-plugin']";
    private static final String EXEC = "/project/build/plugins/plugin[artifactId='exec-maven-plugin']";

    // Minimal plugin-free Maven fixture only; never recursively build this product.
    private static final String LIFECYCLE_PROBE = String.join("\n",
            "import contextlib",
            "import io",
            "import json",
            "from pathlib import Path",
            "import subprocess",
            "import sys",
            "import types",
            "",
            "root, temporary, scenario = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]",
            "script = root / '.github/scripts/test-buildtool-lifecycle.sh'",
            "body = script.read_text().split(\"<<'PY'\\n\", 1)[1].rsplit('\\nPY', 1)[0]",
            "prefix = body.rsplit('\\ntry:\\n    args = sys.argv[2:]', 1)[0]",
            "sys.argv = [str(script), str(root)]",
            "namespace = {'__name__': 'bounded_lifecycle_regression'}",
            "exec(compile(prefix, str(script), 'exec'), namespace)",
            "if scenario == 'fixture':",
            "    # A virtual fixture-construction delay: no real clock changes, sleep, or retries.",
            "    # Positive synthetic lifecycle data must explicitly order its own timestamps.",
            "    namespace['time'] = types.SimpleNamespace(time=lambda: 1900000000.0)",
            "    text = io.StringIO()",
            "    try:",
            "        with contextlib.redirect_stdout(text):",
            "            namespace['self_test']()",
            "    except AssertionError as error:",
            "        print(json.dumps({'scenario': scenario, 'fixtureFailure': str(error)}))",
            "        sys.exit(1)",
            "    print(text.getvalue())",
            "    sys.exit(0)",
            "",
            "project = temporary / 'project'",
            "project.mkdir()",
            "(project / 'pom.xml').write_text('<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>control</groupId><artifactId>collector</artifactId><version>1</version><packaging>pom</packaging></project>')",
            "original = namespace['OBSERVER_SOURCE']",
            "records = []",
            "",
            "",
            "def execute(name, injection='', event_fault=False):",
            "    folder = temporary / name",
            "    folder.mkdir()",
            "    # Only the ephemeral observer fixture's writer is wrapped. Production source,",
            "    # close implementation, Maven dispatcher, CLI and reader remain the real ones.",
            "    anchor = 'emit(\"ObserverStarted\", new LinkedHashMap<String, String>());'",
            "    assert original.count(anchor) == 1",
            "    namespace['OBSERVER_SOURCE'] = original.replace(anchor, anchor + injection)",
            "    if event_fault:",
            "        event_anchor = 'emit(event.getType().name(), data);'",
            "        assert original.count(event_anchor) == 1",
            "        namespace['OBSERVER_SOURCE'] = namespace['OBSERVER_SOURCE'].replace(event_anchor,",
            "                event_anchor + '\\n            if (event.getType() == ExecutionEvent.Type.SessionEnded) { throw new java.io.IOException(\"CONTROLLED_EVENT_FAILURE\"); }')",
            "    observation = namespace['prepare_observer'](folder)",
            "    final = Path(observation['events'])",
            "    if name == 'init-conflict':",
            "        final.write_bytes(b'existing-final-sentinel')",
            "    if name == 'partial-conflict':",
            "        Path(str(final) + '.partial').write_bytes(b'partial-sentinel')",
            "    command = ['mvn', '-B', '-o', '-f', str(project / 'pom.xml'),",
            "               '-Dmaven.ext.class.path=' + observation['jar'],",
            "               '-Dp20.lifecycle.events=' + str(final),",
            "               '-Dp20.lifecycle.token=' + observation['token'], 'validate']",
            "    process = subprocess.run(command, cwd=root, capture_output=True, text=True)",
            "    log = process.stdout + process.stderr",
            "    (folder / 'maven.log').write_text(log)",
            "    try:",
            "        namespace['read_observation'](observation, project)",
            "        accepted, reason = True, ''",
            "    except (AssertionError, ValueError, OSError) as error:",
            "        accepted, reason = False, str(error)",
            "    record = {'name': name, 'command': command, 'mavenExit': process.returncode,",
            "              'readerAccepted': accepted, 'readerReason': reason,",
            "              'finalExists': final.exists(), 'partialExists': Path(str(final) + '.partial').exists(),",
            "              'finalText': final.read_text() if final.is_file() else '', 'mavenLog': log}",
            "    records.append(record)",
            "    return record",
            "",
            "",
            "positive = execute('positive')",
            "assert positive['mavenExit'] == 0 and positive['readerAccepted'], positive",
            "fault = execute('close-fault', '''",
            "        writer = new BufferedWriter(writer) {",
            "            @Override public void close() throws java.io.IOException {",
            "                super.close();",
            "                throw new java.io.IOException(\"CONTROLLED_CLOSE_AFTER_FLUSH\");",
            "            }",
            "        };",
            "''')",
            "assert fault['mavenExit'] == 0 and 'CONTROLLED_CLOSE_AFTER_FLUSH' in fault['mavenLog'], fault",
            "# Accumulate actual behavior before the target oracle; old implementation must RED",
            "# on accepted-after-close-failure, never on compilation/discovery/setup.",
            "if fault['readerAccepted']:",
            "    # Any accepted final after this real close failure violates the oracle;",
            "    # legacy marker bytes are independently preserved in the original RED record.",
            "    print(json.dumps({'scenario': scenario, 'closeFailureWronglyAccepted': True, 'records': records}))",
            "    sys.exit(1)",
            "assert not fault['finalExists'] and fault['partialExists'], fault",
            "publication = execute('unpublished-until-closed', '''",
            "        final java.nio.file.Path published = Paths.get(path);",
            "        writer = new BufferedWriter(writer) {",
            "            @Override public void close() throws java.io.IOException {",
            "                if (Files.exists(published)) { throw new java.io.IOException(\"PREMATURE_FINAL\"); }",
            "                super.close();",
            "            }",
            "        };",
            "''')",
            "assert publication['mavenExit'] == 0 and publication['readerAccepted']",
            "assert publication['partialExists'] and 'PREMATURE_FINAL' not in publication['mavenLog']",
            "published_path = Path(publication['command'][6].split('=', 1)[1])",
            "assert published_path.samefile(Path(str(published_path) + '.partial'))  # atomic no-replace link, not a copy",
            "health = execute('unhealthy-complete-trace', event_fault=True)",
            "assert health['mavenExit'] == 0 and 'CONTROLLED_EVENT_FAILURE' in health['mavenLog']",
            "assert health['partialExists'] and not health['finalExists'] and not health['readerAccepted']",
            "conflict = execute('init-conflict')",
            "assert not conflict['readerAccepted'] and conflict['finalText'] == 'existing-final-sentinel', conflict",
            "partial = execute('partial-conflict')",
            "assert not partial['readerAccepted'] and not partial['finalExists'], partial",
            "collision = execute('publication-conflict', '''",
            "        final java.nio.file.Path collision = Paths.get(path);",
            "        writer = new BufferedWriter(writer) {",
            "            @Override public void close() throws java.io.IOException {",
            "                super.close();",
            "                Files.write(collision, \"publication-sentinel\".getBytes(StandardCharsets.UTF_8),",
            "                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);",
            "            }",
            "        };",
            "''')",
            "assert collision['mavenExit'] == 0 and not collision['readerAccepted'], collision",
            "assert collision['finalText'] == 'publication-sentinel' and collision['partialExists'], collision",
            "# Actual duplicate registration remains fail-closed, independently of publication.",
            "folder = temporary / 'duplicate'",
            "folder.mkdir()",
            "namespace['OBSERVER_SOURCE'] = original",
            "observation = namespace['prepare_observer'](folder)",
            "import zipfile",
            "jar = folder / 'duplicate.jar'",
            "with zipfile.ZipFile(observation['jar']) as source, zipfile.ZipFile(jar, 'w') as target:",
            "    for name in source.namelist():",
            "        data = source.read(name)",
            "        if name == 'META-INF/plexus/components.xml':",
            "            text = data.decode()",
            "            component = text.split('<components>', 1)[1].split('</components>', 1)[0]",
            "            data = text.replace('</components>', component.replace('lifecycle-observer', 'duplicate-observer') + '</components>').encode()",
            "        target.writestr(name, data)",
            "command = ['mvn', '-B', '-o', '-f', str(project / 'pom.xml'), '-Dmaven.ext.class.path=' + str(jar),",
            "           '-Dp20.lifecycle.events=' + observation['events'], '-Dp20.lifecycle.token=' + observation['token'], 'validate']",
            "process = subprocess.run(command, cwd=root, capture_output=True, text=True)",
            "assert process.returncode != 0 and not Path(observation['events']).exists()",
            "records.append({'name': 'duplicate-registration', 'command': command, 'mavenExit': process.returncode,",
            "                'mavenLog': process.stdout + process.stderr, 'finalExists': False})",
            "print(json.dumps({'scenario': scenario, 'closeFailureWronglyAccepted': False, 'records': records}))");

    @Test
    void buildHelpersHaveIndependentCompilation() throws Exception {
        Document pom = pom();
        String execution = COMPILER + "/executions/execution[id='compile-deprecation-buildtools']";
        assertThat(value(pom, COMPILER + "/version")).isEqualTo("3.11.0");
        assertThat(value(pom, COMPILER + "/configuration/source")).isEqualTo("${java.version}");
        assertThat(value(pom, COMPILER + "/configuration/target")).isEqualTo("${java.version}");
        assertThat(value(pom, COMPILER + "/configuration/annotationProcessorPaths/path/artifactId"))
                .isEqualTo("lombok");
        assertThat(value(pom, COMPILER + "/configuration/proc")).isEmpty();
        assertThat(value(pom, execution + "/phase")).isEqualTo("process-classes");
        assertThat(value(pom, execution + "/goals/goal")).isEqualTo("compile");
        String config = execution + "/configuration";
        assertThat(value(pom, config + "/compileSourceRoots/compileSourceRoot"))
                .isEqualTo("${project.basedir}/src/test/java");
        assertThat(value(pom, config + "/includes/include"))
                .isEqualTo("com/ultikits/ultitools/buildtools/deprecation/**/*.java");
        assertThat(value(pom, config + "/excludes/exclude")).isEqualTo("**/*Test.java");
        assertThat(value(pom, config + "/outputDirectory"))
                .isEqualTo("${project.build.directory}/buildtools-classes");
        assertThat(value(pom, config + "/proc")).isEqualTo("none");
        assertThat(value(pom, "/project/properties/java.version")).isEqualTo("1.8");
        assertThat(value(pom, "count(//release)")).isEqualTo("0");
    }

    @Test
    void generatorUsesExplicitBuildOutputAfterJapicmp() throws Exception {
        Document pom = pom();
        assertThat(value(pom, EXEC + "/version")).isEqualTo("3.6.3");
        assertThat(value(pom, EXEC + "/configuration/classpathScope")).isEqualTo("compile");
        assertThat(value(pom, EXEC + "/configuration/additionalClasspathElements/additionalClasspathElement"))
                .isEqualTo("${project.build.directory}/buildtools-classes");
        assertThat(value(pom, EXEC + "/configuration/arguments/argument")).isEqualTo("${project.basedir}");
        assertThat(value(pom, EXEC + "/executions/execution[id='generate-deprecation-registry']/phase"))
                .isEqualTo("verify");
        assertThat(value(pom, EXEC + "/preceding-sibling::plugin[artifactId='japicmp-maven-plugin']/executions/execution/phase"))
                .isEqualTo("verify");
    }

    // Publication consumes the retained, accepted files and never builds (plan 21-06). The registry
    // generator and japicmp still run on every candidate: release.sh --prepare's `clean verify`
    // (releaseHelperVerifiesWithTests) is the build those files come from.
    @Test
    void githubPackagesDeploysTheRetainedFilesWithoutABuild() throws IOException {
        assertThat(calls(workflowRuns("publish-packages.yml", "publish-github")))
                .containsExactly("-B deploy:deploy-file -s \"$SETTINGS\" \\");
    }

    @Test
    void centralPublishesTheRetainedFilesWithoutABuild() throws IOException {
        assertThat(calls(workflowRuns("publish-packages.yml", "publish-central")))
                .containsExactly("-B -v | sed -n 's/^Maven home: //p')\"", "-B -f \"$PROJECT_POM\" \\");
    }

    @Test
    void releaseHelperVerifiesWithTests() throws IOException {
        assertThat(calls(read(Paths.get(".github/scripts/release.sh"))))
                .hasSize(2).anySatisfy(call -> assertThat(call).isEqualTo("-B clean verify"));
    }

    @Test
    void retainedSnapshotAndCiCallsHaveExplicitDispositions() throws IOException {
        List<String> snapshot = calls(workflowRuns("snapshot-publish.yml", "snapshot-publish"));
        // Version classification, the deploy step's own SNAPSHOT re-check, and the snapshot deploy.
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot).filteredOn(call -> call.startsWith("-B -q help:evaluate ")).hasSize(2);
        assertThat(snapshot).contains("-B deploy -DskipTests");
        assertThat(calls(workflowRuns("maven-ci.yml", "test")))
                .containsExactly("-B clean verify",
                        "-B test -Dgroups=isolated -DexcludedGroups=");
        assertThat(calls(workflowRuns("maven-ci.yml", "release-tooling")))
                .containsExactly("-B -ntp process-resources dependency:resolve-plugins");
    }

    @Test
    void bothDependabotEcosystemsTargetMain() throws IOException {
        for (Map<String, Object> update : dependabotUpdates()) {
            assertThat(update).as("explicit integration target for %s", update.get("package-ecosystem"))
                    .containsEntry("target-branch", "main");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependabotRetainsTwoMonthlyGroupedRootEcosystemsAndJdkIgnores() throws IOException {
        Map<String, Object> config = yaml(Paths.get(".github/dependabot.yml"));
        assertThat(config).containsOnlyKeys("version", "updates").containsEntry("version", 2);
        List<Map<String, Object>> updates = dependabotUpdates();
        assertThat(updates).extracting(update -> update.get("package-ecosystem"))
                .containsExactly("maven", "github-actions");
        for (Map<String, Object> update : updates) {
            assertThat(update).containsEntry("directory", "/");
            assertThat(update.get("schedule")).isEqualTo(java.util.Collections.singletonMap("interval", "monthly"));
            Map<String, Map<String, Object>> groups = (Map<String, Map<String, Object>>) update.get("groups");
            assertThat(groups).hasSize(1);
            assertThat(groups.values().iterator().next().get("patterns"))
                    .isEqualTo(java.util.Collections.singletonList("*"));
            if ("maven".equals(update.get("package-ecosystem"))) {
                assertThat(update).containsEntry("open-pull-requests-limit", 10);
                assertThat(groups.get("maven-minor-patch").get("update-types"))
                        .isEqualTo(java.util.Arrays.asList("minor", "patch"));
                List<Map<String, Object>> ignores = (List<Map<String, Object>>) update.get("ignore");
                assertThat(ignores).extracting(rule -> rule.get("dependency-name"))
                        .containsExactly("org.junit.jupiter:*", "com.mysql:mysql-connector-j", "org.mockito:*",
                                "io.papermc.paper:paper-api");
                assertThat(ignores).extracting(rule -> rule.get("versions")).containsExactly(
                        java.util.Collections.singletonList(">=6"), java.util.Collections.singletonList(">=10"),
                        java.util.Collections.singletonList(">=6"), java.util.Arrays.asList("<1.21", ">=1.22"));
            } else {
                assertThat(update).doesNotContainKey("open-pull-requests-limit");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ciRetainsRequiredMainCheckAndRunsLightweightSelfTests() throws IOException {
        Map<String, Object> workflow = yaml(Paths.get(".github/workflows/maven-ci.yml"));
        // SnakeYAML's YAML 1.1 resolver represents the unquoted GitHub Actions `on` key as true.
        Map<String, Object> triggers = (Map<String, Object>) workflow.get(Boolean.TRUE);
        assertThat(triggers).containsOnlyKeys("push", "pull_request");
        for (String event : java.util.Arrays.asList("push", "pull_request")) {
            assertThat(triggers.get(event)).isEqualTo(java.util.Collections.singletonMap(
                    "branches", java.util.Collections.singletonList("main")));
        }
        Map<String, Map<String, Object>> jobs = (Map<String, Map<String, Object>>) workflow.get("jobs");
        assertThat(jobs).containsOnlyKeys("test", "release-tooling");
        for (Map<String, Object> job : jobs.values()) {
            assertThat(job.get("permissions")).isEqualTo(java.util.Collections.singletonMap("contents", "read"));
            assertThat(job.get("runs-on")).isEqualTo("ubuntu-latest");
            assertThat(job).containsKey("timeout-minutes").doesNotContainKeys("if", "needs");
        }
        assertThat(jobs.get("test").get("name")).isEqualTo("🧪 Test & Coverage");
        assertThat(jobs.get("release-tooling").get("name")).isEqualTo("Release tooling regression suites");
        Map<String, Object> concurrency = (Map<String, Object>) workflow.get("concurrency");
        assertThat(concurrency).containsEntry("group",
                "${{ github.workflow }}-${{ github.event_name == 'pull_request' && github.ref || github.sha }}")
                .containsEntry("cancel-in-progress", "${{ github.event_name == 'pull_request' }}");
        String runs = workflowRuns("maven-ci.yml", "test");
        assertThat(runs.split("\\n")).contains("mvn -B clean verify", ".github/scripts/check-cjk-scope.sh",
                "bash .github/scripts/check-cjk-scope.sh --self-test",
                "bash .github/scripts/test-buildtool-lifecycle.sh --self-test");
        assertThat(runs).doesNotContain("--matrix", "check-comment-only-diff.sh --self-test",
                "setup-pinned-maven.sh", "unittest discover");
    }

    @Test
    void mainOnlyDoesNotRetainPromotionOrClosureAutomation() {
        assertThat(Paths.get(".github/workflows/pr-source-guard.yml")).doesNotExist();
        assertThat(Paths.get(".github/workflows/phase-closeout.yml")).doesNotExist();
        assertThat(Paths.get(".github/scripts/close_declared_issues.py")).doesNotExist();
    }

    @Test
    void lifecycleEvidenceIgnoresMavenLikeTestOutput(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        Path output = temporary.resolve("lifecycle-controls.log");
        Process process = new ProcessBuilder("bash", ".github/scripts/test-buildtool-lifecycle.sh", "--self-test")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("bounded isolated controls must terminate").isTrue();
        assertThat(process.exitValue()).as("Maven-like test stdout must not corrupt lifecycle evidence: %s",
                read(output)).isZero();
        assertThat(read(output)).contains("SELF-TEST PASSED");
    }

    @Test
    void observerCloseFailureCannotPublishLifecycleEvidence(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        String output = runLifecycleProbe(temporary, "close");
        assertThat(output).contains("\"closeFailureWronglyAccepted\": false", "publication-conflict",
                "duplicate-registration");
    }

    @Test
    void lifecycleFixturesDeclareCausalTimestamps(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        assertThat(runLifecycleProbe(temporary, "fixture")).contains("SELF-TEST PASSED");
    }

    private static String runLifecycleProbe(Path temporary, String scenario) throws Exception {
        Path output = temporary.resolve("probe.log");
        // Fixed argv: python3, a constant probe script, this checkout root, a @TempDir path and a test-chosen scenario.
        // nosemgrep: java_inject_rule-CommandInjection
        Process process = new ProcessBuilder("python3", "-I", "-c", LIFECYCLE_PROBE,
                Paths.get("").toAbsolutePath().toString(), temporary.toString(), scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("bounded native collector fixture must terminate").isTrue();
        String text = read(output);
        System.out.println("LIFECYCLE PROBE " + scenario + " " + text);
        assertThat(process.exitValue()).as("native lifecycle %s oracle: %s", scenario, text).isZero();
        return text;
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dependabotUpdates() throws IOException {
        List<Map<String, Object>> updates = (List<Map<String, Object>>) yaml(Paths.get(".github/dependabot.yml"))
                .get("updates");
        assertThat(updates).hasSize(2);
        return updates;
    }

    private static Map<String, Object> yaml(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new Yaml().load(input);
        }
    }

    private static Document pom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(Paths.get("pom.xml").toFile());
    }

    private static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static List<String> calls(String script) {
        List<String> result = new ArrayList<>();
        Matcher matcher = MAVEN_CALL.matcher(script);
        while (matcher.find()) {
            result.add(matcher.group(1).trim());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String workflowRuns(String name, String job) throws IOException {
        Map<String, Object> workflow;
        try (InputStream input = Files.newInputStream(Paths.get(".github/workflows", name))) {
            workflow = new Yaml().load(input);
        }
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        Map<String, Object> selected = (Map<String, Object>) jobs.get(job);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) selected.get("steps");
        StringBuilder scripts = new StringBuilder();
        for (Map<String, Object> step : steps) {
            if (step.containsKey("run")) {
                scripts.append(step.get("run")).append('\n');
            }
        }
        return scripts.toString();
    }
}
