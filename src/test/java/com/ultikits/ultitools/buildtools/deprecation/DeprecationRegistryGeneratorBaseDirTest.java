package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>The first revision of this test class exercised only the read-only slice of the fix -
 * {@link DeprecationRegistryGenerator#readPomDocument(Path)} and {@link
 * DeprecationRegistryGenerator#resolveBaseDir(String[])} - deliberately never invoking the full
 * {@code run(Path)} pipeline, citing the risk of corrupting this repository's own tracked
 * {@code compatibility/deprecations.json}/{@code DEPRECATIONS.md}. Gate-1 review WR-01 correctly
 * flagged that as leaving the write side - the actual root-cause fix, threading {@code baseDir}
 * through {@code loadPriorLedger}, {@code JavadocDeprecationScanner#scan}, {@code
 * readJapicmpReport}, and both {@code Files.write} calls - proven only by a manual, one-off shell
 * transcript that no CI run re-executes. {@link #runWritesLedgerFilesUnderGivenBaseDirOnly} closes
 * that gap: it builds a complete, isolated, minimal project layout under {@code @TempDir}
 * (a synthetic {@code pom.xml}, a tiny {@code src/main/java}, and a small japicmp report - no
 * prior ledger), calls {@link DeprecationRegistryGenerator#run(Path)} directly against that
 * temporary root, and asserts both that the ledger files land there AND that the real
 * repository's own tracked {@code compatibility/} files are byte-for-byte unchanged - the
 * regression this test exists to catch is exactly a future edit reintroducing a bare {@code
 * Paths.get(...)} somewhere in that call chain (e.g. during the #464 merge, see WR-02) instead of
 * {@code baseDir.resolve(...)}.
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

    @Test
    @DisplayName("WR-01: run(Path) writes the ledger files under the given base directory only - "
            + "the real repository's own tracked compatibility/ files are byte-for-byte unchanged afterward")
    void runWritesLedgerFilesUnderGivenBaseDirOnly(@TempDir Path tempProjectRoot) throws Exception {
        Files.write(tempProjectRoot.resolve("pom.xml"), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "    <modelVersion>4.0.0</modelVersion>\n"
                        + "    <groupId>com.example</groupId>\n"
                        + "    <artifactId>wr01-fixture</artifactId>\n"
                        + "    <version>1.0.0-SNAPSHOT</version>\n"
                        + "</project>\n").getBytes(StandardCharsets.UTF_8));

        Path srcPkg = tempProjectRoot.resolve("src/main/java/com/example/wr01");
        Files.createDirectories(srcPkg);
        Files.write(srcPkg.resolve("Simple.java"), (
                "package com.example.wr01;\n"
                        + "public class Simple {\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        Path japicmpDir = tempProjectRoot.resolve("target/japicmp");
        Files.createDirectories(japicmpDir);
        Files.write(japicmpDir.resolve("japicmp.xml"), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<japicmp accessModifier=\"PROTECTED\">\n"
                        + "    <classes>\n"
                        + "        <class binaryCompatible=\"true\" changeStatus=\"UNCHANGED\" "
                        + "fullyQualifiedName=\"com.example.wr01.Simple\" "
                        + "javaObjectSerializationCompatible=\"NOT_SERIALIZABLE\" sourceCompatible=\"true\"/>\n"
                        + "    </classes>\n"
                        + "</japicmp>\n").getBytes(StandardCharsets.UTF_8));

        // Snapshot the REAL repository's own tracked ledger files before running - this test's
        // whole point is proving run(Path) never touches them when given an unrelated baseDir.
        Path realLedgerJson = Paths.get("compatibility", "deprecations.json");
        Path realLedgerMarkdown = Paths.get("compatibility", "DEPRECATIONS.md");
        byte[] realJsonBefore = Files.readAllBytes(realLedgerJson);
        byte[] realMarkdownBefore = Files.readAllBytes(realLedgerMarkdown);

        DeprecationRegistryGenerator.run(tempProjectRoot);

        assertThat(Files.readAllBytes(realLedgerJson))
                .as("run(Path) must never write to the real repository's tracked compatibility/deprecations.json "
                        + "when given an unrelated temporary base directory")
                .isEqualTo(realJsonBefore);
        assertThat(Files.readAllBytes(realLedgerMarkdown))
                .as("run(Path) must never write to the real repository's tracked compatibility/DEPRECATIONS.md "
                        + "when given an unrelated temporary base directory")
                .isEqualTo(realMarkdownBefore);

        Path writtenJson = tempProjectRoot.resolve("compatibility/deprecations.json");
        Path writtenMarkdown = tempProjectRoot.resolve("compatibility/DEPRECATIONS.md");
        assertThat(writtenJson).as("the ledger JSON must land under the given base directory").exists();
        assertThat(writtenMarkdown).as("the ledger markdown must land under the given base directory").exists();
        // Simple.java carries no @Deprecated annotation, so the ledger is legitimately empty - the
        // point here is that these two files exist, are valid, and are NOT the real repository's
        // own 55-entry ledger (which would prove baseDir was silently ignored in favour of cwd).
        String jsonContent = new String(Files.readAllBytes(writtenJson), StandardCharsets.UTF_8).trim();
        assertThat(jsonContent).isEqualTo("[]");
        byte[] generatedJson = Files.readAllBytes(writtenJson);
        byte[] generatedMarkdown = Files.readAllBytes(writtenMarkdown);
        DeprecationRegistryGenerator.run(tempProjectRoot);
        assertThat(Files.readAllBytes(writtenJson)).isEqualTo(generatedJson);
        assertThat(Files.readAllBytes(writtenMarkdown)).isEqualTo(generatedMarkdown);
        assertThat(Files.readAllBytes(realLedgerJson)).isEqualTo(realJsonBefore);
        assertThat(Files.readAllBytes(realLedgerMarkdown)).isEqualTo(realMarkdownBefore);
    }

    @Test
    @DisplayName("Codex P2, PR #480: an empty/missing japicmp report plus a prior ANNOUNCED entry "
            + "gone from source fails as REPORT_MISSING_OR_EMPTY, not as a raw LedgerMergeConflictException")
    void emptyReportWithVanishedAnnouncedEntryFailsAsReportMissingNotMergeConflict(
            @TempDir Path tempProjectRoot) throws IOException {
        byte[] realJsonBefore = Files.readAllBytes(Paths.get("compatibility/deprecations.json"));
        byte[] realMarkdownBefore = Files.readAllBytes(Paths.get("compatibility/DEPRECATIONS.md"));
        buildVanishedAnnouncedEntryFixture(tempProjectRoot);
        // No target/japicmp/japicmp.xml is written at all - readJapicmpReport falls back to
        // JapicmpReportReader.Report.empty(), reproducing the exact infrastructure state the
        // Codex finding names ("the japicmp report is missing or has no entries").

        assertThatThrownBy(() -> DeprecationRegistryGenerator.run(tempProjectRoot))
                .as("the japicmp report being empty must be diagnosed as REPORT_MISSING_OR_EMPTY "
                        + "(RemovalConsistencyEvaluator's unconditional infrastructure finding), "
                        + "not surfaced as a raw LedgerMergeConflictException from RegistryLedger.merge "
                        + "blaming a source/report disagreement that never actually happened")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(LedgerMergeConflictException.class)
                .hasMessageContaining("REPORT_MISSING_OR_EMPTY")
                .hasMessageContaining("the japicmp report is missing or empty");
        assertThat(Files.readAllBytes(Paths.get("compatibility/deprecations.json"))).isEqualTo(realJsonBefore);
        assertThat(Files.readAllBytes(Paths.get("compatibility/DEPRECATIONS.md"))).isEqualTo(realMarkdownBefore);
    }

    @Test
    @DisplayName("Codex P2, PR #480 round 2: a zero-byte japicmp.xml (exists, but nothing to parse) "
            + "is treated the same as a missing one - REPORT_MISSING_OR_EMPTY, not a raw XML parse failure")
    void zeroByteReportIsTreatedAsEmptyNotAsAParseFailure(@TempDir Path tempProjectRoot) throws IOException {
        byte[] realJsonBefore = Files.readAllBytes(Paths.get("compatibility/deprecations.json"));
        byte[] realMarkdownBefore = Files.readAllBytes(Paths.get("compatibility/DEPRECATIONS.md"));
        buildVanishedAnnouncedEntryFixture(tempProjectRoot);
        // Unlike the sibling test above, target/japicmp/japicmp.xml DOES exist here - so
        // Files.exists alone can no longer distinguish this from a genuine report, exactly the
        // gap the Codex finding names (an interrupted or racing report write can leave a
        // zero-byte file on disk).
        Path japicmpDir = tempProjectRoot.resolve("target/japicmp");
        Files.createDirectories(japicmpDir);
        Files.write(japicmpDir.resolve("japicmp.xml"), new byte[0]);

        assertThatThrownBy(() -> DeprecationRegistryGenerator.run(tempProjectRoot))
                .as("a zero-byte japicmp.xml must be diagnosed as REPORT_MISSING_OR_EMPTY, "
                        + "the same as a missing file - not surfaced as a generic XML parse failure "
                        + "from JapicmpReportReader.read")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(LedgerMergeConflictException.class)
                .hasMessageContaining("REPORT_MISSING_OR_EMPTY")
                .hasMessageContaining("the japicmp report is missing or empty");
        assertThat(Files.readAllBytes(Paths.get("compatibility/deprecations.json"))).isEqualTo(realJsonBefore);
        assertThat(Files.readAllBytes(Paths.get("compatibility/DEPRECATIONS.md"))).isEqualTo(realMarkdownBefore);
    }

    /**
     * Shared fixture for both empty-report tests above: a minimal pom, no {@code src/main/java}
     * package (so the prior ledger's one member below is absent from this run's fresh source
     * scan, exactly like a real removal), and a prior ledger carrying one {@code ANNOUNCED}
     * entry for that now-vanished member. Callers decide separately what (if anything) to put at
     * {@code target/japicmp/japicmp.xml}.
     */
    private static void buildVanishedAnnouncedEntryFixture(Path tempProjectRoot) throws IOException {
        Files.write(tempProjectRoot.resolve("pom.xml"), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "    <modelVersion>4.0.0</modelVersion>\n"
                        + "    <groupId>com.example</groupId>\n"
                        + "    <artifactId>codex-p2-fixture</artifactId>\n"
                        + "    <version>1.0.0-SNAPSHOT</version>\n"
                        + "</project>\n").getBytes(StandardCharsets.UTF_8));

        Files.createDirectories(tempProjectRoot.resolve("src/main/java"));

        Path compatDir = tempProjectRoot.resolve("compatibility");
        Files.createDirectories(compatDir);
        Files.write(compatDir.resolve("deprecations.json"), (
                "[\n"
                        + "  {\n"
                        + "    \"key\": \"com.example.codexp2.Vanished#gone()\",\n"
                        + "    \"className\": \"com.example.codexp2.Vanished\",\n"
                        + "    \"memberName\": \"gone\",\n"
                        + "    \"kind\": \"METHOD\",\n"
                        + "    \"since\": \"1.0.0\",\n"
                        + "    \"forRemoval\": true,\n"
                        + "    \"removeIn\": \"2.0.0\",\n"
                        + "    \"replacement\": null,\n"
                        + "    \"status\": \"ANNOUNCED\",\n"
                        + "    \"removedIn\": null\n"
                        + "  }\n"
                        + "]\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void lifecycleHarnessControlsRejectWrongArtifactsAndCwd(@TempDir Path output) throws Exception {
        Path log = output.resolve("harness.log");
        Process process = new ProcessBuilder("bash", ".github/scripts/test-buildtool-lifecycle.sh", "--self-test")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("bounded harness controls").isTrue();
        assertThat(process.exitValue()).as(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
                .isZero();
        assertThat(new String(Files.readAllBytes(log), StandardCharsets.UTF_8))
                .contains("SELF-TEST PASSED", "positive controls", "negative controls");
    }

    @Test
    void nonemptyRealLedgerPreservesEveryPriorPropertyAndAddsDeclarations(@TempDir Path project) throws Exception {
        Path realJson = Paths.get("compatibility/deprecations.json");
        Path realMarkdown = Paths.get("compatibility/DEPRECATIONS.md");
        byte[] priorBytes = Files.readAllBytes(realJson);
        byte[] markdownBefore = Files.readAllBytes(realMarkdown);
        com.google.gson.JsonArray prior = com.google.gson.JsonParser.parseString(
                new String(priorBytes, StandardCharsets.UTF_8)).getAsJsonArray();
        assertThat(prior).as("a nonempty real ledger, not an empty replacement baseline").isNotEmpty();
        Files.copy(Paths.get("pom.xml"), project.resolve("pom.xml"));
        Path sourceRoot = Paths.get("src/main/java");
        try (java.util.stream.Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                Path copy = project.resolve("src/main/java").resolve(sourceRoot.relativize(source));
                Files.createDirectories(copy.getParent());
                Files.copy(source, copy);
            }
        }
        Path matrix = project.resolve("src/main/java/com/ultikits/testfixtures/deprecationscan/DeclarationMatrixSubject.java");
        Files.createDirectories(matrix.getParent());
        Files.copy(Paths.get("src/test/java/com/ultikits/testfixtures/deprecationscan/DeclarationMatrixSubject.java"), matrix);
        Path compatibility = Files.createDirectories(project.resolve("compatibility"));
        Files.write(compatibility.resolve("deprecations.json"), priorBytes);
        // This fixture is about source discovery/history, not a new binary comparison. Keep a
        // nonempty protected-scope report with unchanged class controls for every existing exclude;
        // no synthetic removal, deadline or exclusion adjustment is used to obtain a passing merge.
        java.util.Set<String> classes = new java.util.TreeSet<>();
        java.util.Set<String> excludedClasses = new java.util.HashSet<>();
        for (RegistryKey key : DeprecationRegistryGenerator.readPomExcludeKeys(
                DeprecationRegistryGenerator.readPomDocument(project))) {
            if (key.isClassLevel()) {
                excludedClasses.add(key.getClassName());
            } else {
                classes.add(key.getClassName());
            }
        }
        // Do not invent old-side visibility for excluded classes: this fixture makes no
        // admissibility claim about them. Existing dedicated invariant tests cover that gate.
        classes.removeAll(excludedClasses);
        classes.add("com.example.UnchangedControl");
        StringBuilder report = new StringBuilder("<japicmp accessModifier=\"PROTECTED\"><classes>");
        for (String name : classes) {
            report.append("<class binaryCompatible=\"true\" changeStatus=\"UNCHANGED\" fullyQualifiedName=\"")
                    .append(name).append("\" sourceCompatible=\"true\"/>");
        }
        report.append("</classes></japicmp>");
        Path reportDir = Files.createDirectories(project.resolve("target/japicmp"));
        Files.write(reportDir.resolve("japicmp.xml"), report.toString().getBytes(StandardCharsets.UTF_8));

        DeprecationRegistryGenerator.run(project);

        Path outputJson = compatibility.resolve("deprecations.json");
        com.google.gson.JsonArray output = com.google.gson.JsonParser.parseString(
                new String(Files.readAllBytes(outputJson), StandardCharsets.UTF_8)).getAsJsonArray();
        java.util.Map<String, com.google.gson.JsonObject> byKey = new java.util.LinkedHashMap<>();
        output.forEach(row -> byKey.put(row.getAsJsonObject().get("key").getAsString(), row.getAsJsonObject()));
        for (com.google.gson.JsonElement row : prior) {
            String key = row.getAsJsonObject().get("key").getAsString();
            assertThat(byKey.get(key)).as("all prior properties, absent/null schema and history for %s", key)
                    .isEqualTo(row.getAsJsonObject());
        }
        java.util.Set<String> priorKeys = new java.util.HashSet<>();
        prior.forEach(row -> priorKeys.add(row.getAsJsonObject().get("key").getAsString()));
        java.util.Set<String> additions = new java.util.TreeSet<>(byKey.keySet());
        additions.removeAll(priorKeys);
        java.util.Set<String> declaredFixtureKeys = new java.util.TreeSet<>();
        new JavadocDeprecationScanner(getClass().getClassLoader()).scan(matrix.getParent())
                .forEach(entry -> declaredFixtureKeys.add(entry.getKey().toString()));
        assertThat(additions).as("only documented compiled fixture declarations may be added")
                .isEqualTo(declaredFixtureKeys).contains(
                        "com.ultikits.testfixtures.deprecationscan.DeclarationMatrixSubject#annotationOnly()",
                        "com.ultikits.testfixtures.deprecationscan.DeclarationMatrixSubject#initializedField");
        byte[] onceJson = Files.readAllBytes(outputJson);
        byte[] onceMarkdown = Files.readAllBytes(compatibility.resolve("DEPRECATIONS.md"));
        DeprecationRegistryGenerator.run(project);
        assertThat(Files.readAllBytes(outputJson)).isEqualTo(onceJson);
        assertThat(Files.readAllBytes(compatibility.resolve("DEPRECATIONS.md"))).isEqualTo(onceMarkdown);
        assertThat(Files.readAllBytes(realJson)).isEqualTo(priorBytes);
        assertThat(Files.readAllBytes(realMarkdown)).isEqualTo(markdownBefore);
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
