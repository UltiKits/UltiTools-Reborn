package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * #505: {@link PluginInstallUtils#updatePlugin(String)} must never report success for an update
 * it did not complete, and must never destroy the module's last working jar.
 * <p>
 * The catalogue is a local {@link HttpServer} and the download is real. Every test asserts
 * outcomes on disk -- which jars survive, with which bytes -- and the method's reported result,
 * never how the method got there, so the same tests hold for any implementation that keeps the
 * outcomes.
 */
@DisplayName("PluginInstallUtils#updatePlugin outcomes on disk (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsUpdateOldJarTest {

    /** The class path entry of a compiled fixture module, so a fixture JAR is one a module could load from. */
    private static final String MODULE_CLASS_ENTRY = "com/ultikits/testfixtures/pluginloadafter/JarModuleTarget.class";

    private static final String IDENTIFY_STRING = "fixture-module";
    private static final String NEW_JAR_NAME = IDENTIFY_STRING + "-2.0.0.jar";

    @TempDir
    File dataFolder;

    private HttpServer server;
    private byte[] newJarBytes;
    /** What the local catalogue serves as the artifact: the module's 2.0.0 jar unless a test changes it. */
    private volatile byte[] artifactBytes;
    /** How many times the artifact was requested, i.e. how many downloads actually started. */
    private final AtomicInteger artifactRequests = new AtomicInteger();
    /** When set, the first artifact request waits on this latch before answering. */
    private volatile CountDownLatch holdFirstArtifact;
    private final CountDownLatch firstArtifactArrived = new CountDownLatch(1);
    /** When true, every artifact request after the first answers with a truncated body. */
    private volatile boolean truncateLaterArtifacts;
    private File pluginsFolder;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginsFolder = new File(dataFolder, "plugins");
        assertThat(pluginsFolder.mkdirs()).isTrue();
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getDataFolder()).thenReturn(dataFolder));
        newJarBytes = jarBytes("2.0.0");
        artifactBytes = newJarBytes;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/plugin/get", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":{\"id\":7,\"identifyString\":\"" + IDENTIFY_STRING + "\"}}"));
        server.createContext("/plugin/7/latest", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"2.0.0\"}"));
        server.createContext("/plugin/7/2.0.0/download", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"" + origin + "/artifact.jar\"}"));
        server.createContext("/artifact.jar", exchange -> {
            int request = artifactRequests.incrementAndGet();
            byte[] body = artifactBytes;
            if (request == 1) {
                firstArtifactArrived.countDown();
                CountDownLatch hold = holdFirstArtifact;
                if (hold != null) {
                    try {
                        hold.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if (truncateLaterArtifacts) {
                // Announce the full length, send half, and drop the connection: a transfer that
                // fails part-way, as a read timeout or a reset would.
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body, 0, body.length / 2);
                exchange.getResponseBody().flush();
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PluginInstallUtils.setBaseUrlForTesting(origin);
    }

    @AfterEach
    void tearDown() {
        CountDownLatch hold = holdFirstArtifact;
        if (hold != null) {
            hold.countDown();
        }
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("control: a writable folder updates, removes the old jar and reports success")
    void writableFolder_deletesOldJarAndReportsSuccess() throws IOException {
        File oldJar = writeOldJar();

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(new File(pluginsFolder, NEW_JAR_NAME)).hasBinaryContent(newJarBytes);
        assertThat(remainingJarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("an old jar that cannot be removed is reported as a failure naming that jar, and nothing changes")
    void undeletableOldJar_isReportedAsFailureNamingTheJar() throws IOException {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the removal fail");
        File oldJar = writeOldJar();

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the removal cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

            assertThat(thrown)
                    .as("an old jar that cannot be removed must not be reported as a successful update (#505)")
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(FileSystemException.class);
            assertThat(((FileSystemException) thrown.getCause()).getFile()).isEqualTo(oldJar.getAbsolutePath());
            assertThat(oldJar).exists();
            assertThat(remainingJarEntries())
                    .as("a failed update leaves the modules folder exactly as it was")
                    .containsExactly(oldJar.getName());
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    @Test
    @DisplayName("review CR-02: every older jar of the module is removed, not only the first one found")
    void twoOldJars_bothAreDeleted() throws IOException {
        File first = writeOldJar("1.0.0");
        File second = writeOldJar("1.5.0");

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(first).doesNotExist();
        assertThat(second)
                .as("any older jar left next to the new one loads a second version on restart")
                .doesNotExist();
        assertThat(new File(pluginsFolder, NEW_JAR_NAME)).hasBinaryContent(newJarBytes);
    }

    @Test
    @DisplayName("review CR-02 (order-dependent regression guard): a retry with the new jar already on disk still removes the old jar")
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
        assertThat(new File(pluginsFolder, NEW_JAR_NAME)).hasBinaryContent(newJarBytes);
        assertThat(remainingJarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("review CR-02: when older jars cannot be removed, the failure names a jar still on disk and nothing changes")
    void twoUndeletableOldJars_failureNamesARemainingJarAndNothingChanges() throws IOException {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the removal fail");
        File first = writeOldJar("1.0.0");
        File second = writeOldJar("1.5.0");

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the removal cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

            assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(FileSystemException.class);
            FileSystemException failure = (FileSystemException) thrown.getCause();
            java.util.List<String> named = new java.util.ArrayList<>();
            named.add(failure.getFile());
            for (Throwable suppressed : failure.getSuppressed()) {
                named.add(((FileSystemException) suppressed).getFile());
            }
            assertThat(named)
                    .as("every jar the failure names is one of the old jars, still on disk")
                    .isNotEmpty()
                    .isSubsetOf(first.getAbsolutePath(), second.getAbsolutePath());
            assertThat(remainingJarEntries()).containsExactlyInAnyOrder(first.getName(), second.getName());
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    @Test
    @DisplayName("a symbolic link to a jar of the module, plus that jar, end as exactly one entry holding the new bytes")
    void symbolicLinkToAModuleJar_leavesExactlyOneEntryWithTheNewBytes() throws IOException {
        // Two directory entries naming one jar of the module: a regular jar under another spelling
        // and the download name as a symbolic link to it. Whatever the entries are, the update must
        // end with exactly one entry for the module, holding the downloaded bytes.
        File oldJar = writeOldJar("1.0.0");
        File storedName = writeJar("Fixture-Module-2.0.0.jar", "1.9.0");
        Path downloadName = new File(pluginsFolder, NEW_JAR_NAME).toPath();
        try {
            Files.createSymbolicLink(downloadName, storedName.toPath().getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable here, so the alias cannot be modelled: " + e);
        }

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(remainingJarEntries())
                .as("Codex P2 on #508: a second entry for the module's jar is enumerated twice at the next start")
                .hasSize(1);
        File survivor = new File(pluginsFolder, remainingJarEntries().get(0));
        assertThat(Files.readAllBytes(survivor.toPath()))
                .as("the one remaining entry holds the downloaded version, not a lost or empty file")
                .isEqualTo(newJarBytes);
    }

    @Test
    @DisplayName("Codex P2 on #508: a hard-linked second name for a jar of the module leaves exactly one entry with the new bytes")
    void hardLinkedAliasOfTheDownloadedFile_leavesExactlyOneEntry() throws IOException {
        File oldJar = writeOldJar("1.0.0");
        File otherName = writeJar("Fixture-Module-2.0.0.jar", "1.9.0");
        Path downloadName = new File(pluginsFolder, NEW_JAR_NAME).toPath();
        try {
            Files.createLink(downloadName, otherName.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "hard links are unavailable here, so the alias cannot be modelled: " + e);
        }

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();

        assertThat(oldJar).doesNotExist();
        assertThat(remainingJarEntries())
                .as("keeping both hard-linked names makes the plugin loader enumerate the module twice")
                .hasSize(1);
        File survivor = new File(pluginsFolder, remainingJarEntries().get(0));
        assertThat(Files.readAllBytes(survivor.toPath())).isEqualTo(newJarBytes);
    }

    @Test
    @DisplayName("review r3 WR-02: a download that is not a jar of the module changes nothing and is not reported as success")
    void invalidDownload_keepsTheOldJarAndReportsFailure() throws IOException {
        File oldJar = writeOldJar("1.0.0");
        byte[] oldBytes = Files.readAllBytes(oldJar.toPath());
        // A proxy, CDN or captive portal answering the artifact URL with a 200 HTML page.
        artifactBytes = "<html><body>Access blocked by proxy</body></html>".getBytes(StandardCharsets.UTF_8);

        boolean result = PluginInstallUtils.updatePlugin(IDENTIFY_STRING);

        assertThat(oldJar)
                .as("the module's last working jar must survive a download that is not a jar of it")
                .exists()
                .hasBinaryContent(oldBytes);
        assertThat(result).as("an unloadable download must never be reported as a successful update").isFalse();
        assertThat(remainingJarEntries())
                .as("an invalid download changes nothing in the modules folder")
                .containsExactly(oldJar.getName());
    }

    @Test
    @DisplayName("review r3 WR-02: a download that is a jar of a different module changes nothing")
    void downloadOfAnotherModule_keepsTheOldJarAndReportsFailure() throws IOException {
        File oldJar = writeOldJar("1.0.0");
        artifactBytes = jarBytes("other-module", "2.0.0");

        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isFalse();

        assertThat(oldJar).exists();
        assertThat(remainingJarEntries()).containsExactly(oldJar.getName());
    }

    @Test
    @DisplayName("review r3 WR-03: an update of a module whose update is already running is refused and starts no second transfer")
    void overlappingUpdateOfTheSameModule_isRefusedAndNoJarIsTruncated() throws Exception {
        File oldJar = writeOldJar("1.0.0");
        holdFirstArtifact = new CountDownLatch(1);
        truncateLaterArtifacts = true;
        ExecutorService first = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> firstUpdate = first.submit(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));
            assertThat(firstArtifactArrived.await(10, TimeUnit.SECONDS))
                    .as("control: the first update must be mid-transfer before the second starts")
                    .isTrue();

            boolean secondResult;
            try {
                secondResult = PluginInstallUtils.updatePlugin(IDENTIFY_STRING);
            } finally {
                holdFirstArtifact.countDown();
            }
            boolean firstResult = firstUpdate.get(20, TimeUnit.SECONDS);

            assertThat(secondResult).as("the overlapping update must not report success").isFalse();
            assertThat(artifactRequests.get())
                    .as("the overlapping update must be refused before it starts a second transfer "
                            + "that can truncate the module's jar")
                    .isEqualTo(1);
            assertThat(firstResult).isTrue();
            assertThat(oldJar).doesNotExist();
            assertThat(new File(pluginsFolder, NEW_JAR_NAME))
                    .as("the jar left in place is complete, never a truncated transfer")
                    .hasBinaryContent(newJarBytes);
        } finally {
            first.shutdownNow();
        }
    }

    @Test
    @DisplayName("review r3 WR-03: a later update whose transfer fails part-way leaves the installed jar intact")
    void laterUpdateWithFailedTransfer_leavesTheInstalledJarIntact() throws IOException {
        writeOldJar("1.0.0");
        assertThat(PluginInstallUtils.updatePlugin(IDENTIFY_STRING)).isTrue();
        File installed = new File(pluginsFolder, NEW_JAR_NAME);
        assertThat(installed).hasBinaryContent(newJarBytes);

        truncateLaterArtifacts = true;
        boolean retried = PluginInstallUtils.updatePlugin(IDENTIFY_STRING);

        assertThat(retried).as("a transfer that fails part-way is not a successful update").isFalse();
        assertThat(installed)
                .as("a failed transfer must never truncate the jar already in place")
                .hasBinaryContent(newJarBytes);
        assertThat(remainingJarEntries()).containsExactly(NEW_JAR_NAME);
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

        // Two overlapping runs can list the same jar; when the first removes it, the second's
        // delete finds nothing. The file is gone, which is exactly the outcome asked for, so
        // reporting it as a failure names a jar that is no longer on disk.
        Throwable thrown = catchThrowable(
                () -> deleteAll.invoke(null, java.util.Arrays.asList(alreadyRemoved, present)));

        assertThat(thrown)
                .as("an already-removed jar must not turn a completed operation into a reported failure")
                .isNull();
        assertThat(present).doesNotExist();
    }

    private java.util.List<String> remainingJarEntries() {
        String[] names = pluginsFolder.list((dir, name) -> name.endsWith(".jar"));
        return names == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(names);
    }

    private static byte[] jarBytes(String version) throws IOException {
        return jarBytes(IDENTIFY_STRING, version);
    }

    private static byte[] jarBytes(String identifyString, String version) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: " + version + "\nidentify-string: " + identifyString + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            writeModuleClassEntry(out);
        }
        return bytes.toByteArray();
    }

    private File writeOldJar() throws IOException {
        return writeOldJar("1.0.0");
    }

    private File writeOldJar(String version) throws IOException {
        return writeJar(IDENTIFY_STRING + "-" + version + ".jar", version);
    }

    private File writeJar(String fileName, String version) throws IOException {
        File jar = new File(pluginsFolder, fileName);
        try (FileOutputStream out = new FileOutputStream(jar)) {
            out.write(jarBytes(version));
        }
        return jar;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Writes a compiled class that extends {@code UltiToolsPlugin} into a fixture JAR. */
    private static void writeModuleClassEntry(JarOutputStream out) throws IOException {
        try (InputStream in = PluginInstallUtilsUpdateOldJarTest.class.getClassLoader()
                .getResourceAsStream(MODULE_CLASS_ENTRY)) {
            assertThat(in).as("the compiled fixture module class must be on the test class path").isNotNull();
            out.putNextEntry(new JarEntry(MODULE_CLASS_ENTRY));
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            out.closeEntry();
        }
    }

}
