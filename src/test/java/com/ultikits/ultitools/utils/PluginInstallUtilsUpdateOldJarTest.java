package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * #505: {@link PluginInstallUtils#updatePlugin(String)} downloaded the new module jar, then
 * called {@code oldJar.delete()}, ignored its result and returned {@code true} either way. When
 * the delete failed, {@code /upm update} reported success while the old jar stayed next to the new
 * one, so the next start found two jars for the same module.
 * <p>
 * The catalogue is a local {@link HttpServer}; the download is real. To make only the old jar's
 * delete fail, the new jar's file is created before the plugins folder is made read-only: writing
 * an existing file needs only the file's own write permission, while deleting a directory entry
 * needs the directory's.
 */
@DisplayName("PluginInstallUtils#updatePlugin reports a failed old-jar delete (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsUpdateOldJarTest {

    private static final String IDENTIFY_STRING = "fixture-module";
    private static final byte[] NEW_JAR_BYTES = "new-version-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File dataFolder;

    private HttpServer server;
    /** What the local catalogue serves as the artifact; a real jar only where a test needs one. */
    private volatile byte[] artifactBytes = NEW_JAR_BYTES;
    private File pluginsFolder;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginsFolder = new File(dataFolder, "plugins");
        assertThat(pluginsFolder.mkdirs()).isTrue();
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getDataFolder()).thenReturn(dataFolder));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/plugin/get", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":{\"id\":7,\"identifyString\":\"" + IDENTIFY_STRING + "\"}}"));
        server.createContext("/plugin/7/latest", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"2.0.0\"}"));
        server.createContext("/plugin/7/2.0.0/download", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"" + origin + "/artifact.jar\"}"));
        server.createContext("/artifact.jar", exchange -> {
            byte[] body = artifactBytes;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PluginInstallUtils.setBaseUrlForTesting(origin);
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("control: a writable folder updates, deletes the old jar and reports success")
    void writableFolder_deletesOldJarAndReportsSuccess() throws IOException {
        File oldJar = writeOldJar();

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar")).hasBinaryContent(NEW_JAR_BYTES);
    }

    @Test
    @DisplayName("an old jar that cannot be deleted is reported as a failure naming that jar, never as success")
    void undeletableOldJar_isReportedAsFailureNamingTheJar() throws IOException {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the delete fail");
        File oldJar = writeOldJar();
        File newJar = new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar");
        assertThat(newJar.createNewFile()).isTrue();

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the delete cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

            assertThat(newJar)
                    .as("control: the download itself must have happened, so only the old jar's delete failed")
                    .hasBinaryContent(NEW_JAR_BYTES);
            assertThat(thrown)
                    .as("a failed old-jar delete must not be reported as a successful update (#505)")
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(FileSystemException.class);
            assertThat(((FileSystemException) thrown.getCause()).getFile()).isEqualTo(oldJar.getAbsolutePath());
            assertThat(oldJar).exists();
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    @Test
    @DisplayName("review CR-02: every jar of the module other than the downloaded one is deleted, not only the first one found")
    void twoOldJars_bothAreDeleted() throws IOException {
        File first = writeOldJar("1.0.0");
        File second = writeOldJar("1.5.0");

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(first).doesNotExist();
        assertThat(second)
                .as("any older jar left next to the new one loads a second version on restart")
                .doesNotExist();
        assertThat(new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar")).hasBinaryContent(NEW_JAR_BYTES);
    }

    @Test
    @DisplayName("review CR-02 (order-dependent regression guard): a retry with the new jar already on disk still deletes the old jar")
    void retryWithNewJarAlreadyPresent_deletesTheOldJar() throws IOException {
        File oldJar = writeOldJar("1.0.0");
        // The previous, failed attempt already left the new version on disk, carrying the same
        // identify-string. Order-dependent: the defective single-match lookup took whichever jar
        // File#listFiles() returned first, so on a listing that returns the old jar first this
        // test passes against the defective code too (it did in this repository's RED run). It
        // pins the retry scenario as a regression guard only; twoOldJars_bothAreDeleted is the
        // test that is deterministically red against the old code.
        writeOldJar("2.0.0");

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar")).hasBinaryContent(NEW_JAR_BYTES);
    }

    @Test
    @DisplayName("review CR-02: when several old jars cannot be deleted, the failure names every one of them")
    void twoUndeletableOldJars_failureNamesEveryRemainingJar() throws IOException {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the delete fail");
        File first = writeOldJar("1.0.0");
        File second = writeOldJar("1.5.0");
        assertThat(new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar").createNewFile()).isTrue();

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the delete cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

            assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(FileSystemException.class);
            FileSystemException failure = (FileSystemException) thrown.getCause();
            java.util.List<String> named = new java.util.ArrayList<>();
            named.add(failure.getFile());
            for (Throwable suppressed : failure.getSuppressed()) {
                named.add(((FileSystemException) suppressed).getFile());
            }
            assertThat(named).containsExactlyInAnyOrder(first.getAbsolutePath(), second.getAbsolutePath());
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    @Test
    @DisplayName("review r2 WR-01: a second name for the downloaded file is recognised as the download, so the download is never deleted")
    void aliasOfTheDownloadedFile_isNotDeletedAsAnOlderJar() throws IOException {
        // A case-insensitive filesystem (NTFS, default APFS) writes the lower-cased download name
        // into an existing differently-cased file and lists it under that stored name. Linux has no
        // such aliasing, so a symbolic link models the same thing: two directory entries, one file.
        File oldJar = writeOldJar("1.0.0");
        File storedName = new File(pluginsFolder, "Fixture-Module-2.0.0.jar");
        Path downloadName = new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar").toPath();
        assertThat(storedName.createNewFile()).isTrue();
        try {
            Files.createSymbolicLink(downloadName, storedName.toPath().getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable here, so the alias cannot be modelled: " + e);
        }
        byte[] newJar = jarBytes("2.0.0");
        artifactBytes = newJar;

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(storedName)
                .as("the stored name IS the downloaded file; deleting it as an 'older jar' loses the "
                        + "update while the command reports success")
                .exists();
        assertThat(Files.readAllBytes(storedName.toPath())).isEqualTo(newJar);
        assertThat(remainingJarEntries())
                .as("Codex P2 on #508: the link is a second directory entry for the same jar; left in "
                        + "place, the module's jar is enumerated twice at the next start")
                .containsExactly(storedName.getName());
    }

    @Test
    @DisplayName("Codex P2 on #508: a hard-linked second name for the downloaded jar is unlinked, keeping exactly one entry with the new bytes")
    void hardLinkedAliasOfTheDownloadedFile_leavesExactlyOneEntry() throws IOException {
        File oldJar = writeOldJar("1.0.0");
        File otherName = new File(pluginsFolder, "Fixture-Module-2.0.0.jar");
        Path downloadName = new File(pluginsFolder, IDENTIFY_STRING + "-2.0.0.jar").toPath();
        assertThat(otherName.createNewFile()).isTrue();
        try {
            Files.createLink(downloadName, otherName.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "hard links are unavailable here, so the alias cannot be modelled: " + e);
        }
        byte[] newJar = jarBytes("2.0.0");
        artifactBytes = newJar;

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(remainingJarEntries())
                .as("both hard-linked names are the downloaded jar; keeping both makes the plugin loader "
                        + "enumerate the module twice")
                .containsExactly(downloadName.getFileName().toString());
        assertThat(Files.readAllBytes(downloadName)).isEqualTo(newJar);
    }

    @Test
    @DisplayName("Codex P2 on #508: a jar another updater already removed counts as deleted, not as a failure")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the vanish-between-listing-and-delete race cannot be scheduled deterministically, so the private delete step is driven directly
    void jarAlreadyRemovedByAnotherUpdater_isNotReportedAsAFailure() throws Exception {
        File present = writeOldJar("1.0.0");
        File alreadyRemoved = new File(pluginsFolder, IDENTIFY_STRING + "-1.5.0.jar");
        java.lang.reflect.Method deleteAll =
                PluginInstallUtils.class.getDeclaredMethod("deleteAllOrThrow", java.util.List.class);
        deleteAll.setAccessible(true);

        // Two overlapping @RunAsync /upm update runs can list the same old jar; when the first
        // deletes it, the second's delete finds nothing. The file is gone, which is exactly the
        // outcome asked for, so reporting it as a failure names a jar that is no longer on disk.
        Throwable thrown = catchThrowable(
                () -> deleteAll.invoke(null, java.util.Arrays.asList(alreadyRemoved, present)));

        assertThat(thrown)
                .as("an already-removed jar must not turn a completed update into a reported failure")
                .isNull();
        assertThat(present).doesNotExist();
    }

    private java.util.List<String> remainingJarEntries() {
        String[] names = pluginsFolder.list((dir, name) -> name.endsWith(".jar"));
        return names == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(names);
    }

    private static byte[] jarBytes(String version) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: " + version + "\nidentify-string: " + IDENTIFY_STRING + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private File writeOldJar() throws IOException {
        return writeOldJar("1.0.0");
    }

    private File writeOldJar(String version) throws IOException {
        File jar = new File(pluginsFolder, IDENTIFY_STRING + "-" + version + ".jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: " + version + "\nidentify-string: " + IDENTIFY_STRING + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
