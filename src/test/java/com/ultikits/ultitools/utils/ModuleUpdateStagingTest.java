package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.failingDownload;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Staging an update while the server runs (#505): what {@code /upm update} may change (only the
 * transaction folder), what it refuses, and how an uninstall cancels a staged update.
 */
@DisplayName("Module update staging: failures, one pending update per module, cancellation (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateStagingTest {

    @TempDir
    File serverRoot;

    /** {@code <server root>/plugins/UltiTools}, as on a real server; the records live under the server root. */
    private File dataFolder;

    private File modules;
    private File transactions;
    private File oldJar;
    private UltiToolsPlugin loadedOld;

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
        oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        loadedOld = loadedModule("Demo", "1.0", "demo");
    }

    private ModuleFileTransactions.StageResult stage(ModuleFileTransactions.Downloader downloader) {
        return new ModuleFileTransactions(dataFolder).stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"), downloader);
    }

    /**
     * Every file under the server root with its bytes, to prove "nothing changed": the modules
     * folder and the records folder, which lives under the server root, not the data folder.
     */
    private Map<String, String> snapshot() throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String path : treeOf(serverRoot)) {
            // The path comes from listing this test's own temporary folder; nothing is external input.
            // nosemgrep: java.inject.rule-SpotbugsPathTraversalAbsolute
            files.put(path, new String(Files.readAllBytes(new File(serverRoot, path).toPath()),
                    StandardCharsets.ISO_8859_1));
        }
        return files;
    }

    @Test
    @DisplayName("a failed download is reported with its cause and leaves no record and no file behind")
    void failedDownload_changesNothing() throws IOException {
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult result = stage(failingDownload("connection reset"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_DOWNLOAD_FAILED);
        assertThat(String.valueOf(result.getReasonArgs()[0])).contains("connection reset");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("a download that does not declare the module's identify-string is refused and leaves nothing behind")
    void downloadOfAnotherModule_changesNothing() throws IOException {
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult result = stage(downloading("Other", "1.1", "other"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_WRONG_IDENTITY);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("a module not loaded from the modules folder is refused before anything is downloaded")
    void moduleLoadedFromElsewhere_isRefused() throws IOException {
        File elsewhere = moduleJar(new File(dataFolder, "dev/demo.jar"), "Demo", "1.0", "demo");
        Map<String, String> before = snapshot();
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, elsewhere),
                catalogue("demo", "1.1"), (link, name, folder) -> downloads.add(name));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_NOT_IN_MODULES_FOLDER);
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("a JAR another loaded module also came from is refused before anything is downloaded (Codex P1, #561)")
    void jarSharedWithAnotherLoadedModule_isRefused() throws IOException {
        UltiToolsPlugin bystander = loadedModule("Companion", "1.0", "companion");
        Map<String, String> before = snapshot();
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                java.util.Arrays.asList(loadedOld, bystander),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar).with(bystander, oldJar),
                catalogue("demo", "1.1"), (link, name, folder) -> downloads.add(name));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_SHARED_JAR);
        assertThat(result.getReasonArgs()).containsExactly(oldJar.getAbsolutePath(), "Companion");
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("an old JAR that cannot be read (so its content cannot be recorded) is refused before anything is downloaded")
    void unreadableOldJar_isRefused() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
        java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(oldJar.toPath());
        Files.setPosixFilePermissions(oldJar.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(oldJar.toPath()),
                    "running as a user that ignores permissions");
            List<String> downloads = new ArrayList<>();

            ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                    Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar),
                    catalogue("demo", "1.1"), (link, name, folder) -> downloads.add(name));

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_OLD_JAR_UNREADABLE);
            assertThat(result.getReasonArgs()).containsExactly(oldJar.getAbsolutePath());
            assertThat(downloads).isEmpty();
        } finally {
            Files.setPosixFilePermissions(oldJar.toPath(), original);
        }
        assertThat(treeOf(transactions)).noneMatch(p -> p.endsWith(".json"));
    }

    @Test
    @DisplayName("a second update of a module with a staged update names the staged version and changes nothing")
    void secondUpdateWhilePending_changesNothing() throws IOException {
        stage(downloading("Demo", "1.1", "demo"));
        Map<String, String> before = snapshot();
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult again = stage((link, name, folder) -> downloads.add(name));

        assertThat(again.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.ALREADY_STAGED);
        assertThat(again.getNewVersion()).isEqualTo("1.1");
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("a download in progress holds no lock: cancellation returns at once and a second update is refused as busy")
    void downloadInProgress_holdsNoLock() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ModuleFileTransactions.StageResult> first = pool.submit(() ->
                    stage((link, name, folder) -> {
                        entered.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        moduleJar(new File(folder, name), "Demo", "1.1", "demo");
                    }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.CompletableFuture<List<String>> cancel = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Other"));
            assertThat(cancel.get(2, TimeUnit.SECONDS)).isEmpty();
            List<String> downloads = new ArrayList<>();
            ModuleFileTransactions.StageResult second = stage((link, name, folder) -> downloads.add(name));
            assertThat(second.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.BUSY);
            assertThat(downloads).isEmpty();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getOutcome())
                    .isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an uninstall cancels a staged update: its record and staged JAR go, the modules folder is untouched")
    void cancellingAStagedUpdate_removesOnlyTheTransaction() throws IOException {
        stage(downloading("Demo", "1.1", "demo"));

        List<String> cancelled = new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Demo");

        assertThat(cancelled).containsExactly("1.1");
        assertThat(treeOf(transactions)).isEmpty();
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
    }

    @Test
    @DisplayName("cancelling for a module with no staged update cancels nothing")
    void cancellingWithNothingStaged_isANoOp() throws IOException {
        stage(downloading("Demo", "1.1", "demo"));

        List<String> cancelled = new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Other");

        assertThat(cancelled).isEmpty();
        assertThat(treeOf(transactions)).filteredOn(p -> p.endsWith(".json")).hasSize(1);
    }
}
