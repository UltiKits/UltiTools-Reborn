package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reset is source-derived, deterministic and bounded; normal comparison stays fail-closed. */
class BaselineResetRegistryTest {
    @Test
    void resetIgnoresPriorHistoryArchivesAndStaleReportsButKeepsCurrentDeclarations(@TempDir Path root)
            throws Exception {
        pom(root, "7.0.0-SNAPSHOT", "6.5.0", true);
        Path source = root.resolve("src/main/java/com/ultikits/testfixtures/deprecationscan/DeclarationMatrixSubject.java");
        Files.createDirectories(source.getParent());
        Files.copy(Paths.get("src/test/java/com/ultikits/testfixtures/deprecationscan/DeclarationMatrixSubject.java"), source);
        RegistryLedger expected = RegistryLedger.of(new JavadocDeprecationScanner(getClass().getClassLoader())
                .scan(root.resolve("src/main/java")));
        assertThat(expected.entries()).isNotEmpty();
        Path ledger = root.resolve("compatibility/deprecations.json");
        Files.createDirectories(ledger.getParent());
        Files.write(ledger, "not a valid prior ledger".getBytes(StandardCharsets.UTF_8));
        Path archive = root.resolve("compatibility/archive/6.x/deprecations.json");
        Files.createDirectories(archive.getParent());
        Files.write(archive, "read-only history sentinel".getBytes(StandardCharsets.UTF_8));
        Path staleReport = root.resolve("target/japicmp/japicmp.xml");
        Files.createDirectories(staleReport.getParent());
        Files.write(staleReport, "not a current comparison report".getBytes(StandardCharsets.UTF_8));

        DeprecationRegistryGenerator.run(root);

        assertThat(new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8)).isEqualTo(expected.toJson());
        assertThat(expected.entries()).noneMatch(entry -> entry.getStatus() == DeprecationEntry.Status.REMOVED);
        byte[] json = Files.readAllBytes(ledger);
        Path markdown = root.resolve("compatibility/DEPRECATIONS.md");
        byte[] text = Files.readAllBytes(markdown);
        DeprecationRegistryGenerator.run(root);
        assertThat(Files.readAllBytes(ledger)).isEqualTo(json);
        assertThat(Files.readAllBytes(markdown)).isEqualTo(text);
        assertThat(new String(Files.readAllBytes(archive), StandardCharsets.UTF_8))
                .isEqualTo("read-only history sentinel");
        assertThat(new String(Files.readAllBytes(staleReport), StandardCharsets.UTF_8))
                .isEqualTo("not a current comparison report");
        // Effective Maven -Djapicmp.skip=false must override the POM default and require a report.
        Files.delete(staleReport);
        assertThatThrownBy(() -> DeprecationRegistryGenerator.run(root, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REPORT_MISSING_OR_EMPTY");
    }

    @Test
    void generatorAlsoRejectsForgettingToReopenWhenTestsAreSkipped(@TempDir Path root) throws Exception {
        for (String version : new String[] {"7.0.1-SNAPSHOT", "7.1.0-SNAPSHOT", "7.0.1"}) {
            pom(root, version, "6.5.0", true);
            assertThatThrownBy(() -> DeprecationRegistryGenerator.run(root))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("japicmp.skip=false").hasMessageContaining("at least 7.0.0");
            assertThat(root.resolve("compatibility/deprecations.json")).doesNotExist();
        }
        pom(root, "7.0.1-SNAPSHOT", "7.0.0", false);
        assertThatThrownBy(() -> DeprecationRegistryGenerator.run(root))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REPORT_MISSING_OR_EMPTY");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "generator version {0}: valid={1}, reset={2}")
    @org.junit.jupiter.params.provider.CsvFileSource(resources = "/buildtools/project-version-cases.csv", numLinesToSkip = 1)
    void generatorValidatesVersionBeforeWritingRegistry(
            String version, boolean valid, boolean reset, @TempDir Path root) throws Exception {
        pom(root, version, "7.0.0", true);
        if (valid && reset) {
            DeprecationRegistryGenerator.run(root);
            assertThat(root.resolve("compatibility/deprecations.json")).isRegularFile();
        } else {
            assertThatThrownBy(() -> DeprecationRegistryGenerator.run(root))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(valid ? "japicmp.skip=false" : "must match N.N.N or N.N.N-SNAPSHOT");
            assertThat(root.resolve("compatibility/deprecations.json")).doesNotExist();
            assertThat(root.resolve("compatibility/DEPRECATIONS.md")).doesNotExist();
        }
    }

    private static void pom(Path root, String version, String baseline, boolean skip) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        String xml = "<project><version>" + version + "</version><properties>"
                + "<japicmp.baseline.version>" + baseline + "</japicmp.baseline.version>"
                + "<japicmp.skip>" + skip + "</japicmp.skip></properties></project>";
        Files.write(root.resolve("pom.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }
}
