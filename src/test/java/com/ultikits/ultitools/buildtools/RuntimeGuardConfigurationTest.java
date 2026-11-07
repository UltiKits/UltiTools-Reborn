package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** The floor fixtures and the post-shade gate must run in the ordinary verify lifecycle. */
class RuntimeGuardConfigurationTest {
    @Test
    void verifyChecksThePackagedJarAndCopiesIndependentFloorFixtures() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document pom = factory.newDocumentBuilder().parse(
                Paths.get(System.getProperty("basedir", "."), "pom.xml").toFile());
        javax.xml.xpath.XPath xpath = XPathFactory.newInstance().newXPath();
        String fixtures = "/project/build/plugins/plugin[artifactId='maven-dependency-plugin']"
                + "/executions/execution[id='runtime-api-floor-fixtures']";
        assertThat(xpath.evaluate(fixtures + "/phase", pom)).isEqualTo("generate-test-resources");
        assertThat(xpath.evaluate(fixtures + "/configuration/artifactItems/artifactItem"
                + "[artifactId='gson']/version", pom)).isEqualTo("2.8.9");
        assertThat(xpath.evaluate(fixtures + "/configuration/artifactItems/artifactItem"
                + "[artifactId='paper-api']/version", pom)).isEqualTo("1.19.2-R0.1-20221206.184705-189");
        String failsafe = "/project/build/plugins/plugin[artifactId='maven-failsafe-plugin']";
        assertThat(xpath.evaluate(failsafe + "/executions/execution/goals/goal[.='integration-test']", pom))
                .isEqualTo("integration-test");
        assertThat(xpath.evaluate(failsafe + "/executions/execution/goals/goal[.='verify']", pom))
                .isEqualTo("verify");
        assertThat(xpath.evaluate(failsafe + "/configuration/systemPropertyVariables/runtimeGuardArtifact", pom))
                .isEqualTo("${project.build.directory}/${project.build.finalName}.jar");
        // Keep the production compiler dependency unchanged; these are isolated fixtures only.
        assertThat(xpath.evaluate("/project/dependencies/dependency[artifactId='gson']/version", pom))
                .isEqualTo("2.10.1");
    }
}
