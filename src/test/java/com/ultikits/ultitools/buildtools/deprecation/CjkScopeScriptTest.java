package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CjkScopeScriptTest {
    private static final String SCRIPT = ".github/scripts/check-cjk-scope.sh";
    private static final String SAMPLE = "src/main/java/Sample.java";
    private static final Path FIXTURE = Paths.get(".github/scripts/testdata/cjk-java-comments.java.txt");

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

    private Result command(String... argv) throws Exception {
        Path output = Files.createTempFile(temp, "process-", ".log");
        Process process = new ProcessBuilder(argv).directory(temp.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
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
