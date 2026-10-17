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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public CLI checks use real Git objects only in disposable repositories. */
class CommentOnlyDiffScriptTest {
    @TempDir Path temp;
    private static final Path SCRIPT = Paths.get(".github/scripts/check-comment-only-diff.sh").toAbsolutePath();

    @Test void validEmptyAndCommentComparisonArePositiveControls() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        assertThat(f.check(f.base, f.base).output).contains("Empty range:");
        assertThat(f.check(f.base, f.base).exit).isZero();
        assertThat(f.check(f.base, f.head).exit).isZero();
    }

    @Test void invalidBaseIsNotEmptySuccess() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        assertThat(f.check(f.base, f.head).exit).isZero();
        assertInability(f.check("refs/heads/missing-base", f.head), "base");
    }

    @Test void invalidHeadIsNotEmptySuccess() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        assertThat(f.check(f.base, f.head).exit).isZero();
        assertInability(f.check(f.base, "refs/heads/missing-head"), "head");
    }

    @Test void mainDiffReadFailureIsNotEmptySuccess() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        assertThat(f.check(f.base, f.head).exit).isZero();
        f.inject("git", "diff", "--name-only", 1);
        Result result = f.check(f.base, f.head);
        f.assertHit();
        assertInability(result, "diff");
    }

    private static void assertInability(Result result, String context) {
        assertThat(result.exit).as(result.output).isEqualTo(2);
        assertThat(result.output).containsIgnoringCase(context).contains("ERROR:")
                .doesNotContain("Empty range:", "SUMMARY:");
    }

    private Fixture fixture(String before, String after) throws Exception {
        return new Fixture(Files.createTempDirectory(temp, "repo-"), before, after);
    }

    private static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }

    private static final class Fixture {
        final Path root;
        final String base;
        final String head;
        final Path bin;
        Fixture(Path root, String before, String after) throws Exception {
            this.root = root;
            bin = root.resolve("fault-bin");
            Files.createDirectories(root.resolve(".github/scripts"));
            Files.copy(SCRIPT, root.resolve(".github/scripts/check-comment-only-diff.sh"));
            git("init", "-q");
            git("config", "user.email", "fixture@example.invalid");
            git("config", "user.name", "Fixture");
            git("config", "commit.gpgsign", "false");
            git("config", "core.autocrlf", "false");
            Files.createDirectories(root.resolve("src/main/java"));
            write("Changed.java", before);
            write("Unchanged.java", "class Unchanged { String s = i18n(\"stable\"); }\n");
            base = commit();
            write("Changed.java", after);
            head = commit();
            assertThat(git("rev-parse", "--verify", base + "^{commit}")).isEqualTo(base);
            assertThat(git("rev-parse", "--verify", head + "^{commit}")).isEqualTo(head);
            assertThat(git("diff", "--name-only", base, head)).contains("Changed.java");
        }
        void write(String name, String text) throws IOException {
            Files.write(root.resolve("src/main/java").resolve(name), text.getBytes(StandardCharsets.UTF_8));
        }
        String commit() throws Exception {
            git("add", "src");
            git("commit", "-q", "-m", "fixture");
            return git("rev-parse", "HEAD");
        }
        String git(String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git"); command.addAll(Arrays.asList(args));
            Result result = run(command, false);
            assertThat(result.exit).as(command + "\n" + result.output).isZero();
            return result.output.trim();
        }
        Result check(String from, String to) throws Exception {
            return run(Arrays.asList("bash", ".github/scripts/check-comment-only-diff.sh", "--base", from,
                    "--head", to), true);
        }
        Result run(List<String> command, boolean fault) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_CONFIG_GLOBAL", "/dev/null");
            env.put("GIT_AUTHOR_NAME", "Fixture"); env.put("GIT_COMMITTER_NAME", "Fixture");
            env.put("GIT_AUTHOR_EMAIL", "fixture@example.invalid"); env.put("GIT_COMMITTER_EMAIL", "fixture@example.invalid");
            if (fault && Files.isDirectory(bin)) env.put("PATH", bin + ":" + env.get("PATH"));
            Path output = Files.createTempFile(root, "command-", ".log");
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as(command.toString()).isTrue();
            return new Result(process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        }
        void inject(String tool, String first, String contains, int occurrence) throws IOException {
            Files.createDirectories(bin);
            String wrapper = "#!/usr/bin/env bash\nset -eu\n"
                    + "if [[ ${1:-} == '" + first + "' && \"$*\" == *'" + contains + "'* ]]; then\n"
                    + "  n=0; [[ ! -f '" + bin.resolve("count") + "' ]] || read -r n < '" + bin.resolve("count") + "'\n"
                    + "  n=$((n+1)); printf '%s\\n' \"$n\" > '" + bin.resolve("count") + "'\n"
                    + "  if [[ $n == " + occurrence + " ]]; then printf '%s\\n' \"$*\" >> '" + bin.resolve("hit")
                    + "'; printf 'injected evidence read failure\\n' >&2; exit 73; fi\nfi\n"
                    + "exec /usr/bin/" + tool + " \"$@\"\n";
            Path executable = bin.resolve(tool);
            Files.write(executable, wrapper.getBytes(StandardCharsets.UTF_8));
            assertThat(executable.toFile().setExecutable(true)).isTrue();
        }
        void assertHit() throws IOException {
            assertThat(bin.resolve("hit")).exists();
            assertThat(Files.readAllLines(bin.resolve("hit"))).hasSize(1);
        }
    }
}
