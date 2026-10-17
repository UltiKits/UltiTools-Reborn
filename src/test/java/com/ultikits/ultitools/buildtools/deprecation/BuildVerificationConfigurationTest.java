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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.yaml.snakeyaml.Yaml;

/** Static contracts only: publication scripts are never executed by these tests. */
class BuildVerificationConfigurationTest {
    private static final Pattern MAVEN_CALL = Pattern.compile(
            "(?m)(?:^\\h*(?:if\\h+)?|\\$\\()mvn\\h+([^\\r\\n]+)");
    private static final String COMPILER = "/project/build/plugins/plugin[artifactId='maven-compiler-plugin']";
    private static final String EXEC = "/project/build/plugins/plugin[artifactId='exec-maven-plugin']";

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

    @Test
    void githubPackagesBuildReachesVerify() throws IOException {
        assertThat(calls(workflowRuns("publish-packages.yml", "publish-github")))
                .hasSize(3).anySatisfy(call -> assertThat(call).isEqualTo("-B clean verify -DskipTests"));
    }

    @Test
    void centralRetainsSigningAndSkipsOnlyTestExecution() throws IOException {
        assertThat(calls(workflowRuns("publish-packages.yml", "publish-central")))
                .hasSize(2).anySatisfy(call -> assertThat(call).isEqualTo(
                        "-B deploy -P release -DskipTests -Dgpg.passphrase=\"${MAVEN_GPG_PASSPHRASE}\""));
    }

    @Test
    void releaseHelperVerifiesWithTests() throws IOException {
        assertThat(calls(read(Paths.get(".github/scripts/release.sh"))))
                .hasSize(2).anySatisfy(call -> assertThat(call).isEqualTo("-B clean verify"));
    }

    @Test
    void retainedSnapshotAndCiCallsHaveExplicitDispositions() throws IOException {
        List<String> snapshot = calls(workflowRuns("snapshot-publish.yml", "snapshot-publish"));
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot).anySatisfy(call -> assertThat(call).startsWith("-B -q help:evaluate "));
        assertThat(snapshot).contains("-B deploy -DskipTests");
        assertThat(calls(workflowRuns("maven-ci.yml", "test")))
                .containsExactly("-B clean verify", "-B jacoco:report");
        assertThat(calls(workflowRuns("maven-ci.yml", "test-isolated")))
                .containsExactly("-B test -Dgroups=isolated -DexcludedGroups=");
        List<String> diagnostic = calls(workflowRuns("maven-ci.yml", "build-snapshot"));
        assertThat(diagnostic).hasSize(2);
        assertThat(diagnostic).anySatisfy(call -> assertThat(call).startsWith("versions:set "));
        // Diagnostic artifacts are not a second full verification gate.
        assertThat(diagnostic).contains("-B package javadoc:javadoc -DskipTests");
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
