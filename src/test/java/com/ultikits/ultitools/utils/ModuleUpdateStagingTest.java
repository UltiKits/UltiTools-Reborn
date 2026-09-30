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
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
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
    @DisplayName("a download declaring a version other than the catalogue's latest is refused and leaves nothing behind (Codex round 6)")
    void downloadOfAnotherVersion_changesNothing() throws IOException {
        Map<String, String> before = snapshot();

        // The catalogue says 1.1; a stale endpoint serves a valid JAR of the same module declaring 1.0.
        ModuleFileTransactions.StageResult result = stage(downloading("Demo", "1.0", "demo"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_WRONG_VERSION);
        assertThat(result.getReasonArgs()).containsExactly("demo-1.1.jar", "1.0", "1.1");
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
    @DisplayName("two module classes from ONE JAR share its plugin.yml identify-string: the update is still refused (Codex round 8)")
    void jarSharedWithASameIdentityModule_isRefused() throws IOException {
        // identify-string is read from the archive's plugin.yml, so every module class packaged in
        // one JAR reports the same one -- the real shared-archive case.
        UltiToolsPlugin companion = loadedModule("DemoCompanion", "1.0", "demo");
        ModuleUpdateFixtures.CodeSources sources =
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar).with(companion, oldJar);
        Map<String, String> before = snapshot();
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult selectedFirst = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                java.util.Arrays.asList(loadedOld, companion), sources, catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name));
        ModuleFileTransactions.StageResult companionFirst = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                java.util.Arrays.asList(companion, loadedOld), sources, catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name));

        assertThat(selectedFirst.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(selectedFirst.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_SHARED_JAR);
        assertThat(selectedFirst.getReasonArgs()).containsExactly(oldJar.getAbsolutePath(), "DemoCompanion");
        assertThat(companionFirst.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(companionFirst.getReasonArgs()).containsExactly(oldJar.getAbsolutePath(), "Demo");
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("two loaded modules in different JARs declaring the same identify-string: the update is refused, whichever is listed first (Codex, #561)")
    void identifyStringDeclaredByModulesInTwoJars_isRefused() throws IOException {
        // A misconfiguration, but a real one: two modules whose plugin.yml files carry the same
        // identify-string. /upm update passes only that string, so picking the first match could
        // replace, and commit, the other module's JAR while reporting the requested one staged.
        File twinJar = moduleJar(new File(modules, "twin-1.0.jar"), "Twin", "1.0", "demo");
        UltiToolsPlugin twin = loadedModule("Twin", "1.0", "demo");
        ModuleUpdateFixtures.CodeSources sources =
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar).with(twin, twinJar);
        Map<String, String> before = snapshot();
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult demoFirst = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                java.util.Arrays.asList(loadedOld, twin), sources, catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name));
        ModuleFileTransactions.StageResult twinFirst = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                java.util.Arrays.asList(twin, loadedOld), sources, catalogue("demo", "1.1"),
                (link, name, folder) -> downloads.add(name));

        assertThat(demoFirst.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(demoFirst.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_AMBIGUOUS_IDENTIFY_STRING);
        assertThat(demoFirst.getReasonArgs()).containsExactly("demo", "Demo, Twin");
        assertThat(twinFirst.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_AMBIGUOUS_IDENTIFY_STRING);
        assertThat(twinFirst.getReasonArgs()).containsExactly("demo", "Twin, Demo");
        assertThat(downloads).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("an uninstall that cancels while staging reads the loaded modules is not missed: the loaded modules are read under the lock (Codex round 16, #561)")
    void uninstallDuringTheLoadedModulesRead_cancelsTheUpdate() throws Exception {
        // /upm update used to take its snapshot of the loaded modules before staging took its lock:
        // an uninstall completing in that gap found no download marker and no record to cancel,
        // and the stale snapshot then staged the update of a module that had just been removed.
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<List<UltiToolsPlugin>> loaded = () -> {
            reading.countDown();
            try {
                // Long enough for the uninstall below to reach the lock while this read is running.
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Collections.singletonList(loadedOld);
        };
        ModuleFileTransactions.Downloader waitsForTheUninstall = (link, name, folder) -> {
            try {
                cancelled.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            moduleJar(new File(folder, name), "Demo", "1.1", "demo");
        };
        java.util.concurrent.ExecutorService command = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ModuleFileTransactions.StageResult> staging = command.submit(() ->
                    new ModuleFileTransactions(dataFolder).stageUpdate("demo", loaded,
                            new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                            waitsForTheUninstall, null));
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

            new ModuleFileTransactions(dataFolder).cancelStagedUpdates(new ModuleFileTransactions.RemovedModule(
                    Collections.singletonList("demo"), Collections.singletonList("Demo"),
                    Collections.singletonList("demo-1.0.jar")));
            cancelled.countDown();

            ModuleFileTransactions.StageResult result = staging.get(20, TimeUnit.SECONDS);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_CANCELLED_WHILE_DOWNLOADING);
            assertThat(treeOf(transactions)).as("no record is written for the uninstalled module").isEmpty();
        } finally {
            command.shutdownNow();
        }
    }

    @Test
    @DisplayName("an update held for the operator refuses /upm update of the module, and nothing changes (round 19)")
    void updateHeldForTheOperator_refusesStaging() throws IOException {
        assertThat(stage(ModuleUpdateFixtures.downloading("Demo", "1.1", "demo")).getOutcome())
                .isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(records).hasSize(1);
        String text = new String(Files.readAllBytes(records[0].toPath()), StandardCharsets.UTF_8)
                .replace("\"state\": \"PENDING\"", "\"state\": \"NEEDS_OPERATOR\",\n  \"failure\": \"a foreign file\"");
        Files.write(records[0].toPath(), text.getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = snapshot();

        ModuleFileTransactions.StageResult result = stage(ModuleUpdateFixtures.downloading("Demo", "1.1", "demo"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_NEEDS_OPERATOR);
        assertThat(result.getReasonArgs()).containsExactly("a foreign file", records[0].getAbsolutePath());
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
    @DisplayName("an uninstall's cancellation over an unlistable records folder cancels nothing and does not throw")
    void cancellationOverUnlistableFolder_cancelsNothing() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
        stage(downloading("Demo", "1.1", "demo"));
        java.util.Set<java.nio.file.attribute.PosixFilePermission> original =
                Files.getPosixFilePermissions(transactions.toPath());
        Files.setPosixFilePermissions(transactions.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("-wx------"));
        List<String> cancelled;
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(transactions.list() == null,
                    "running as a user that ignores permissions");

            cancelled = new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Demo");
        } finally {
            Files.setPosixFilePermissions(transactions.toPath(), original);
        }

        assertThat(cancelled).isEmpty();
        assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith(".json"));
    }

    @Test
    @DisplayName("an incomplete staged record is reported as unreadable, never as already staged (Codex round 7)")
    void incompleteRecord_isReportedNotAlreadyStaged() throws IOException {
        stage(downloading("Demo", "1.1", "demo"));
        File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(records).hasSize(1);
        String text = new String(Files.readAllBytes(records[0].toPath()), StandardCharsets.UTF_8);
        Files.write(records[0].toPath(), text.replaceFirst("\\s*\"newVersion\": \"[^\"]*\",", "")
                .getBytes(StandardCharsets.UTF_8));
        List<String> downloads = new ArrayList<>();

        ModuleFileTransactions.StageResult again = stage((link, name, folder) -> downloads.add(name));

        assertThat(again.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(again.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_RECORD_UNREADABLE);
        assertThat(again.getReasonArgs()).containsExactly(records[0].getAbsolutePath(), "missing field newVersion");
        assertThat(downloads).isEmpty();
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

    /**
     * Stages Demo's update with a download that waits, runs {@code cancel} while it waits, then lets
     * it finish, and returns what staging replied.
     */
    private ModuleFileTransactions.StageResult cancelledWhileDownloading(java.util.function.Supplier<List<String>> cancel)
            throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ModuleFileTransactions.StageResult> staging = pool.submit(() ->
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
            assertThat(java.util.concurrent.CompletableFuture.supplyAsync(cancel).get(2, TimeUnit.SECONDS)).isEmpty();
            release.countDown();
            return staging.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an uninstall by name during the download cancels it: no record is written, nothing applies (Codex round 9)")
    void uninstallByNameDuringDownload_cancelsIt() throws Exception {
        byte[] before = Files.readAllBytes(oldJar.toPath());

        ModuleFileTransactions.StageResult result = cancelledWhileDownloading(
                () -> new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Demo"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_CANCELLED_WHILE_DOWNLOADING);
        assertThat(treeOf(transactions)).isEmpty();
        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
        assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(before);
    }

    @Test
    @DisplayName("an uninstall by another name that removed the module's JAR during the download cancels it too")
    void uninstallByJarDuringDownload_cancelsIt() throws Exception {
        ModuleFileTransactions.StageResult result = cancelledWhileDownloading(
                () -> new ModuleFileTransactions(dataFolder).cancelStagedUpdates(
                        new ModuleFileTransactions.RemovedModule(Collections.<String>emptyList(),
                                Collections.singletonList("demo-module"), Collections.singletonList(oldJar.getName()))));

        assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_CANCELLED_WHILE_DOWNLOADING);
        assertThat(treeOf(transactions)).isEmpty();
    }

    @Test
    @DisplayName("a cancellation for another module during the download leaves it staged, and leaves no marker behind")
    void unrelatedCancellationDuringDownload_leavesItStaged() throws Exception {
        ModuleFileTransactions.StageResult result = cancelledWhileDownloading(
                () -> new ModuleFileTransactions(dataFolder).cancelStagedUpdates("Other"));

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
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
