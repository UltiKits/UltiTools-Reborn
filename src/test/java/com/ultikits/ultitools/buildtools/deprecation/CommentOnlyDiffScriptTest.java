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
    private static final Path SCRIPT = Paths.get(System.getProperty("commentOnlyScript",
            ".github/scripts/check-comment-only-diff.sh")).toAbsolutePath();

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

    @Test void resolvedEndpointsStayImmutableWhenNamedRefsMove() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        f.git("branch", "moving-base", f.base);
        f.git("branch", "moving-head", f.head);
        f.write("Changed.java", "int changedCode = 99;\n");
        String code = f.commit();
        Files.createDirectories(f.bin);
        String wrapper = "#!/usr/bin/env bash\nset -eu\n"
                + "if [[ ${1:-} == diff && ! -f '" + f.bin.resolve("hit") + "' ]]; then\n"
                + " /usr/bin/git update-ref refs/heads/moving-head '" + code + "'\n"
                + " printf '%s\\n' moved >> '" + f.bin.resolve("hit") + "'\nfi\n"
                + "exec /usr/bin/git \"$@\"\n";
        Path executable = f.bin.resolve("git");
        Files.write(executable, wrapper.getBytes(StandardCharsets.UTF_8));
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        Result result = f.check("moving-base", "moving-head");
        f.assertHit();
        assertThat(f.git("rev-parse", "moving-head")).isEqualTo(code);
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("SUMMARY: assertion A PASS, assertion B PASS, assertion C PASS.");
    }

    @Test void finiteGitEvidenceReadFailuresAreReached() throws Exception {
        // Mixed lines reach A's cache before B's full-tree extraction and C's full-blob check.
        String before = "class Changed { int n = 1; // before\n}\n";
        String after = "class Changed { int n = 1; // after\n}\n";
        for (String side : Arrays.asList("base", "head")) {
            for (String operation : Arrays.asList("cache-tree", "cache-blob", "literal-tree",
                    "literal-changed", "literal-unchanged", "crlf-tree", "crlf-blob")) {
                Fixture f = fixture(before, after);
                assertThat(f.check(f.base, f.head).exit).isZero();
                String oid = "base".equals(side) ? f.base : f.head;
                String path = "src/main/java/Changed.java";
                String verb;
                String selector;
                String diagnostic;
                int occurrence = 1;
                switch (operation) {
                    case "cache-tree": verb = "ls-tree"; selector = oid + " -- " + path; diagnostic = "existence tree"; break;
                    case "cache-blob": verb = "show"; selector = oid + ":" + path; diagnostic = "state-cache blob"; break;
                    case "literal-tree": verb = "ls-tree"; selector = oid + " -- src/main/java"; diagnostic = "literal tree"; break;
                    case "literal-changed": verb = "show"; selector = oid + ":" + path; diagnostic = "literal blob"; occurrence = 2; break;
                    case "literal-unchanged": verb = "show"; selector = oid + ":src/main/java/Unchanged.java"; diagnostic = "literal blob"; break;
                    case "crlf-tree": verb = "ls-tree"; selector = oid + " -- " + path; diagnostic = "existence tree"; occurrence = 2; break;
                    default: verb = "show"; selector = oid + ":" + path; diagnostic = "CRLF blob"; occurrence = 3;
                }
                // A scoped tree selector must not also match A/C's path-specific existence queries.
                if ("literal-tree".equals(operation)) selector += "|END";
                f.inject("git", verb, selector, occurrence);
                Result result = f.check(f.base, f.head);
                f.assertHit();
                assertInability(result, diagnostic);
                assertThat(result.output).contains(oid);
            }
        }
        for (String kind : Arrays.asList("unified", "changed")) {
            Fixture f = fixture(before, after);
            assertThat(f.check(f.base, f.head).exit).isZero();
            f.inject("git", "diff", "unified".equals(kind) ? "--unified=0" : "--name-only",
                    "unified".equals(kind) ? 1 : 2);
            Result result = f.check(f.base, f.head);
            f.assertHit();
            assertInability(result, "unified".equals(kind) ? "assertion A" : "assertion C");
        }
    }

    @Test void captureCacheMatcherSortAndComparisonFailuresAreReached() throws Exception {
        String[][] cases = {
            {"cat", "--", "main-diff", "1", "read capture"},
            {"cat", "--", "a-diff", "1", "read capture"},
            {"cat", "--", "state-blob", "1", "read capture"},
            {"cat", "--", "state-blob", "2", "read capture"},
            {"cat", "--", "literal-tree", "1", "read capture"},
            {"cat", "--", "literal-tree", "2", "read capture"},
            {"cat", "--", "literal-diff", "1", "read capture"},
            {"cat", "--", "c-diff", "1", "read capture"},
            {"cat", "--", "existence", "1", "read capture"},
            {"cat", "--", "existence", "2", "read capture"},
            {"cat", "--", "existence", "3", "read capture"},
            {"cat", "--", "existence", "4", "read capture"},
            {"sed", "-n", "state.", "1", "read state cache"},
            {"sed", "-n", "state.", "2", "read state cache"},
            {"grep", "-oP", "literal-blob", "1", "literal matcher"},
            {"grep", "-oP", "literal-blob", "3", "literal matcher"},
            {"grep", "-ca", "crlf-blob", "1", "CRLF matcher"},
            {"grep", "-ca", "crlf-blob", "2", "CRLF matcher"},
            {"sort", "", "base-literals", "1", "base literal sort"},
            {"sort", "", "head-literals", "1", "head literal sort"},
            {"diff", "", "base-list", "1", "literal multiset diff"},
            {"mktemp", "-d", "", "1", "temporary evidence directory"},
            {"mktemp", "", "state.", "1", "state cache allocation"}
        };
        for (String[] c : cases) {
            Fixture f = fixture("class C { int n = 1; // before\n}\n", "class C { int n = 1; // after\n}\n");
            assertThat(f.check(f.base, f.head).exit).isZero();
            f.inject(c[0], c[1], c[2], Integer.parseInt(c[3]));
            Result result = f.check(f.base, f.head);
            f.assertHit();
            assertInability(result, c[4]);
        }
    }

    @Test void evidenceCaptureWriteFailuresCannotBecomeEmptyInputs() throws Exception {
        for (String capture : Arrays.asList("main-diff", "a-diff", "existence", "state-blob",
                "literal-tree", "literal-blob", "base-literals", "head-literals", "base-list",
                "head-list", "literal-diff", "c-diff", "crlf-blob")) {
            Fixture f = fixture("int n = 1; // before\n", "int n = 1; // after\n");
            assertThat(f.check(f.base, f.head).exit).isZero();
            f.blockCapture(capture);
            Result result = f.check(f.base, f.head);
            f.assertHit();
            assertInability(result, "evidence");
        }
    }

    @Test void selfTestFixtureReadErrorNamesTheFailedOperation() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        assertThat(f.selfTest().exit).isZero();
        f.inject("git", "hash-object", "-w", 1);
        Result result = f.selfTest();
        f.assertHit();
        assertInability(result, "self-test hash-object");
    }

    @Test void selfTestRetainsLargeBlobAndTrailingCommentControls() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        Result result = f.selfTest();
        assertThat(result.exit).as(result.output).isZero();
        for (int i = 1; i <= 5; i++) assertThat(result.output).contains("PASS: assertion " + i);
        assertThat(result.output).contains("large genuinely-CRLF", "unchanged large CRLF", "genuine code change")
                .doesNotContain("FAIL:", "ERROR:");
    }

    @Test void codeLiteralMultiplicityAndCrLfViolationsRemainExitOne() throws Exception {
        String[][] pairs = {
            {"int n = 1; // same\n", "int n = 2; // same\n", "assertion A"},
            {"// i18n(\"before\")\n", "// i18n(\"after\")\n", "assertion B"},
            {"// i18n(\"same\")\n", "// i18n(\"same\") i18n(\"same\")\n", "assertion B"},
            {"// same\r\n", "// same\n", "assertion C"},
            {"/* opener\nold words\n*/\n", "/* opener\nnew words\n*/\n", "REFUSED"}
        };
        for (String[] pair : pairs) {
            Fixture f = fixture(pair[0], pair[1]);
            Result result = f.check(f.base, f.head);
            assertThat(result.exit).as(result.output).isEqualTo(1);
            assertThat(result.output).contains(pair[2]).doesNotContain("ERROR:");
        }
    }

    @Test void addedAndDeletedCommentFilesHaveCertifiedAbsentSides() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        f.write("Added.java", "// newly added\n");
        String added = f.commit();
        Result addition = f.check(f.head, added);
        assertThat(addition.exit).as(addition.output).isZero();
        assertThat(addition.output).contains("added at head (no base blob)");
        Files.delete(f.root.resolve("src/main/java/Added.java"));
        String deleted = f.commit();
        Result deletion = f.check(added, deleted);
        assertThat(deletion.exit).as(deletion.output).isZero();
        assertThat(deletion.output).contains("removed at head (no head blob)");
    }

    @Test void missingSideDoesNotHideTreeOrPresentBlobReadFailure() throws Exception {
        for (boolean deletion : Arrays.asList(false, true)) {
            for (boolean tree : Arrays.asList(false, true)) {
                Fixture f = fixture("// before\n", "// after\n");
                f.write("Added.java", "// added\n");
                String added = f.commit();
                Files.delete(f.root.resolve("src/main/java/Added.java"));
                String removed = f.commit();
                String from = deletion ? added : f.head;
                String to = deletion ? removed : added;
                assertThat(f.check(from, to).exit).isZero();
                String oid = tree ? (deletion ? removed : f.head) : added;
                f.inject("git", tree ? "ls-tree" : "show",
                        tree ? oid + " -- src/main/java/Added.java" : oid + ":src/main/java/Added.java", 1);
                Result result = f.check(from, to);
                f.assertHit();
                assertInability(result, tree ? "existence tree" : "literal blob");
            }
        }
    }

    @Test void usageErrorsAreExitSixtyFour() throws Exception {
        Fixture f = fixture("// before\n", "// after\n");
        for (String option : Arrays.asList("--unknown", "--base", "--head", "--scope")) {
            assertThat(f.run(Arrays.asList("bash", ".github/scripts/check-comment-only-diff.sh", option), true).exit)
                    .as(option).isEqualTo(64);
        }
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
        Result selfTest() throws Exception {
            return run(Arrays.asList("bash", ".github/scripts/check-comment-only-diff.sh", "--self-test"), true);
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
                    + "if [[ ${1:-} == " + (first.isEmpty() ? "*" : "'" + first + "'")
                    + " && \"$*|END\" == *'" + contains + "'* ]]; then\n"
                    + "  n=0; [[ ! -f '" + bin.resolve("count") + "' ]] || read -r n < '" + bin.resolve("count") + "'\n"
                    + "  n=$((n+1)); printf '%s\\n' \"$n\" > '" + bin.resolve("count") + "'\n"
                    + "  if [[ $n == " + occurrence + " ]]; then { printf '%q ' \"$@\"; printf '\\n'; } >> '" + bin.resolve("hit")
                    + "'; printf 'injected evidence read failure\\n' >&2; exit 73; fi\nfi\n"
                    + "exec /usr/bin/" + tool + " \"$@\"\n";
            Path executable = bin.resolve(tool);
            Files.write(executable, wrapper.getBytes(StandardCharsets.UTF_8));
            assertThat(executable.toFile().setExecutable(true)).isTrue();
        }
        void blockCapture(String name) throws IOException {
            Files.createDirectories(bin);
            String wrapper = "#!/usr/bin/env bash\nset -eu\n"
                    + "dir=$(/usr/bin/mktemp \"$@\")\n"
                    + "if [[ ${1:-} == -d ]]; then mkdir \"$dir/" + name + "\"; printf '%s\\n' 'blocked capture "
                    + name + "' >> '" + bin.resolve("hit") + "'; fi\nprintf '%s\\n' \"$dir\"\n";
            Path executable = bin.resolve("mktemp");
            Files.write(executable, wrapper.getBytes(StandardCharsets.UTF_8));
            assertThat(executable.toFile().setExecutable(true)).isTrue();
        }
        void assertHit() throws IOException {
            assertThat(bin.resolve("hit")).exists();
            List<String> hits = Files.readAllLines(bin.resolve("hit"));
            assertThat(hits).hasSize(1);
            System.out.println("REACHED fault selector: " + hits.get(0));
        }
    }
}
