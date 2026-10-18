package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CjkScopeScriptTest {
    private static final String SCRIPT = ".github/scripts/check-cjk-scope.sh";
    private static final String SAMPLE = "src/main/java/Sample.java";
    private static final Path FIXTURE = Paths.get(".github/scripts/testdata/cjk-java-comments.java.txt");
    private static final String FAULT_BIN = "fault-bin";
    private static final String GATE_TMP = "gate-tmp";
    private static final String SAMPLE_VIOLATION = SAMPLE + ":1:// 中文";
    private static final String ONE_VIOLATION_SUMMARY = "SUMMARY: 1 CJK violation(s) across 1 file(s).";
    private static final String ENUMERATION_ERROR =
            "ERROR: git ls-files failed for scope 'src/main/java' (exit 128); the gate fails closed.";
    private static final String SORT_ERROR = "ERROR: sorting the violation list failed (exit 2); the gate fails closed.";

    @TempDir
    Path temp;

    @Test
    void inlineBlockCommentIsRejected() throws Exception {
        String line = fixtureLine("int inline");
        prepare("class Sample {\n" + line + "\n}\n");
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(result.output).contains(SAMPLE + ":2:" + line);
    }

    @Test
    void knownLineCommentIsPositiveControl() throws Exception {
        String line = fixtureLine("int line");
        prepare("class Sample {\n" + line + "\n}\n");
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(result.output).contains(SAMPLE + ":2:" + line);
    }

    @Test
    void literalBesideEnglishCommentIsAccepted() throws Exception {
        prepare("class Sample {\n" + fixtureLine("String literal") + "\n}\n");
        Result result = gate();
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("SUMMARY: 0 CJK violation(s)");
    }

    @Test
    void asciiOpenedUnstarredContinuationIsRejected() throws Exception {
        String source = "class Sample {\n    /* ASCII opener\n    unstarred 中文续行\n    */\n}\n";
        prepare(source);
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(result.output).contains(SAMPLE + ":3:    unstarred 中文续行");
    }

    @Test
    void fullFileMatrixHasExactViolationLines() throws Exception {
        assertMatrix(new String(Files.readAllBytes(FIXTURE), StandardCharsets.UTF_8));
    }

    @Test
    void crlfMatrixRetainsOriginalBytesAndDiagnostics() throws Exception {
        assertMatrix(new String(Files.readAllBytes(FIXTURE), StandardCharsets.UTF_8).replace("\n", "\r\n"));
    }

    @Test
    void missingFinalNewlineStillReportsFinalComment() throws Exception {
        String source = new String(Files.readAllBytes(FIXTURE), StandardCharsets.UTF_8);
        assertMatrix(source.substring(0, source.length() - 1));
    }

    private void assertMatrix(String source) throws Exception {
        prepare(source);
        byte[] before = Files.readAllBytes(temp.resolve(SAMPLE));
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        List<Integer> expected = Arrays.asList(3, 6, 9, 12, 14, 15, 24, 26);
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String prefix = SAMPLE + ":" + (i + 1) + ":";
            if (expected.contains(i + 1)) {
                assertThat(result.output).contains(prefix + lines[i] + "\n");
            } else {
                assertThat(result.output).doesNotContain(prefix);
            }
        }
        assertThat(result.output).contains("SUMMARY: 8 CJK violation(s) across 1 file(s).");
        assertThat(Files.readAllBytes(temp.resolve(SAMPLE))).isEqualTo(before);
    }

    @Test
    void expandedFixtureSelfTestPasses() throws Exception {
        prepare("class Sample {}\n");
        Result result = gate("--self-test");
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).doesNotContain("FAIL:");
    }

    @Test
    void unicodeContractIsPinnedInBothDirections() throws Exception {
        prepare("// ひらがなカタカナ・㈱\n");
        assertThat(gate().exit).isZero();
        for (String planted : Arrays.asList("一", "㐀", "𠀀", "豈", "、", "：", "ｶ", "ﾠ")) {
            write(SAMPLE, "// " + planted + "\n");
            Result result = gate();
            assertThat(result.exit).as(planted + ": " + result.output).isEqualTo(1);
            assertThat(result.output).contains(SAMPLE + ":1:// " + planted);
        }
    }

    @Test
    void allowlistMatchesOriginalLineButNotWholeFile() throws Exception {
        prepare("int i = 0; /* 中文 */ // allowed-marker\n// 中文不豁免\n");
        write(".github/cjk-allowlist.txt", SAMPLE + ":allowed-marker$\n");
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(result.output).doesNotContain(SAMPLE + ":1:")
                .contains(SAMPLE + ":2:// 中文不豁免");
    }

    @Test
    void invalidAllowlistRegexFailsClosedEvenInReportOnlyMode() throws Exception {
        prepare("// 中文\n");
        write(".github/cjk-allowlist.txt", SAMPLE + ":[\n");
        Result result = gate("--report-only");
        assertThat(result.exit).as(result.output).isEqualTo(2);
        assertThat(result.output).contains("ERROR:", "allowlist");
    }

    @Test
    void invalidContractRegexFailsBeforeScanning() throws Exception {
        prepare("class Sample {}\n");
        Path script = temp.resolve(SCRIPT);
        String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        write(SCRIPT, source.replace("CJK_RANGE='[\\p{sc:Han}\\x{3000}-\\x{303f}\\x{ff00}-\\x{ffef}]'", "CJK_RANGE='['"));
        Result result = gate("--report-only");
        assertThat(result.exit).as(result.output).isEqualTo(2);
        assertThat(result.output).contains("ERROR:", "contract pattern");
    }

    @Test
    void unreadableTrackedJavaDoesNotBecomeEmptySuccess() throws Exception {
        prepare("class Sample {}\n");
        Files.delete(temp.resolve(SAMPLE));
        Result result = gate("--report-only");
        assertThat(result.exit).as(result.output).isEqualTo(2);
        assertThat(result.output).contains("ERROR:", "Java comment helper failed", SAMPLE);
    }

    @Test
    void helperFailureDoesNotBecomeEmptySuccess() throws Exception {
        prepare("// 中文\n");
        Path script = temp.resolve(SCRIPT);
        String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        assertThat(source).contains("use strict;");
        write(SCRIPT, source.replace("use strict;", "die qq(injected helper failure\\n);\nuse strict;"));
        Result result = gate("--report-only");
        assertThat(result.exit).as(result.output).isEqualTo(2);
        assertThat(result.output).contains("injected helper failure", "Java comment helper failed");
    }

    @Test
    void yamlStillChecksWholeLinesIncludingQuotedText() throws Exception {
        prepare("class Sample {}\n");
        String workflow = ".github/workflows/fixture.yml";
        write(workflow, "name: '中文'\n");
        assertThat(command("git", "add", "--", workflow).exit).isZero();
        Result result = gate();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(result.output).contains(workflow + ":1:name: '中文'");
    }

    @Test
    void structuralExclusionsAndUntrackedFilesRemainExcluded() throws Exception {
        prepare("class Sample {}\n");
        write("src/main/resources/lang/en.json", "{\"key\":\"中文\"}\n");
        write("src/main/resources/lang/zh.json", "{\"key\":\"中文\"}\n");
        assertThat(command("git", "add", "--", "src/main/resources/lang").exit).isZero();
        write("src/main/java/Untracked.java", "// 中文\n");
        Result result = gate("--scope", "src/main", "--scope", ".github/scripts/testdata");
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("SUMMARY: 0 CJK violation(s)");
    }

    @Test
    void zeroScopeIsExplicitAndThresholdConventionsRemain() throws Exception {
        prepare("// 中文\n");
        Result empty = gate("--scope", "missing-scope");
        assertThat(empty.exit).as(empty.output).isZero();
        assertThat(empty.output).contains("matched zero tracked files", "SUMMARY: 0");
        assertThat(gate().exit).isEqualTo(1);
        assertThat(gate("--max-violations", "1").exit).isZero();
        assertThat(gate("--report-only").exit).isZero();
        assertThat(gate("--unknown").exit).isEqualTo(64);
    }

    @Test
    void enumerationFailureDoesNotBecomeEmptySuccess() throws Exception {
        prepare("// 中文\n");
        Result control = isolatedGate(false);
        assertThat(control.exit).as("control: " + control.output).isEqualTo(1);
        assertThat(control.output).contains(SAMPLE_VIOLATION, ONE_VIOLATION_SUMMARY);
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();

        Path marker = installShim("git", "ls-files", 128, false);
        Result fault = isolatedGate(true);
        assertThat(marker).exists();
        assertThat(fault.exit).as("fault: " + fault.output).isEqualTo(2);
        assertThat(fault.output).contains(ENUMERATION_ERROR)
                .doesNotContain("SUMMARY:")
                .doesNotContain("matched zero tracked files");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();

        Files.delete(marker);
        Result reportOnly = isolatedGate(true, "--report-only");
        assertThat(marker).exists();
        assertThat(reportOnly.exit).as("fault --report-only: " + reportOnly.output).isEqualTo(2);
        assertThat(reportOnly.output).contains(ENUMERATION_ERROR).doesNotContain("SUMMARY:");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();
    }

    @Test
    void violationSortFailureDoesNotBecomeEmptySuccess() throws Exception {
        prepare("// 中文\n");
        Result control = isolatedGate(false);
        assertThat(control.exit).as("control: " + control.output).isEqualTo(1);
        assertThat(control.output).contains(SAMPLE_VIOLATION, ONE_VIOLATION_SUMMARY);
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();

        Path marker = installShim("sort", "-k2,2n", 2, true);
        Path recordedInput = temp.resolve(FAULT_BIN).resolve("sort-input.txt");
        Result fault = isolatedGate(true);
        assertThat(marker).exists();
        assertThat(new String(Files.readAllBytes(recordedInput), StandardCharsets.UTF_8)).contains(SAMPLE_VIOLATION);
        assertThat(fault.exit).as("fault: " + fault.output).isEqualTo(2);
        assertThat(fault.output).contains(SORT_ERROR).doesNotContain("SUMMARY:");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();

        Files.delete(marker);
        Result reportOnly = isolatedGate(true, "--report-only");
        assertThat(marker).exists();
        assertThat(reportOnly.exit).as("fault --report-only: " + reportOnly.output).isEqualTo(2);
        assertThat(reportOnly.output).contains(SORT_ERROR).doesNotContain("SUMMARY:");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();
    }

    @Test
    void selfTestEmptyScopeAssertionRejectsEnumerationFailure() throws Exception {
        prepare("class Sample {}\n");
        Result control = isolatedGate(false, "--self-test");
        assertThat(control.exit).as("control: " + control.output).isZero();
        assertThat(control.output).contains("PASS: assertion 3").doesNotContain("FAIL:");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();

        Path marker = installShim("git", "ls-files", 128, false);
        Result fault = isolatedGate(true, "--self-test");
        assertThat(marker).exists();
        assertThat(fault.exit).as("fault: " + fault.output).isNotZero();
        String failLine = Arrays.stream(fault.output.split("\n"))
                .filter(line -> line.startsWith("FAIL: assertion 3")).findFirst().orElse("");
        assertThat(failLine).as("fault: " + fault.output).contains("run_scan exit 2");
        assertThat(fault.output).contains("PASS: assertion 1");
        assertThat(temp.resolve(GATE_TMP)).isEmptyDirectory();
    }

    private String fixtureLine(String marker) throws IOException {
        return Files.readAllLines(FIXTURE, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing fixture marker: " + marker));
    }

    private void prepare(String java) throws Exception {
        Result init = command("git", "init", "-q");
        assertThat(init.exit).as(init.output).isZero();
        copy(SCRIPT);
        copy(".github/cjk-allowlist.txt");
        try (java.util.stream.Stream<Path> paths = Files.list(FIXTURE.getParent())) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)) {
                    copy(path.toString());
                }
            }
        }
        write(SAMPLE, java);
        Result add = command("git", "add", "--", SAMPLE, ".github");
        assertThat(add.exit).as(add.output).isZero();
        Result tracked = command("git", "ls-files", "--", SAMPLE);
        assertThat(tracked.exit).as(tracked.output).isZero();
        assertThat(tracked.output.trim()).isEqualTo(SAMPLE);
    }

    private void copy(String path) throws IOException {
        Path destination = temp.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.copy(Paths.get(path), destination);
    }

    private void write(String path, String content) throws IOException {
        Path destination = temp.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.write(destination, content.getBytes(StandardCharsets.UTF_8));
    }

    private Result gate(String... args) throws Exception {
        List<String> argv = new ArrayList<>(Arrays.asList("bash", SCRIPT));
        argv.addAll(Arrays.asList(args));
        return command(argv.toArray(new String[0]));
    }

    /**
     * Runs the gate with its TMPDIR pointed at a test-owned directory; when shimmed, the fault-bin
     * directory is prepended to the child PATH only, so the injected fault never leaves the gate's
     * own process tree.
     */
    private Result isolatedGate(boolean shimmed, String... args) throws Exception {
        Path gateTmp = Files.createDirectories(temp.resolve(GATE_TMP));
        Map<String, String> environment = new HashMap<>();
        environment.put("TMPDIR", gateTmp.toString());
        if (shimmed) {
            environment.put("PATH", temp.resolve(FAULT_BIN) + File.pathSeparator + System.getenv("PATH"));
        }
        List<String> argv = new ArrayList<>(Arrays.asList("bash", SCRIPT));
        argv.addAll(Arrays.asList(args));
        return command(environment, argv.toArray(new String[0]));
    }

    /**
     * Writes an executable shim named after TOOL into fault-bin. A call carrying SELECTOR as one of
     * its arguments creates the marker (proof the faulted call was reached), optionally records its
     * last operand, prints nothing and exits with STATUS; every other call runs the real tool,
     * resolved to an absolute path beforehand, with unchanged arguments.
     */
    private Path installShim(String tool, String selector, int status, boolean recordInput) throws Exception {
        Result resolved = command("bash", "-c", "command -v " + tool);
        String real = resolved.output.trim();
        assertThat(resolved.exit).as(resolved.output).isZero();
        assertThat(real).startsWith("/");
        Path bin = Files.createDirectories(temp.resolve(FAULT_BIN));
        Path marker = bin.resolve(tool + ".reached");
        java.util.function.UnaryOperator<String> quote = text -> "'" + text.replace("'", "'\\''") + "'";
        StringBuilder shim = new StringBuilder("#!/usr/bin/env bash\n")
                .append("for argument in \"$@\"; do\n")
                .append("    if [ \"$argument\" = ").append(quote.apply(selector)).append(" ]; then\n")
                .append("        : > ").append(quote.apply(marker.toString())).append('\n');
        if (recordInput) {
            shim.append("        last=\"${!#}\"\n")
                    .append("        if [ -f \"$last\" ] && [ -r \"$last\" ]; then cp -- \"$last\" ")
                    .append(quote.apply(bin.resolve(tool + "-input.txt").toString())).append("; fi\n");
        }
        shim.append("        exit ").append(status).append('\n')
                .append("    fi\n")
                .append("done\n")
                .append("exec ").append(quote.apply(real)).append(" \"$@\"\n");
        Path script = bin.resolve(tool);
        Files.write(script, shim.toString().getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return marker;
    }

    private Result command(String... argv) throws Exception {
        return command(Collections.<String, String>emptyMap(), argv);
    }

    private Result command(Map<String, String> environment, String... argv) throws Exception {
        Path output = Files.createTempFile(temp, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(argv).directory(temp.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + Arrays.toString(argv));
        }
        return new Result(process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }

    private static final class Result {
        final int exit;
        final String output;

        Result(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }
}
