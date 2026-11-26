package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * An uninstall and a staged update of the same module (#505, #518, gate-1 review): whatever name
 * the uninstall was given, and whether or not its JAR could be deleted, the next start never
 * installs the update of a module the operator removed. And a recorded deletion never deletes a JAR
 * installed again since.
 */
@DisplayName("Module update and uninstall together: the removed module never comes back (#505, #518)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateUninstallTest {

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

    private void stage() {
        ModuleFileTransactions.StageResult result = new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar),
                catalogue("demo", "1.1"), downloading("Demo", "1.1", "demo"));
        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
    }

    private static List<String> keys(ModuleFileTransactions tx) {
        return tx.pendingReports().stream().map(ModuleFileTransactions.Report::getKey).collect(Collectors.toList());
    }

    @Test
    @DisplayName("an uninstall by a name other than the runtime name cancels the update through the JAR it removed")
    void uninstallByAnotherName_cancelsThroughTheRemovedJar() throws IOException {
        stage();

        var journal = new ModuleFileTransactions(dataFolder);
        var result = PendingTestValues.queueRemoval(journal, "UltiTools-Demo", null, oldJar);

        assertThat(result.queued()).isTrue();
        assertThat(PendingTestValues.keys(result)).containsExactly(UpmKeys.UNINSTALL_QUEUED, UpmKeys.UNINSTALL_ALSO_CANCELLED_UPDATE);
        assertThat(PendingTestValues.values(result.replies().getLast().args())).containsExactly("UltiTools-Demo", "1.1");
        assertThat(treeOf(transactions)).singleElement().asString().startsWith("remove-").endsWith(".json");
        assertThat(oldJar).exists();
    }

    @Test
    @DisplayName("an update whose old JAR is recorded for deletion is abandoned even when that deletion fails again")
    void pendingRemovalOfTheOldJar_abandonsTheUpdate() throws IOException {
        stage();
        PendingTestValues.legacyRemoval(dataFolder, "UltiTools-Demo", oldJar);
        ModuleFileTransactions start = new ModuleFileTransactions(modules, transactions,
                new ModuleFileTransactions.FileOps() {
                    @Override
                    public void move(Path from, Path to) throws IOException {
                        ModuleFileTransactions.FileOps.DEFAULT.move(from, to);
                    }

                    @Override
                    public void delete(Path path) throws IOException {
                        throw new java.nio.file.AccessDeniedException(path.toString());
                    }
                }, ModuleFileTransactions.CrashPoints.NONE);

        start.applyBeforeLoad();

        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
        assertThat(keys(start)).containsExactly(UpmKeys.START_REMOVAL_FAILED.translationKey(),
                ModuleFileTransactions.Keys.UPDATE_ABANDONED);
        assertThat(treeOf(transactions)).hasSize(1).allMatch(p -> p.startsWith("remove-"));
    }

    private void cancelRemovalByKeepingTheRunningVersion() {
        var journal = new ModuleFileTransactions(dataFolder);
        var result = journal.stageChange("demo", "1.0", "Demo", null, () -> List.of(loadedOld), ignored -> oldJar,
                catalogue("demo", "1.0"), (link, name, folder) -> {
                    throw new AssertionError("the running version needs no download");
                }, null);
        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.CANCELLED_UNINSTALL_SAME);
    }

    @Test
    @DisplayName("installing the running version cancels the pending removal without touching the JAR")
    void installOverAPendingRemoval_keepsTheRunningVersion() throws IOException {
        byte[] original = Files.readAllBytes(oldJar.toPath());
        PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);

        cancelRemovalByKeepingTheRunningVersion();
        var start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(original);
        assertThat(start.pendingReports()).isEmpty();
        assertThat(treeOf(transactions)).isEmpty();
    }

    @Test
    @DisplayName("an install can cancel a matching removal past an unrelated malformed record, reported at start")
    void installPastAMalformedRecord_clearsTheMatchingRemoval() throws IOException {
        var cleared = PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);
        File malformed = new File(transactions, "a-malformed.json");
        Files.writeString(malformed.toPath(), "{ not json");

        cancelRemovalByKeepingTheRunningVersion();
        assertThat(cleared).doesNotExist();
        assertUnusableRecordIsKeptAtStart(malformed, ModuleFileTransactions.Keys.RECORD_UNREADABLE);
    }

    private File withNullRemoval(String moduleName) throws IOException {
        File file = moduleJar(new File(modules, moduleName + "-x.jar"), moduleName, "1.0", moduleName);
        File record = PendingTestValues.legacyRemoval(dataFolder, moduleName, file);
        var json = JsonParser.parseString(Files.readString(record.toPath())).getAsJsonObject();
        json.getAsJsonArray("removals").add(JsonNull.INSTANCE);
        Files.writeString(record.toPath(), json.toString());
        return record;
    }

    @Test
    @DisplayName("an install cancels the matching removal without touching an unrelated invalid removal")
    void installPastARecordWithANullRemoval_clearsTheMatchingRemoval() throws IOException {
        var cleared = PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);
        File invalid = withNullRemoval("Other");

        cancelRemovalByKeepingTheRunningVersion();
        assertThat(cleared).doesNotExist();
        assertUnusableRecordIsKeptAtStart(invalid, ModuleFileTransactions.Keys.RECORD_REFUSED);
        assertThat(new File(modules, "Other-x.jar")).exists();
    }

    @Test
    @DisplayName("uninstall refuses instead of overwriting its earlier invalid journal")
    void recordingOverARecordWithANullRemoval_isRefused() throws IOException {
        assertNewUninstallRefusesToReplace(withNullRemoval("Demo"), ModuleFileTransactions.Keys.RECORD_REFUSED);
    }

    @Test
    @DisplayName("uninstall refuses instead of overwriting its earlier unreadable journal")
    void recordingOverAMalformedRecord_isRefused() throws IOException {
        File record = PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);
        Files.writeString(record.toPath(), "{ not json");

        assertNewUninstallRefusesToReplace(record, ModuleFileTransactions.Keys.RECORD_UNREADABLE);
    }

    private void assertNewUninstallRefusesToReplace(File record, String startKey) throws IOException {
        byte[] original = Files.readAllBytes(record.toPath());
        var result = PendingTestValues.queueRemoval(new ModuleFileTransactions(dataFolder), "Demo", null, oldJar);

        assertThat(result.queued()).isFalse();
        assertThat(PendingTestValues.keys(result)).containsExactly(UpmKeys.UNINSTALL_NOT_RECORDED);
        assertThat(Files.readAllBytes(record.toPath())).isEqualTo(original);
        assertUnusableRecordIsKeptAtStart(record, startKey);
    }

    private void assertUnusableRecordIsKeptAtStart(File badRecord, String key) throws IOException {
        byte[] original = Files.readAllBytes(badRecord.toPath());
        var start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(start.pendingReports()).singleElement().satisfies(report -> {
            assertThat(report.getKey()).isEqualTo(key);
            assertThat(report.getArgs()).contains(badRecord.getAbsolutePath());
        });
        assertThat(Files.readAllBytes(badRecord.toPath())).isEqualTo(original);
        assertThat(oldJar).exists();
        assertThat(treeOf(transactions)).containsExactly(badRecord.getName());
    }

    @Test
    @DisplayName("a JAR copied back after the uninstall (same bytes, another timestamp) is not deleted")
    void copiedBackJar_isNotDeleted() throws IOException {
        PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);
        assertThat(oldJar.setLastModified(oldJar.lastModified() + 60_000L)).isTrue();

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(oldJar).exists();
        assertThat(keys(start)).containsExactly(UpmKeys.START_REMOVAL_SKIPPED.translationKey());
    }

    @Test
    @DisplayName("recorded deletions run before updates, whatever their record names sort as")
    void removalsRunBeforeUpdates() throws IOException {
        stage();
        PendingTestValues.legacyRemoval(dataFolder, "Demo", oldJar);

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(namesIn(modules)).isEmpty();
        assertThat(keys(start)).containsExactly(UpmKeys.START_REMOVAL_REMOVED.translationKey(),
                ModuleFileTransactions.Keys.UPDATE_ABANDONED);
    }
}
