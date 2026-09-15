package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Pins #461's root cause and fix: {@code exec-maven-plugin}'s {@code java} goal runs {@link
 * DeprecationRegistryGenerator} in-process, in the Maven JVM's own working directory - NOT {@code
 * ${project.basedir}}. Invoked as {@code mvn -f <worktree>/pom.xml} from a different shell cwd (every
 * agent's Bash cwd resets to the primary checkout between calls), the generator silently
 * read/wrote the WRONG tree, producing 15 false {@code STALE_EXCLUSION} findings that looked like a
 * real compatibility problem (the empty-report guard in {@link RemovalConsistencyEvaluator} is the
 * sibling half of this fix - see {@code RemovalConsistencyEvaluatorTest}).
 *
 * <p>These tests exercise only the safe, read-only slice of the fix - {@link
 * DeprecationRegistryGenerator#readPomDocument(Path)} and {@link
 * DeprecationRegistryGenerator#resolveBaseDir(String[])} - against a temporary, throwaway project
 * tree, deliberately never invoking the full {@code run(Path)} pipeline (javadoc scan, japicmp
 * report read, ledger merge and write) in a unit test: that pipeline writes {@code
 * compatibility/deprecations.json}/{@code DEPRECATIONS.md}, and running it against anything other
 * than an isolated temporary tree risks corrupting this repository's own tracked registry. The full
 * write-side pipeline is proven end-to-end by the plan's own integration verify (<code>cd "$W" &amp;&amp;
 * mvn -B -o exec:java@generate-deprecation-registry</code> from inside the worktree, and the
 * from-primary-checkout-cwd proof required by Task 3), not by a unit test here.
 */
@DisplayName("DeprecationRegistryGenerator basedir resolution tests (#461)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeprecationRegistryGeneratorBaseDirTest {

    @Test
    @DisplayName("resolveBaseDir returns the given argument verbatim when present and non-blank")
    void resolveBaseDirReturnsGivenArgument() {
        Path result = DeprecationRegistryGenerator.resolveBaseDir(new String[] {"/some/other/tree"});

        assertThat(result).isEqualTo(Paths.get("/some/other/tree"));
    }

    @Test
    @DisplayName("resolveBaseDir falls back to the JVM's actual working directory when no argument is given")
    void resolveBaseDirFallsBackToWorkingDirectoryWhenArgsEmpty() {
        Path result = DeprecationRegistryGenerator.resolveBaseDir(new String[0]);

        assertThat(result).isEqualTo(Paths.get("").toAbsolutePath());
    }

    @Test
    @DisplayName("resolveBaseDir falls back to the working directory for null args, a null first element, or a blank first element")
    void resolveBaseDirFallsBackForDegenerateInputs() {
        Path expected = Paths.get("").toAbsolutePath();

        assertThat(DeprecationRegistryGenerator.resolveBaseDir(null)).isEqualTo(expected);
        assertThat(DeprecationRegistryGenerator.resolveBaseDir(new String[] {null})).isEqualTo(expected);
        assertThat(DeprecationRegistryGenerator.resolveBaseDir(new String[] {"   "})).isEqualTo(expected);
    }

    @Test
    @DisplayName("#461: readPomDocument(Path) reads the pom.xml under the GIVEN base directory, "
            + "not the JVM's actual working directory - a temporary tree with a distinct version proves it")
    void readPomDocumentReadsFromGivenBaseDirNotActualCwd(@TempDir Path tempProjectRoot) throws IOException {
        String sentinelVersion = "0.0.0-BASEDIR-SENTINEL";
        Files.write(tempProjectRoot.resolve("pom.xml"), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "    <modelVersion>4.0.0</modelVersion>\n"
                        + "    <groupId>com.example</groupId>\n"
                        + "    <artifactId>sentinel</artifactId>\n"
                        + "    <version>" + sentinelVersion + "</version>\n"
                        + "</project>\n").getBytes(StandardCharsets.UTF_8));

        // The JVM's actual working directory (Surefire forks with basedir as cwd, so this reads
        // the REAL project's pom.xml, whose version is emphatically not the sentinel above) is
        // deliberately never referenced here - only tempProjectRoot is passed in, proving the read
        // is not falling back to it by accident.
        Document pomDocument = DeprecationRegistryGenerator.readPomDocument(tempProjectRoot);

        assertThat(projectVersion(pomDocument))
                .as("readPomDocument(Path) must read the pom.xml under the given base directory, "
                        + "not whatever pom.xml the JVM's actual working directory happens to hold")
                .isEqualTo(sentinelVersion);
    }

    private static String projectVersion(Document doc) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && "version".equals(node.getNodeName())) {
                return node.getTextContent().trim();
            }
        }
        return null;
    }
}
