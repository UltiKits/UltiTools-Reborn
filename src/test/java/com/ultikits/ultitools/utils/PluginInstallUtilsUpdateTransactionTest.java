package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.utils.PluginInstallUtils.UpdateOutcome;
import com.ultikits.ultitools.utils.PluginInstallUtils.UpdateOutcome.Status;

/**
 * The staged update transaction behind {@link PluginInstallUtils#updatePluginTransactionally}
 * (#505, review r3), driven through its package-private {@code UpdateFileOperations} seam.
 * <p>
 * The seam records the order of the steps and injects move and delete failures, so every rollback
 * path is exercised on any platform without directory-permission tricks. The catalogue is a local
 * {@link HttpServer}; the download itself goes through the seam.
 */
@DisplayName("PluginInstallUtils staged update transaction (#505, review r3)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsUpdateTransactionTest {

    private static final String IDENTIFY_STRING = "fixture-module";
    private static final String NEW_JAR_NAME = IDENTIFY_STRING + "-2.0.0.jar";

    @TempDir
    File dataFolder;

    private HttpServer server;
    private File pluginsFolder;
    private File stagingFolder;
    private byte[] newJarBytes;
    private RecordingOperations operations;

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginsFolder = new File(dataFolder, "plugins");
        stagingFolder = new File(dataFolder, PluginInstallUtils.STAGING_DIRECTORY_NAME);
        assertThat(pluginsFolder.mkdirs()).isTrue();
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getDataFolder()).thenReturn(dataFolder));
        newJarBytes = jarBytes("2.0.0");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/plugin/get", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":{\"id\":7,\"identifyString\":\"" + IDENTIFY_STRING + "\"}}"));
        server.createContext("/plugin/7/latest", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"2.0.0\"}"));
        server.createContext("/plugin/7/2.0.0/download", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"" + origin + "/artifact.jar\"}"));
        server.start();
        PluginInstallUtils.setBaseUrlForTesting(origin);

        operations = new RecordingOperations();
        PluginInstallUtils.updateFileOperations = operations;
    }

    @AfterEach
    void tearDown() {
        PluginInstallUtils.updateFileOperations = PluginInstallUtils.UpdateFileOperations.DEFAULT;
        PluginInstallUtils.resetBaseUrl();
        server.stop(0);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("review r3 WR-01 by construction: old jars are selected before the new version exists anywhere in the modules folder")
    void oldJarSelection_completesBeforeTheNewVersionEntersTheModulesFolder() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        int download = operations.indexOf("download");
        int select = operations.indexOf("find");
        int moveIn = operations.indexOf("move-in");
        assertThat(download).as("the download step ran").isNotNegative();
        assertThat(operations.downloadDirectory.get())
                .as("the download is written to the staging directory, never into the modules folder")
                .isEqualTo(stagingFolder);
        assertThat(select).as("old jars are selected after the download").isGreaterThan(download);
        assertThat(moveIn).as("the new version enters the modules folder only after the selection").isGreaterThan(select);
        assertThat(operations.moduleFolderEntriesAtSelection)
                .as("when the old jars are selected, the modules folder holds nothing of the new version, "
                        + "so no name or file-identity comparison can mistake it for an old jar")
                .containsExactly(IDENTIFY_STRING + "-1.0.0.jar");
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
        assertThat(stagingEntries()).as("nothing is left in staging").isEmpty();
    }

    @Test
    @DisplayName("an older jar that cannot be moved aside rolls back every jar already moved, and nothing changes")
    void secondOldJarCannotBeMovedAside_firstIsMovedBackAndNothingChanges() throws IOException {
        File first = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        File second = writeJar(IDENTIFY_STRING + "-1.5.0.jar", "1.5.0");
        byte[] firstBytes = Files.readAllBytes(first.toPath());
        byte[] secondBytes = Files.readAllBytes(second.toPath());
        // Whichever old jar the listing yields second fails to move aside.
        operations.failMoveAsideAtCall = 2;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.OLD_JAR_NOT_MOVED);
        assertThat(outcome.getFiles()).hasSize(1);
        assertThat(outcome.getFiles().get(0)).isIn(first.getAbsolutePath(), second.getAbsolutePath());
        assertThat(outcome.getUnrestoredFiles()).isEmpty();
        assertThat(first).as("the jar moved aside before the failure is moved back").hasBinaryContent(firstBytes);
        assertThat(second).hasBinaryContent(secondBytes);
        assertThat(jarEntries()).containsExactlyInAnyOrder(first.getName(), second.getName());
        assertThat(stagingEntries()).as("the staged download is deleted").isEmpty();
    }

    @Test
    @DisplayName("a new version that cannot be moved in restores the older jars, and nothing changes")
    void newVersionCannotBeMovedIn_oldJarsAreRestored() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        byte[] oldBytes = Files.readAllBytes(oldJar.toPath());
        operations.failMoveIn = true;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.NEW_JAR_NOT_INSTALLED);
        assertThat(outcome.getFiles()).containsExactly(new File(pluginsFolder, NEW_JAR_NAME).getAbsolutePath());
        assertThat(outcome.getUnrestoredFiles()).isEmpty();
        assertThat(oldJar).hasBinaryContent(oldBytes);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).isEmpty();
    }

    @Test
    @DisplayName("an older jar that cannot be moved back after a failure is reported by path, never silently lost")
    void oldJarCannotBeMovedBack_isReportedAsUnrestored() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.failMoveIn = true;
        operations.failMoveBack = true;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.NEW_JAR_NOT_INSTALLED);
        assertThat(outcome.getUnrestoredFiles()).hasSize(1);
        Path aside = new File(outcome.getUnrestoredFiles().get(0)).toPath();
        assertThat(aside.getParent().toFile()).isEqualTo(stagingFolder);
        assertThat(aside).as("the unrestored jar still exists where the report says it is").exists();
    }

    @Test
    @DisplayName("a set-aside jar that cannot be deleted after a successful update is a leftover outside the modules folder, not a failure")
    void setAsideJarCannotBeDeleted_isReportedAsLeftoverOfASuccessfulUpdate() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.failDeleteOfSetAside = true;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(outcome.getLeftoverFiles()).hasSize(1);
        assertThat(new File(outcome.getLeftoverFiles().get(0)).getParentFile()).isEqualTo(stagingFolder);
        assertThat(oldJar).as("the old jar is out of the modules folder, so it never loads").doesNotExist();
        assertThat(new File(pluginsFolder, NEW_JAR_NAME)).hasBinaryContent(newJarBytes);
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("an update of a module whose update is running is refused before it downloads anything")
    void updateStartedDuringAnUpdateOfTheSameModule_isRefused() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        AtomicReference<UpdateOutcome> nested = new AtomicReference<>();
        operations.duringDownload = () -> nested.set(PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING));

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(nested.get().getStatus()).isEqualTo(Status.ALREADY_IN_PROGRESS);
        assertThat(operations.count("download"))
                .as("the refused update must not start a second download")
                .isEqualTo(1);
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING).getStatus())
                .as("the guard is released once the running update finishes")
                .isEqualTo(Status.UPDATED);
    }

    @Test
    @DisplayName("a download of the module at a version other than the catalogue's latest changes nothing")
    void downloadAtUnexpectedVersion_isAnInvalidDownload() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.downloadBytes = jarBytes("1.9.0");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.INVALID_DOWNLOAD);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(operations.count("find")).as("nothing is selected or moved after a failed validation").isZero();
        assertThat(stagingEntries()).isEmpty();
    }

    /** Wraps the real operations, recording each step and injecting the configured failures. */
    private final class RecordingOperations implements PluginInstallUtils.UpdateFileOperations {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<File> downloadDirectory = new AtomicReference<>();
        private volatile List<String> moduleFolderEntriesAtSelection = Collections.emptyList();
        private volatile byte[] downloadBytes;
        private volatile Runnable duringDownload;
        private volatile int failMoveAsideAtCall;
        private volatile boolean failMoveIn;
        private volatile boolean failMoveBack;
        private volatile boolean failDeleteOfSetAside;
        private int moveAsideCalls;

        @Override
        public void download(String url, String fileName, File directory) throws IOException {
            events.add("download");
            downloadDirectory.set(directory);
            Runnable hook = duringDownload;
            if (hook != null) {
                duringDownload = null;
                hook.run();
            }
            byte[] bytes = downloadBytes != null ? downloadBytes : newJarBytes;
            try (FileOutputStream out = new FileOutputStream(new File(directory, fileName))) {
                out.write(bytes);
            }
        }

        @Override
        public List<File> findModuleJars(File folder, String identifyString) {
            events.add("find");
            String[] names = folder.list();
            moduleFolderEntriesAtSelection = names == null ? Collections.emptyList() : new ArrayList<>(Arrays.asList(names));
            return DEFAULT.findModuleJars(folder, identifyString);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            boolean moveIn = target.getParent().toFile().equals(pluginsFolder) && source.getFileName().toString().endsWith(".part");
            boolean moveBack = target.getParent().toFile().equals(pluginsFolder) && !moveIn;
            if (moveIn) {
                events.add("move-in");
                if (failMoveIn) {
                    throw new IOException("injected: cannot move the new version in");
                }
            } else if (moveBack) {
                events.add("move-back");
                if (failMoveBack) {
                    throw new IOException("injected: cannot move the old jar back");
                }
            } else {
                events.add("move-aside");
                moveAsideCalls++;
                if (moveAsideCalls == failMoveAsideAtCall) {
                    throw new IOException("injected: cannot move the old jar aside");
                }
            }
            DEFAULT.move(source, target);
        }

        @Override
        public void delete(Path path) throws IOException {
            events.add("delete");
            if (failDeleteOfSetAside && path.getFileName().toString().endsWith(".old")) {
                throw new IOException("injected: cannot delete the set-aside jar");
            }
            DEFAULT.delete(path);
        }

        int indexOf(String event) {
            return events.indexOf(event);
        }

        long count(String event) {
            return events.stream().filter(event::equals).count();
        }
    }

    private List<String> jarEntries() {
        String[] names = pluginsFolder.list((dir, name) -> name.endsWith(".jar"));
        return names == null ? Collections.emptyList() : Arrays.asList(names);
    }

    private List<String> stagingEntries() {
        String[] names = stagingFolder.list();
        return names == null ? Collections.emptyList() : Arrays.asList(names);
    }

    private File writeJar(String fileName, String version) throws IOException {
        File jar = new File(pluginsFolder, fileName);
        try (FileOutputStream out = new FileOutputStream(jar)) {
            out.write(jarBytes(version));
        }
        return jar;
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
