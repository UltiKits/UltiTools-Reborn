package com.ultikits.ultitools.buildtools.deprecation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real-script control-flow fixtures. Fake Maven is never binary-compatibility evidence. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class JapicmpRehearsalScriptTest {
    private static final String SOURCE = "src/main/java/com/ultikits/ultitools/manager/UltiPanelLogTransmitter.java";
    private static final String FQN = "com.ultikits.ultitools.manager.UltiPanelLogTransmitter";

    @Test
    void dirtyCompatibilityBytesAndIndexSurviveRehearsal(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        Map<String, String> before = f.manifest();
        Result result = f.run();
        assertThat(result.exit).as(result.output).isZero();
        assertThat(f.manifest()).as("actual pre-run bytes, modes, index and candidate metadata, not HEAD")
                .isEqualTo(before);
        assertThat(result.output).contains("GREEN leg exit code: 0", "RED leg exit code: 1");
        assertThat(f.read("calls")).contains("install", "verify");
    }

    @Test
    void absentGeneratedFilesStayAbsent(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        Files.delete(f.root.resolve("compatibility/DEPRECATIONS.md"));
        Files.delete(f.root.resolve(".flattened-pom.xml"));
        Map<String, String> before = f.manifest();
        Result result = f.run();
        assertThat(result.exit).as(result.output).isZero();
        assertThat(f.manifest()).as("absent-before outputs must not be recreated by cleanup").isEqualTo(before);
    }

    @Test
    void dirtyPomAndBreakSourceFailBeforeMutation(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        for (String path : Arrays.asList("pom.xml", SOURCE)) {
            byte[] original = Files.readAllBytes(f.root.resolve(path));
            Files.write(f.root.resolve(path), (f.read(path) + "\n// dirty\n").getBytes(StandardCharsets.UTF_8));
            Map<String, String> before = f.manifest();
            Result result = f.run();
            assertThat(result.exit).as(result.output).isEqualTo(64);
            assertThat(f.manifest()).isEqualTo(before);
            assertThat(f.root.resolve("calls")).doesNotExist();
            Files.write(f.root.resolve(path), original);
        }
    }

    private static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }

    private static final class Fixture {
        final Path root;
        final Path bin;
        final Path home;
        final Path local;
        final Path scratch;

        Fixture(Path temp) throws Exception {
            root = Files.createDirectories(temp.resolve("repo"));
            bin = Files.createDirectories(temp.resolve("bin"));
            home = Files.createDirectories(temp.resolve("home"));
            local = Files.createDirectories(home.resolve(".m2/repository"));
            scratch = Files.createDirectories(temp.resolve("scratch"));
            write("pom.xml", "<project>\r\n<modelVersion>4.0.0</modelVersion>\r\n"
                    + "<groupId>com.ultikits</groupId><artifactId>UltiTools-API</artifactId>\r\n"
                    + "<version>6.3.0-SNAPSHOT</version>\r\n"
                    + "<properties><japicmp.baseline.version>6.2.5</japicmp.baseline.version></properties>\r\n"
                    + "<build><plugins><plugin><artifactId>japicmp-maven-plugin</artifactId>\r\n"
                    + "<!-- A literal <excludes> in a comment is not the element. -->\r\n"
                    + "<configuration><ignoreMissingOldVersion>true</ignoreMissingOldVersion>\r\n"
                    + "<excludes><exclude>old.Api</exclude></excludes></configuration>\r\n"
                    + "</plugin></plugins></build></project>\r\n");
            write(SOURCE, "package com.ultikits.ultitools.manager;\npublic class UltiPanelLogTransmitter {}\n");
            Files.createDirectories(root.resolve("src/test/java"));
            write("compatibility/deprecations.json", "[]\n");
            write("compatibility/DEPRECATIONS.md", "# baseline\n");
            write("compatibility/unrelated.txt", "unrelated compatibility sentinel\n");
            Path scripts = Files.createDirectories(root.resolve(".github/scripts"));
            Path tested = Paths.get(System.getProperty("japicmp.rehearsal.script", ".github/scripts/japicmp-rehearsal.sh"));
            Files.copy(tested, scripts.resolve("japicmp-rehearsal.sh"));
            git("init", "-q");
            git("config", "user.name", "Fixture");
            git("config", "user.email", "fixture@example.invalid");
            git("add", "pom.xml", SOURCE, "compatibility", ".github");
            // This is an isolated local repository, not a product commit or hook bypass.
            git("commit", "-q", "-m", "fixture baseline");
            write("compatibility/deprecations.json", "[ ]\n");
            git("add", "compatibility/deprecations.json");
            write("compatibility/deprecations.json", "[  ]\r\n");
            write("compatibility/DEPRECATIONS.md", "# operator draft\r\n");
            Files.setPosixFilePermissions(root.resolve("compatibility/DEPRECATIONS.md"),
                    PosixFilePermissions.fromString("rw-r-----"));
            write(".flattened-pom.xml", "operator flatten sentinel\n");
            write("unrelated.txt", "outside write set\n");
            Path family = Files.createDirectories(local.resolve("com/ultikits/UltiTools-API/6.3.0"));
            Files.write(family.resolve("UltiTools-API-6.3.0.jar"), "preexisting candidate".getBytes(StandardCharsets.UTF_8));
            Files.write(family.getParent().resolve("maven-metadata-local.xml"), "preexisting metadata".getBytes(StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(family.getParent().resolve("maven-metadata-local.xml"),
                    PosixFilePermissions.fromString("rw-------"));
            Files.write(local.resolve("unrelated-sentinel"), "unrelated local artifact".getBytes(StandardCharsets.UTF_8));
            Files.write(bin.resolve("mvn"), fakeMaven().getBytes(StandardCharsets.UTF_8));
            bin.resolve("mvn").toFile().setExecutable(true);
        }

        private String fakeMaven() {
            return "#!/usr/bin/env bash\nset -euo pipefail\n"
                    + "local_repo=\"$HOME/.m2/repository\"\n"
                    + "for arg in \"$@\"; do case \"$arg\" in -Dmaven.repo.local=*) local_repo=${arg#*=};; esac; done\n"
                    + "if [[ \" $* \" == *help:evaluate* ]]; then printf '%s\\n' \"$local_repo\"; exit 0; fi\n"
                    + "printf '%s\\n' \"$*\" >> calls\n"
                    + "mkdir -p target/japicmp \"$local_repo/com/ultikits/UltiTools-API/6.3.0\"\n"
                    + "printf 'candidate run' > \"$local_repo/com/ultikits/UltiTools-API/6.3.0/UltiTools-API-6.3.0.jar\"\n"
                    + "printf 'metadata run' > \"$local_repo/com/ultikits/UltiTools-API/maven-metadata-local.xml\"\n"
                    + "printf 'generated' > compatibility/deprecations.json\n"
                    + "printf 'generated' > compatibility/DEPRECATIONS.md\n"
                    + "printf 'generated' > .flattened-pom.xml\n"
                    + "if [[ \" $* \" == *' install '* ]]; then exit 0; fi\n"
                    + "echo '[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ UltiTools-API ---'\n"
                    + "echo '[INFO] Compiling 1 source file with javac [debug target 8] to target/classes'\n"
                    + "echo '[INFO] --- japicmp-maven-plugin:0.26.1:cmp (default) @ UltiTools-API ---'\n"
                    + "if /usr/bin/grep -q 'public final class' " + SOURCE + "; then\n"
                    + "printf '%s\\n' '<japicmp><classes><class fullyQualifiedName=\"" + FQN
                    + "\" binaryCompatible=\"false\"><compatibilityChanges><compatibilityChange binaryCompatible=\"false\" type=\"CLASS_NOW_NOT_EXTENDABLE\"/></compatibilityChanges></class></classes></japicmp>' > target/japicmp/japicmp.xml\n"
                    + "echo '[ERROR] Failed to execute goal com.github.siom79.japicmp:japicmp-maven-plugin:0.26.1:cmp: " + FQN + " CLASS_NOW_NOT_EXTENDABLE'\n"
                    + "exit 1\nfi\n"
                    + "printf '%s\\n' '<japicmp><classes><class fullyQualifiedName=\"" + FQN
                    + "\" binaryCompatible=\"true\"/></classes></japicmp>' > target/japicmp/japicmp.xml\n";
        }

        void write(String path, String value) throws IOException {
            Path file = root.resolve(path);
            Files.createDirectories(file.getParent());
            Files.write(file, value.getBytes(StandardCharsets.UTF_8));
        }

        String read(String path) throws IOException {
            return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
        }

        void git(String... args) throws Exception {
            List<String> argv = new ArrayList<>(Arrays.asList("git"));
            argv.addAll(Arrays.asList(args));
            Path log = scratch.resolve("git.log");
            ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = builder.start();
            assertThat(p.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)).isZero();
        }

        Result run(String... args) throws Exception {
            List<String> argv = new ArrayList<>(Arrays.asList("bash", ".github/scripts/japicmp-rehearsal.sh"));
            argv.addAll(Arrays.asList(args));
            Path log = scratch.resolve("run.log");
            ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("HOME", home.toString());
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            builder.environment().put("TMPDIR", scratch.toString());
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = builder.start();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("bounded real-script fixture").isTrue();
            return new Result(p.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        }

        Map<String, String> manifest() throws Exception {
            Map<String, String> result = new TreeMap<>();
            for (Path base : Arrays.asList(root, local)) {
                try (Stream<Path> paths = Files.walk(base)) {
                    for (Path path : paths.collect(Collectors.toList())) {
                        String rel = base.relativize(path).toString();
                        if (base.equals(root) && (rel.equals("target") || rel.startsWith("target/")
                                || (rel.startsWith(".git/") && !rel.equals(".git/index"))
                                || rel.equals(".git") || rel.equals("calls"))) { continue; }
                        String content = Files.isDirectory(path) ? "directory" : java.util.Base64.getEncoder()
                                .encodeToString(Files.readAllBytes(path));
                        result.put((base.equals(root) ? "repo/" : "local/") + rel,
                                PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) + ":" + content);
                    }
                }
            }
            return result;
        }
    }
}
