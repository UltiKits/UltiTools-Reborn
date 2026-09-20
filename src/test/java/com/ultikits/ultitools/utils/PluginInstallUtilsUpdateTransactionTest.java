package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /** The class path entry of a compiled fixture module, so a fixture JAR is one a module could load from. */
    private static final String MODULE_CLASS_ENTRY = "com/ultikits/testfixtures/pluginloadafter/JarModuleTarget.class";

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
    void tearDown() throws IOException {
        java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(
                stagingFolder.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
        if (view != null && stagingFolder.exists()) {
            // A test may have made the staging directory unwritable; @TempDir cleanup needs it back.
            view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
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
    @DisplayName("review r4 WR-01 / codex r6: a move that cannot be atomic is refused as such, with nothing changed")
    void atomicMoveNotSupported_isRefusedAndNothingChanges() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        byte[] oldBytes = Files.readAllBytes(oldJar.toPath());
        operations.moveAsideNotAtomic = true;

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus())
                .as("both folders passed the same-file-store check, so this is not a split file system")
                .isEqualTo(Status.ATOMIC_MOVE_UNSUPPORTED);
        assertThat(outcome.getFiles()).containsExactly(stagingFolder.getAbsolutePath(), pluginsFolder.getAbsolutePath());
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
                .isInstanceOf(com.ultikits.ultitools.exceptions.PluginModuleException.class);
        assertThat(((com.ultikits.ultitools.exceptions.PluginModuleException) uninstallResult.get()).getErrorCode().name())
                .as("the refusal is a typed framework failure, not a collection-iteration error")
                .isEqualTo("PLUGIN_OPERATION_IN_PROGRESS");
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("review r5 WR-01: a journal records the transaction while it runs and is gone once it finishes")
    void transactionJournal_existsWhileMovingAndIsDeletedAfterwards() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        List<String> journalWhileMoving = new ArrayList<>();
        operations.duringMoveIn = () -> {
            for (String name : stagingEntries()) {
                if (name.endsWith(".txn")) {
                    try {
                        // The name comes from this test's own temporary staging directory listing.
                        // nosemgrep: java.inject.rule-SpotbugsPathTraversalAbsolute
                        File journal = new File(stagingFolder, name);
                        journalWhileMoving.add(name + "\n"
                                + new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
        };

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(journalWhileMoving)
                .as("without a journal, boot recovery cannot tell this transaction's set-aside jars from leftovers")
                .hasSize(1);
        String journal = journalWhileMoving.get(0);
        assertThat(journal)
                .contains("module=" + IDENTIFY_STRING)
                .contains("target=" + NEW_JAR_NAME)
                .contains("process=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName())
                .contains("aside.0.original=" + IDENTIFY_STRING + "-1.0.0.jar")
                .contains("aside.0.aside=" + IDENTIFY_STRING + "-1.0.0.jar.");
        assertThat(stagingEntries()).as("a finished transaction leaves no journal behind").isEmpty();
    }

    @Test
    @DisplayName("review r6 IN-05: an uninstall during an update that had no old JAR to move aside is still refused")
    void uninstallDuringAnUpdateWithNoOlderJar_isRefused() throws IOException {
        AtomicReference<Throwable> uninstallResult = new AtomicReference<>();
        operations.duringMoveIn = () -> uninstallResult.set(org.assertj.core.api.Assertions.catchThrowable(
                () -> PluginInstallUtils.uninstallPlugin("Fixture")));

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(uninstallResult.get())
                .as("with no old JAR the journal records no pair, so it must name the module itself")
                .isInstanceOf(com.ultikits.ultitools.exceptions.PluginModuleException.class);
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
    }

    @Test
    @DisplayName("codex r7 P1: a download carrying no class the loader could load is invalid, before any JAR moves")
    void downloadWithoutAModuleClass_isAnInvalidDownload() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: 2.0.0\nidentify-string: " + IDENTIFY_STRING + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        operations.downloadBytes = bytes.toByteArray();

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus())
                .as("a JAR with a plugin.yml and nothing else produces no module, so installing it loses this one")
                .isEqualTo(Status.INVALID_DOWNLOAD);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).isEmpty();
    }

    @Test
    @DisplayName("codex r6 P1: a download missing the name the loader requires is invalid, before any JAR moves")
    void downloadWithoutTheNameKey_isAnInvalidDownload() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.downloadBytes = jarBytesWithRawYaml("version: 2.0.0\nidentify-string: " + IDENTIFY_STRING + "\n");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus())
                .as("UltiToolsPlugin refuses a module JAR with no name:, so installing one loses the module")
                .isEqualTo(Status.INVALID_DOWNLOAD);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).isEmpty();
    }

    @Test
    @DisplayName("codex r6 P2: a journal that cannot be marked committed is removed instead, never left uncommitted")
    void journalThatCannotBeMarkedCommitted_isDeleted() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        // Occupy the name the committed marker is written through, so that write is the only failure.
        operations.duringMoveIn = () -> {
            for (String name : stagingEntries()) {
                if (name.endsWith(".txn")) {
                    // nosemgrep: java.inject.rule-SpotbugsPathTraversalAbsolute
                    File blocker = new File(stagingFolder, name + ".tmp");
                    assertThat(blocker.mkdir()).as("a directory cannot be written as a file").isTrue();
                }
            }
        };

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(stagingEntries())
                .as("an uncommitted journal would make the next boot undo a later uninstall")
                .noneMatch(name -> name.endsWith(".txn"));
    }

    @Test
    @DisplayName("review r6 IN-04: a journal that cannot be written is reported as itself and leaves no temporary file")
    void journalThatCannotBeWritten_isReportedAsSuchAndCleansUp() throws IOException {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        org.junit.jupiter.api.Assumptions.assumeTrue(makeStagingReadOnlyAfterDownload(),
                "a POSIX file system whose permissions bind this process is required");

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.JOURNAL_NOT_WRITTEN);
        assertThat(outcome.getFiles()).hasSize(1);
        assertThat(outcome.getFiles().get(0)).endsWith(".txn");
        assertThat(outcome.getFailureReason()).isNotNull();
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).as("no half-written journal is left behind").noneMatch(n -> n.endsWith(".tmp"));
    }

    /**
     * Makes the staging directory unwritable once the download has been written into it, so the
     * journal write is the first operation that fails.
     *
     * @return whether the permissions actually bind this process
     */
    private boolean makeStagingReadOnlyAfterDownload() {
        operations.duringDownloadCompleted = () -> {
            try {
                java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(
                        stagingFolder.toPath(), java.nio.file.attribute.PosixFileAttributeView.class);
                if (view != null) {
                    view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                }
            } catch (IOException | UnsupportedOperationException e) {
                throw new java.io.UncheckedIOException(new IOException(e));
            }
        };
        return stagingFolder.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")
                && !"root".equals(System.getProperty("user.name"));
    }

    @Test
    @DisplayName("review r5 IN-03: an uninstall started while a module has no jar on disk, between the moves, is still refused")
    void uninstallBetweenTheMoves_isRefused() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        AtomicReference<Throwable> uninstallResult = new AtomicReference<>();
        operations.duringMoveIn = () -> uninstallResult.set(org.assertj.core.api.Assertions.catchThrowable(
                () -> PluginInstallUtils.uninstallPlugin("Fixture")));

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(uninstallResult.get())
                .as("in this window the module has no jar in the modules folder, so the running transaction's "
                        + "journal is the only thing that names it")
                .isInstanceOf(com.ultikits.ultitools.exceptions.PluginModuleException.class);
        assertThat(((com.ultikits.ultitools.exceptions.PluginModuleException) uninstallResult.get()).getErrorCode().name())
                .isEqualTo("PLUGIN_OPERATION_IN_PROGRESS");
        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(jarEntries()).containsExactly(NEW_JAR_NAME);
    }

    @Test
    @DisplayName("review r5 WR-02: an uninstall refused on one of its keys leaves none of them held")
    void uninstallRefusedOnOneKey_holdsNoOtherKeyAfterwards() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        // A second jar with the same runtime name but another identify-string: the uninstall must
        // take both keys, and the update of this module already holds one of them.
        File otherModule = new File(pluginsFolder, "aaa-module-1.0.0.jar");
        try (FileOutputStream out = new FileOutputStream(otherModule)) {
            out.write(jarBytesWithRawYaml("name: Fixture\nversion: 1.0.0\nidentify-string: aaa-module\n"));
        }
        AtomicReference<Throwable> uninstallResult = new AtomicReference<>();
        AtomicReference<UpdateOutcome> otherUpdate = new AtomicReference<>();
        operations.duringDownload = () -> {
            uninstallResult.set(org.assertj.core.api.Assertions.catchThrowable(
                    () -> PluginInstallUtils.uninstallPlugin("Fixture")));
            otherUpdate.set(PluginInstallUtils.updatePluginTransactionally("aaa-module"));
        };

        PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(uninstallResult.get()).isInstanceOf(com.ultikits.ultitools.exceptions.PluginModuleException.class);
        assertThat(otherUpdate.get().getStatus())
                .as("the refused uninstall must not leave the other module locked until restart")
                .isNotEqualTo(Status.ALREADY_IN_PROGRESS);
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
            writeModuleClassEntry(out);
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
        private volatile Runnable duringMoveIn;
        private volatile Runnable duringMoveAside;
        private volatile Runnable duringDownloadCompleted;
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
            Runnable completed = duringDownloadCompleted;
            if (completed != null) {
                duringDownloadCompleted = null;
                completed.run();
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
                Runnable hook = duringMoveIn;
                if (hook != null) {
                    duringMoveIn = null;
                    hook.run();
                }
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
                Runnable asideHook = duringMoveAside;
                if (asideHook != null) {
                    duringMoveAside = null;
                    asideHook.run();
                }
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

    @Test
    @DisplayName("review r6 WR-02: the journal is on disk, naming every planned move, before the first JAR moves")
    void transactionJournal_namesEveryPlannedMoveBeforeTheFirstMoveAside() throws IOException {
        writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        writeJar(IDENTIFY_STRING + "-1.5.0.jar", "1.5.0");
        List<String> journalAtFirstMove = new ArrayList<>();
        operations.duringMoveAside = () -> journalAtFirstMove.addAll(journalContents());

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus()).isEqualTo(Status.UPDATED);
        assertThat(journalAtFirstMove)
                .as("a kill during the first move must leave a journal that already names both JARs")
                .hasSize(1);
        assertThat(journalAtFirstMove.get(0))
                .contains(IDENTIFY_STRING + "-1.0.0.jar")
                .contains(IDENTIFY_STRING + "-1.5.0.jar")
                .contains("aside.0.aside")
                .contains("aside.1.aside");
    }

    /** The contents of every journal in the staging directory, read at the moment of the call. */
    private List<String> journalContents() {
        List<String> contents = new ArrayList<>();
        for (String name : stagingEntries()) {
            if (name.endsWith(".txn")) {
                try {
                    // nosemgrep: java.inject.rule-SpotbugsPathTraversalAbsolute
                    File journal = new File(stagingFolder, name);
                    contents.add(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        return contents;
    }

    @Test
    @DisplayName("codex r6 P2: a signed JAR whose plugin.yml was changed after signing is an invalid download")
    void tamperedSignedJar_isAnInvalidDownloadAndLeavesNothingBehind() throws Exception {
        File oldJar = writeJar(IDENTIFY_STRING + "-1.0.0.jar", "1.0.0");
        operations.downloadBytes = tamperedSignedJarBytes();

        UpdateOutcome outcome = PluginInstallUtils.updatePluginTransactionally(IDENTIFY_STRING);

        assertThat(outcome.getStatus())
                .as("reading a tampered signed JAR throws SecurityException, which is still an unusable download")
                .isEqualTo(Status.INVALID_DOWNLOAD);
        assertThat(jarEntries()).containsExactly(oldJar.getName());
        assertThat(stagingEntries()).as("the staged download is deleted like any other invalid one").isEmpty();
    }

    @Test
    @DisplayName("codex r6 P2: every plugin.yml read survives a JAR whose signature does not verify")
    void tamperedSignedJar_isReadAsNeitherAModuleJarNorAVersion() throws Exception {
        File tampered = new File(dataFolder, "tampered.jar");
        Files.write(tampered.toPath(), tamperedSignedJarBytes());

        assertThat(PluginInstallUtils.isJarOfModule(tampered, IDENTIFY_STRING, "2.0.0")).isFalse();
        assertThat(PluginInstallUtils.readModuleVersion(tampered)).isNull();
    }

    /**
     * A JAR signed with a throwaway key whose {@code plugin.yml} bytes were replaced afterwards, so
     * reading that entry throws {@code java.lang.SecurityException: SHA-... digest error}.
     */
    private byte[] tamperedSignedJarBytes() throws Exception {
        File tools = new File(System.getProperty("java.home"), "bin");
        File keytool = new File(tools, "keytool");
        File jarsigner = new File(tools, "jarsigner");
        org.junit.jupiter.api.Assumptions.assumeTrue(keytool.canExecute() && jarsigner.canExecute(),
                "a JDK with keytool and jarsigner is required to build a signed JAR");
        File work = new File(dataFolder, "signing");
        assertThat(work.mkdirs()).isTrue();
        File jar = new File(work, "signed.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(jarBytesPluginYml("2.0.0"));
            out.closeEntry();
            writeModuleClassEntry(out);
        }
        File keystore = new File(work, "keystore.jks");
        run(work, keytool.getAbsolutePath(), "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=ultitools-test", "-keystore", keystore.getAbsolutePath(),
                "-storepass", "changeit", "-keypass", "changeit", "-validity", "3650");
        run(work, jarsigner.getAbsolutePath(), "-keystore", keystore.getAbsolutePath(),
                "-storepass", "changeit", "-keypass", "changeit", jar.getAbsolutePath(), "t");
        java.io.ByteArrayOutputStream tampered = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipFile signed = new java.util.zip.ZipFile(jar);
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(tampered)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> it = signed.entries();
            while (it.hasMoreElements()) {
                java.util.zip.ZipEntry entry = it.nextElement();
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if ("plugin.yml".equals(entry.getName())) {
                    out.write(jarBytesPluginYml("9.9.9"));
                } else {
                    try (InputStream in = signed.getInputStream(entry)) {
                        byte[] buffer = new byte[4096];
                        for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                out.closeEntry();
            }
        }
        return tampered.toByteArray();
    }

    private static byte[] jarBytesPluginYml(String version) {
        return ("name: Fixture\nversion: " + version + "\nidentify-string: " + IDENTIFY_STRING + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void run(File directory, String... command) throws Exception {
        // The command is this JDK's own keytool or jarsigner plus literals and paths under @TempDir;
        // nothing here comes from outside the test.
        // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
        Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder(256);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                output.append(line).append('\n');
            }
        }
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("command timed out: %s", Arrays.toString(command)).isTrue();
        assertThat(process.exitValue()).as("command failed: %s%n%s", Arrays.toString(command), output).isZero();
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
            writeModuleClassEntry(out);
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
            writeModuleClassEntry(out);
        }
        return bytes.toByteArray();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Writes a compiled class that extends {@code UltiToolsPlugin} into a fixture JAR. */
    private static void writeModuleClassEntry(JarOutputStream out) throws IOException {
        try (InputStream in = PluginInstallUtilsUpdateTransactionTest.class.getClassLoader()
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
