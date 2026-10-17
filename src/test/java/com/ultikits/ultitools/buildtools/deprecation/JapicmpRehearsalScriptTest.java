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

    @Test
    void arbitraryRedFailuresNeverProveCompatibility(@TempDir Path temp) throws Exception {
        for (String mode : Arrays.asList("red-compile", "red-infrastructure", "red-wrong-class",
                "red-wrong-kind", "red-compatible", "red-malformed", "red-missing", "red-success")) {
            Fixture f = new Fixture(Files.createDirectories(temp.resolve(mode)));
            f.mode = mode;
            Map<String, String> before = f.manifest();
            Result result = f.run();
            assertThat(f.read("stage")).as("required injected RED stage: %s", mode).isEqualTo("red");
            assertThat(result.exit).as("%s must not count as compatibility RED: %s", mode, result.output).isEqualTo(1);
            assertThat(result.output).doesNotContain("Rehearsal proved the pair");
            assertThat(f.manifest()).as(mode).isEqualTo(before);
        }
    }

    @Test
    void failedBuildStagesRestoreEverything(@TempDir Path temp) throws Exception {
        for (String mode : Arrays.asList("install-fail", "green-fail")) {
            Fixture f = new Fixture(Files.createDirectories(temp.resolve(mode)));
            f.mode = mode;
            Map<String, String> before = f.manifest();
            Result result = f.run();
            assertThat(result.exit).as(result.output).isEqualTo(1);
            assertThat(f.read("stage")).isEqualTo(mode.startsWith("install") ? "install" : "green");
            assertThat(f.manifest()).as(mode).isEqualTo(before);
            assertThat(result.output).doesNotContain("Rehearsal proved the pair");
        }
    }

    @Test
    void snapshotFailuresAllowNoMutation(@TempDir Path temp) throws Exception {
        for (String mode : Arrays.asList("snapshot", "allocation")) {
            Fixture f = new Fixture(Files.createDirectories(temp.resolve(mode)));
            f.mode = mode;
            f.installFaultWrappers();
            Map<String, String> before = f.manifest();
            Result result = f.run();
            assertThat(result.exit).as(result.output).isNotZero();
            assertThat(f.scratch.resolve("fault-hit")).hasContent(mode + "\n");
            assertThat(f.root.resolve("calls")).doesNotExist();
            assertThat(f.manifest()).as(mode + " before any build/mutation").isEqualTo(before);
        }
    }

    @Test
    void midRestoreFailureIsNonzeroAndFinalRetryRestoresOriginal(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        f.mode = "restore-mid";
        f.installFaultWrappers();
        Map<String, String> before = f.manifest();
        Result result = f.run();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(f.scratch.resolve("fault-hit")).hasContent("restore-mid\n");
        assertThat(result.output).contains("Mid-script POM restoration failed");
        assertThat(f.manifest()).isEqualTo(before);
    }

    @Test
    void failedFinalRestoreRetainsRecoverableOriginals(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        f.mode = "restore-final";
        f.installFaultWrappers();
        Map<String, String> before = f.manifest();
        byte[] original = Files.readAllBytes(f.root.resolve("compatibility/deprecations.json"));
        byte[] index = Files.readAllBytes(f.root.resolve(".git/index"));
        Result result = f.run();
        assertThat(result.exit).as(result.output).isEqualTo(1);
        assertThat(f.scratch.resolve("fault-hit")).hasContent("restore-final\n");
        assertThat(result.output).contains("Restoration failed; recoverable originals and manifest retained at ");
        Path backup;
        try (Stream<Path> dirs = Files.list(f.scratch)) {
            List<Path> backups = dirs.filter(p -> p.getFileName().toString().startsWith("japicmp-rehearsal.")
                    && Files.isDirectory(p)).collect(Collectors.toList());
            assertThat(backups).hasSize(1);
            backup = backups.get(0);
        }
        assertThat(result.output).contains(backup.toString());
        assertThat(Files.readAllBytes(backup.resolve("original-2"))).isEqualTo(original);
        assertThat(backup.resolve("manifest.tsv")).isNotEmptyFile();
        assertThat(Files.readAllBytes(f.root.resolve(".git/index"))).isEqualTo(index);
        Map<String, String> otherOriginals = new TreeMap<>(before);
        otherOriginals.remove("repo/compatibility/deprecations.json");
        assertThat(f.manifest()).as("all other paths still restore when one copy fails").isEqualTo(otherOriginals);
        // The wrapper fails an actual copy, not an assumed chmod effect; originals remain usable.
        Files.copy(backup.resolve("original-2"), f.root.resolve("compatibility/deprecations.json"),
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        assertThat(Files.readAllBytes(f.root.resolve("compatibility/deprecations.json"))).isEqualTo(original);
        assertThat(f.manifest()).as("reported originals recover the complete original state").isEqualTo(before);
    }

    @Test
    void handledSignalsStopAndRestore(@TempDir Path temp) throws Exception {
        for (String signal : Arrays.asList("INT", "TERM")) {
            for (String stage : Arrays.asList("install", "green", "red")) {
                Fixture f = new Fixture(Files.createDirectories(temp.resolve(signal + "-" + stage)));
                f.mode = "signal-" + signal + "-" + stage;
                Map<String, String> before = f.manifest();
                Result result = f.run();
                assertThat(f.read("stage")).isEqualTo(stage);
                assertThat(result.exit).as(result.output).isEqualTo(signal.equals("INT") ? 130 : 143);
                assertThat(f.manifest()).as(signal + " at " + stage).isEqualTo(before);
                assertThat(f.read("calls").split("\n")).hasSize(stage.equals("install") ? 1 : stage.equals("green") ? 2 : 3);
            }
        }
    }

    @Test
    void dryRunHasZeroWrites(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        Map<String, String> before = f.manifest();
        Result result = f.run("--dry-run");
        assertThat(result.exit).as(result.output).isZero();
        assertThat(f.manifest()).isEqualTo(before);
        assertThat(f.root.resolve("calls")).doesNotExist();
        try (Stream<Path> entries = Files.list(f.scratch)) {
            assertThat(entries.map(p -> p.getFileName().toString()).collect(Collectors.toList()))
                    .containsExactlyInAnyOrder("git.log", "run.log");
        }
    }

    @Test
    void keepArtifactsRetainsOnlyDeliberateCoordinateWrites(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        Map<String, String> before = f.manifest();
        Result result = f.run("--keep-artifacts");
        assertThat(result.exit).as(result.output).isZero();
        Map<String, String> after = f.manifest();
        before.keySet().removeIf(k -> k.startsWith("local/com/ultikits/UltiTools-API"));
        after.keySet().removeIf(k -> k.startsWith("local/com/ultikits/UltiTools-API"));
        assertThat(after).isEqualTo(before);
        assertThat(f.local.resolve("com/ultikits/UltiTools-API/6.3.0/UltiTools-API-6.3.0.jar"))
                .hasContent("candidate run");
    }

    @Test
    void absentCoordinateFamilyAndEffectiveLocalRepositoryAreOwnedExactly(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp);
        Path originalHomeRepository = f.local;
        f.local = Files.createDirectories(temp.resolve("selected-local-repository"));
        Files.write(f.local.resolve("sentinel"), new byte[] {42});
        Map<String, String> before = f.manifest();
        byte[] homeCandidate = Files.readAllBytes(originalHomeRepository.resolve(
                "com/ultikits/UltiTools-API/6.3.0/UltiTools-API-6.3.0.jar"));
        Result result = f.run();
        assertThat(result.exit).as(result.output).isZero();
        assertThat(f.manifest()).isEqualTo(before);
        assertThat(f.read("calls").split("\n")).allMatch(s -> s.contains("-Dmaven.repo.local=" + f.local));
        assertThat(Files.readAllBytes(originalHomeRepository.resolve(
                "com/ultikits/UltiTools-API/6.3.0/UltiTools-API-6.3.0.jar"))).isEqualTo(homeCandidate);
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
        Path local;
        final Path scratch;
        String mode = "normal";

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
                    + "local_repo=\"${TEST_LOCAL_REPO:-$HOME/.m2/repository}\"\n"
                    + "for arg in \"$@\"; do case \"$arg\" in -Dmaven.repo.local=*) local_repo=${arg#*=};; esac; done\n"
                    + "if [[ \" $* \" == *help:evaluate* ]]; then printf '%s\\n' \"$local_repo\"; exit 0; fi\n"
                    + "printf '%s\\n' \"$*\" >> calls\n"
                    + "mkdir -p target/japicmp \"$local_repo/com/ultikits/UltiTools-API/6.3.0\"\n"
                    + "printf 'candidate run' > \"$local_repo/com/ultikits/UltiTools-API/6.3.0/UltiTools-API-6.3.0.jar\"\n"
                    + "printf 'metadata run' > \"$local_repo/com/ultikits/UltiTools-API/maven-metadata-local.xml\"\n"
                    + "printf 'generated' > compatibility/deprecations.json\n"
                    + "printf 'generated' > compatibility/DEPRECATIONS.md\n"
                    + "printf 'generated' > .flattened-pom.xml\n"
                    + "if [[ \" $* \" == *' install '* ]]; then\n"
                    + "printf install > stage\n"
                    + "case \"$REHEARSAL_TEST_MODE\" in install-fail) exit 21;; signal-*-install) signal=${REHEARSAL_TEST_MODE#signal-}; kill -s \"${signal%-install}\" \"$PPID\";; esac\n"
                    + "exit 0; fi\n"
                    + "printf green > stage\n"
                    + "if /usr/bin/grep -q 'public final class' " + SOURCE + "; then\n"
                    + "printf red > stage\n"
                    + "case \"$REHEARSAL_TEST_MODE\" in\n"
                    + "red-success) exit 0;;\n"
                    + "red-compile) echo '[ERROR] compiler failed for UltiPanelLogTransmitter'; exit 1;;\n"
                    + "red-infrastructure) echo '[ERROR] dependency resolution failed for UltiPanelLogTransmitter'; exit 1;;\n"
                    + "esac\n"
                    + "elif [[ \"$REHEARSAL_TEST_MODE\" == green-fail ]]; then exit 22; fi\n"
                    + "stage=$(<stage); case \"$REHEARSAL_TEST_MODE\" in signal-*-$stage) signal=${REHEARSAL_TEST_MODE#signal-}; signal=${signal%-$stage}; kill -s \"$signal\" \"$PPID\"; exit 0;; esac\n"
                    + "echo '[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ UltiTools-API ---'\n"
                    + "echo '[INFO] Compiling 1 source file with javac [debug target 8] to target/classes'\n"
                    + "echo '[INFO] --- japicmp-maven-plugin:0.26.1:cmp (default) @ UltiTools-API ---'\n"
                    + "if /usr/bin/grep -q 'public final class' " + SOURCE + "; then\n"
                    + "printf '%s\\n' '<japicmp><classes><class fullyQualifiedName=\"" + FQN
                    + "\" binaryCompatible=\"false\"><compatibilityChanges><compatibilityChange binaryCompatible=\"false\" type=\"CLASS_NOW_NOT_EXTENDABLE\"/></compatibilityChanges></class></classes></japicmp>' > target/japicmp/japicmp.xml\n"
                    + "case \"$REHEARSAL_TEST_MODE\" in\n"
                    + "red-wrong-class) /usr/bin/sed -i 's/manager.UltiPanelLogTransmitter/manager.Other/' target/japicmp/japicmp.xml;;\n"
                    + "red-wrong-kind) /usr/bin/sed -i 's/CLASS_NOW_NOT_EXTENDABLE/METHOD_NOW_FINAL/' target/japicmp/japicmp.xml;;\n"
                    + "red-compatible) /usr/bin/sed -i 's/binaryCompatible=\"false\"/binaryCompatible=\"true\"/g' target/japicmp/japicmp.xml;;\n"
                    + "red-malformed) printf '<japicmp' > target/japicmp/japicmp.xml;;\n"
                    + "red-missing) rm -f target/japicmp/japicmp.xml;;\n"
                    + "esac\n"
                    + "echo '[ERROR] Failed to execute goal com.github.siom79.japicmp:japicmp-maven-plugin:0.26.1:cmp: " + FQN + " CLASS_NOW_NOT_EXTENDABLE'\n"
                    + "exit 1\nfi\n"
                    + "printf '%s\\n' '<japicmp><classes><class fullyQualifiedName=\"" + FQN
                    + "\" binaryCompatible=\"true\"/></classes></japicmp>' > target/japicmp/japicmp.xml\n";
        }

        void installFaultWrappers() throws IOException {
            String cp = "#!/usr/bin/env bash\nset -euo pipefail\n"
                    + "src=${@: -2:1}; dest=${@: -1}\n"
                    + "hit=\"$TMPDIR/fault-hit\"\n"
                    + "case \"$REHEARSAL_TEST_MODE\" in\n"
                    + "snapshot) if [[ \"$dest\" == */original-2 ]]; then echo snapshot > \"$hit\"; exit 73; fi;;\n"
                    + "restore-mid) if [[ \"$src\" == */original-0 && ! -e \"$hit\" && -e stage ]]; then echo restore-mid > \"$hit\"; exit 74; fi;;\n"
                    + "restore-final) if [[ \"$src\" == */original-2 && -e stage ]]; then echo restore-final > \"$hit\"; exit 75; fi;;\n"
                    + "esac\nexec /usr/bin/cp \"$@\"\n";
            Files.write(bin.resolve("cp"), cp.getBytes(StandardCharsets.UTF_8));
            bin.resolve("cp").toFile().setExecutable(true);
            Files.write(bin.resolve("mktemp"), ("#!/usr/bin/env bash\n"
                    + "if [[ \"$REHEARSAL_TEST_MODE\" == allocation ]]; then echo allocation > \"$TMPDIR/fault-hit\"; exit 76; fi\n"
                    + "exec /usr/bin/mktemp \"$@\"\n").getBytes(StandardCharsets.UTF_8));
            bin.resolve("mktemp").toFile().setExecutable(true);
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
            Map<String, String> before = manifest();
            Path log = scratch.resolve("run.log");
            ProcessBuilder builder = new ProcessBuilder(argv).directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("HOME", home.toString());
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            builder.environment().put("TMPDIR", scratch.toString());
            builder.environment().put("REHEARSAL_TEST_MODE", mode);
            builder.environment().put("TEST_LOCAL_REPO", local.toString());
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = builder.start();
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("bounded real-script fixture").isTrue();
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            Map<String, Object> record = new TreeMap<>();
            record.put("mode", mode);
            record.put("args", argv);
            record.put("exit", p.exitValue());
            record.put("before", before);
            record.put("after", manifest());
            record.put("log", output);
            record.put("stage", Files.exists(root.resolve("stage")) ? read("stage") : "no-build");
            record.put("faultHit", Files.exists(scratch.resolve("fault-hit"))
                    ? new String(Files.readAllBytes(scratch.resolve("fault-hit")), StandardCharsets.UTF_8) : "none");
            System.out.println("FIXTURE " + new com.google.gson.Gson().toJson(record));
            String archive = System.getenv("JAPICMP_FIXTURE_EVIDENCE");
            if (archive != null) {
                Path folder = Files.createTempDirectory(Paths.get(archive), mode + "-");
                Files.write(folder.resolve("result.json"), new com.google.gson.GsonBuilder().setPrettyPrinting()
                        .create().toJson(record).getBytes(StandardCharsets.UTF_8));
                try (Stream<Path> paths = Files.walk(scratch)) {
                    for (Path path : paths.filter(pth -> pth.toString().contains("japicmp-rehearsal."))
                            .collect(Collectors.toList())) {
                        Path dest = folder.resolve("recovery").resolve(scratch.relativize(path));
                        if (Files.isDirectory(path)) { Files.createDirectories(dest); }
                        else { Files.copy(path, dest); }
                    }
                }
            }
            return new Result(p.exitValue(), output);
        }

        Map<String, String> manifest() throws Exception {
            Map<String, String> result = new TreeMap<>();
            for (Path base : Arrays.asList(root, local)) {
                try (Stream<Path> paths = Files.walk(base)) {
                    for (Path path : paths.collect(Collectors.toList())) {
                        String rel = base.relativize(path).toString();
                        if (base.equals(root) && (rel.equals("target") || rel.startsWith("target/")
                                || (rel.startsWith(".git/") && !rel.equals(".git/index"))
                                || rel.equals(".git") || rel.equals("calls") || rel.equals("stage"))) { continue; }
                        String content = "directory";
                        if (!Files.isDirectory(path)) {
                            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                            StringBuilder hex = new StringBuilder();
                            for (byte value : digest) { hex.append(String.format("%02x", value & 0xff)); }
                            content = hex.toString();
                        }
                        result.put((base.equals(root) ? "repo/" : "local/") + rel,
                                PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) + ":" + content);
                    }
                }
            }
            return result;
        }
    }
}
