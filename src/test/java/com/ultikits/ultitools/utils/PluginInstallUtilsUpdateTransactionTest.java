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
    /** The catalogue's latest version; tests may change it before updating. */
    private volatile String catalogueVersion = "2.0.0";

    @BeforeEach
    void setUp() throws IOException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginsFolder = new File(dataFolder, "plugins");
        stagingFolder = new File(dataFolder, PluginInstallUtils.STAGING_DIRECTORY_NAME);
        assertThat(pluginsFolder.mkdirs()).isTrue();
        com.ultikits.ultitools.manager.PluginManager pluginManager =
                org.mockito.Mockito.mock(com.ultikits.ultitools.manager.PluginManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
        newJarBytes = jarBytes("2.0.0");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/plugin/get", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":{\"id\":7,\"identifyString\":\"" + IDENTIFY_STRING + "\"}}"));
        server.createContext("/plugin/7/latest", exchange -> respond(exchange,
                "{\"code\":\"200\",\"data\":\"" + catalogueVersion + "\"}"));
        // Every /plugin/7/<version>/download request answers with the artifact link.
        server.createContext("/plugin/7/", exchange -> respond(exchange,
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
        assertThat(outcome.getFailureReason()).isEqualTo("java.io.IOException: injected: cannot move the old jar aside");
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
        assertThat(outcome.getFailureReason())
                .as("review r4 WR-04: the failed move's cause is carried, not dropped")
                .isEqualTo("java.io.IOException: injected: cannot move the new version in");
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
        assertThat(outcome.getUnrestoredTargets())
                .as("review r4 WR-02: the original path, with its original file name, must be reported; "
                        + "moving the .old file back under its staging name leaves it unloadable")
                .containsExactly(new File(pluginsFolder, IDENTIFY_STRING + "-1.0.0.jar").getAbsolutePath());
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

    @Test
    @DisplayName("review r4 WR-04: updatePlugin keeps a failed move's exception as the cause of the thrown FileSystemException")
    void updatePlugin_keepsTheMoveFailureAsCause() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.failMoveIn = true;

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

        assertThat(thrown).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(thrown.getCause()).isInstanceOf(java.nio.file.FileSystemException.class);
        assertThat(thrown.getCause().getCause())
                .as("the operator's diagnosis needs the original exception, not only the status name")
                .isInstanceOf(IOException.class)
                .hasMessage("injected: cannot move the new version in");
    }

    @Test
    @DisplayName("review r4: updatePlugin never answers false once a set-aside jar could not be moved back, whatever refused the move")
    void updatePlugin_throwsWhenANonAtomicRefusalLeavesAnUnrestoredJar() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        writeJar(IDENTIFY_STRING + "-1.5.0.jar", "1.5.0");
        operations.moveAsideNotAtomicAtCall = 2;
        operations.failMoveBack = true;

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> PluginInstallUtils.updatePlugin(IDENTIFY_STRING));

        assertThat(thrown)
                .as("false means nothing was changed; a jar left in staging is a change the caller must see")
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    @DisplayName("review r4 WR-01: staging and modules folders on different file systems are refused before anything is downloaded or moved")
    void differentFileStores_areRefusedBeforeAnyChange() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.sameFileStore = false;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.FILE_SYSTEMS_DIFFER);
        assertThat(outcome.getFiles()).containsExactly(stagingFolder.getAbsolutePath(), pluginsFolder.getAbsolutePath());
        assertThat(operations.count("download")).isZero();
        assertThat(jarEntries()).containsExactly(oldJar.getName());
    }

    @Test
    @DisplayName("review r4 WR-01: a move that cannot be atomic is refused with nothing changed, never copied")
    void atomicMoveNotSupported_isRefusedAndNothingChanges() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        byte[] oldBytes = Files.readAllBytes(oldJar.toPath());
        operations.moveAsideNotAtomic = true;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.FILE_SYSTEMS_DIFFER);
        assertThat(oldJar).hasBinaryContent(oldBytes);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).isEmpty();
    }

    @Test
    @DisplayName("review r4 IN-03: a staging directory that cannot be prepared is reported as such, not as a failed download")
    void stagingCannotBePrepared_isStagingUnavailable() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        assertThat(stagingFolder.createNewFile()).as("staging path occupied by a regular file").isTrue();

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.STAGING_UNAVAILABLE);
        assertThat(outcome.getFiles()).containsExactly(stagingFolder.getAbsolutePath());
        assertThat(jarEntries()).containsExactly(oldJar.getName());
    }

    @Test
    @DisplayName("review r4 WR-05: a jar newer than the catalogue's latest version is never replaced by it")
    void newerJarInTheModulesFolder_isRefusedAndNothingChanges() throws IOException {
        File older = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        File newer = writeJar("UltiChat-2.1.0-beta.jar", "2.1.0");
        byte[] newerBytes = Files.readAllBytes(newer.toPath());

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.NEWER_VERSION_PRESENT);
        assertThat(outcome.getFiles()).containsExactly(newer.getAbsolutePath());
        assertThat(outcome.getFoundVersion()).isEqualTo("2.1.0");
        assertThat(outcome.getExpectedVersion()).isEqualTo("2.0.0");
        assertThat(newer).as("the operator's newer jar is kept").hasBinaryContent(newerBytes);
        assertThat(jarEntries()).containsExactlyInAnyOrder(older.getName(), newer.getName());
        assertThat(stagingEntries()).isEmpty();
    }

    @Test
    @DisplayName("review r4 IN-04: an unquoted plugin.yml version such as 2.10 is compared as written, not as the number 2.1")
    void unquotedVersionTwoPointTen_matchesCatalogueTwoPointTen() throws IOException {
        writeJar(IDENTIFY_STRING + "-2.9.0.jar", "2.9.0");
        catalogueVersion = "2.10";
        operations.downloadBytes = jarBytesWithRawYaml("name: Fixture\nversion: 2.10\nidentify-string: " + IDENTIFY_STRING + "\n");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(jarEntries()).containsExactly(IDENTIFY_STRING + "-2.10.jar");
    }

    @Test
    @DisplayName("review r4 WR-03: an uninstall of a module whose update is running is refused and removes nothing")
    void uninstallDuringAnUpdateOfTheSameModule_isRefused() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        AtomicReference<Throwable> uninstallResult = new AtomicReference<>();
        operations.duringDownload = () -> uninstallResult.set(org.assertj.core.api.Assertions.catchThrowable(
                () -> PluginInstallUtils.uninstallPlugin("Fixture")));

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(uninstallResult.get())
                .as("the uninstall resolves the same module through its jar's identify-string and meets the update's guard")
                .isInstanceOf(java.util.ConcurrentModificationException.class);
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("review r4 WR-07 (M1): an existing file at the new version's name is never replaced")
    void existingFileAtTheNewVersionName_isNotReplaced() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        File occupant = new File(pluginsFolder, NEW_JAR_NAME);
        byte[] occupantBytes = "README saved under a jar name".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(occupant)) {
            out.write(occupantBytes);
        }

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.NEW_JAR_NOT_INSTALLED);
        assertThat(occupant)
                .as("an atomic rename replaces an existing target on POSIX unless the move refuses it first")
                .hasBinaryContent(occupantBytes);
        assertThat(oldJar).exists();
    }

    @Test
    @DisplayName("review r4 WR-07 (M2): the per-module guard is keyed by the normalised identify string")
    void guardIsKeyedByTheNormalisedIdentifyString() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        AtomicReference<UpdateOutcome> nested = new AtomicReference<>();
        operations.duringDownload = () -> nested.set(PluginInstallUtils.updatePluginTransactionally("  FIXTURE-Module "));

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(nested.get().getStatus())
                .as("another spelling of the same module must meet the same guard")
                .isEqualTo(Status.ALREADY_IN_PROGRESS);
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
    }

    @Test
    @DisplayName("review r4 WR-07 (M3): the guard is released even when an update throws")
    void guardIsReleasedWhenAnUpdateThrows() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.downloadThrowsUnchecked = new IllegalStateException("injected: unexpected failure");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING).getStatus())
                .as("a module whose update threw must not stay locked until restart")
                .isEqualTo(Status.UPDATED);
    }

    @Test
    @DisplayName("review r4 WR-07 (M4): an old jar that disappears after selection does not fail the update")
    void oldJarGoneAfterSelection_doesNotFailTheUpdate() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.extraFoundJar = new File(pluginsFolder, IDENTIFY_STRING + "-1.5.0.jar");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("review r4 WR-07 (M5): a download beyond the loader's entry limit is invalid, and the old jar survives")
    void downloadBeyondTheEntryLimit_isAnInvalidDownload() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: 2.0.0\nidentify-string: " + IDENTIFY_STRING + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int i = 0; i < 10_001; i++) {
                out.putNextEntry(new JarEntry("filler/" + i));
                out.closeEntry();
            }
        }
        operations.downloadBytes = bytes.toByteArray();

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus())
                .as("the loader skips a jar over 10,000 entries at boot, so installing it would lose the module")
                .isEqualTo(Status.INVALID_DOWNLOAD);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
    }

    @Test
    @DisplayName("review r4 WR-07 (M12): a download that fails after writing bytes leaves nothing in staging")
    void failedDownloadAfterWriting_leavesNothingInStaging() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.downloadThrowsAfterWriting = new IOException("injected: connection reset mid-body");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.DOWNLOAD_FAILED);
        assertThat(stagingEntries()).as("the partial download is deleted").isEmpty();
        assertThat(jarEntries()).containsExactly(oldJar.getName());
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
        private volatile RuntimeException downloadThrowsUnchecked;
        private volatile IOException downloadThrowsAfterWriting;
        private volatile File extraFoundJar;
        private volatile boolean sameFileStore = true;
        private volatile boolean moveAsideNotAtomic;
        private volatile int moveAsideNotAtomicAtCall;
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
            RuntimeException unchecked = downloadThrowsUnchecked;
            if (unchecked != null) {
                downloadThrowsUnchecked = null;
                throw unchecked;
            }
            byte[] bytes = downloadBytes != null ? downloadBytes : newJarBytes;
            try (FileOutputStream out = new FileOutputStream(new File(directory, fileName))) {
                out.write(bytes);
            }
            IOException afterWriting = downloadThrowsAfterWriting;
            if (afterWriting != null) {
                downloadThrowsAfterWriting = null;
                throw afterWriting;
            }
        }

        @Override
        public List<File> findModuleJars(File folder, String identifyString) {
            events.add("find");
            String[] names = folder.list();
            moduleFolderEntriesAtSelection = names == null ? Collections.emptyList() : new ArrayList<>(Arrays.asList(names));
            List<File> found = new ArrayList<>(DEFAULT.findModuleJars(folder, identifyString));
            if (extraFoundJar != null) {
                found.add(extraFoundJar);
            }
            return found;
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
                if (moveAsideNotAtomic || moveAsideCalls + 1 == moveAsideNotAtomicAtCall) {
                    throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(),
                            "injected: different file stores");
                }
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

        @Override
        public boolean isSameFileStore(Path first, Path second) {
            events.add("same-store");
            return sameFileStore;
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

    private static byte[] jarBytesWithRawYaml(String pluginYml) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
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
