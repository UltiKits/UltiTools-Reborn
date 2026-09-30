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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

        List<String> cancelled = new ModuleFileTransactions(dataFolder)
                .cancelStagedUpdates("UltiTools-Demo", Collections.singletonList(oldJar.getName()));

        assertThat(cancelled).containsExactly("1.1");
        assertThat(treeOf(transactions)).isEmpty();
    }

    @Test
    @DisplayName("an update whose old JAR is recorded for deletion is abandoned even when that deletion fails again")
    void pendingRemovalOfTheOldJar_abandonsTheUpdate() throws IOException {
        stage();
        new ModuleFileTransactions(dataFolder).recordDeferredRemoval("UltiTools-Demo",
                Collections.singletonList(oldJar));
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
        assertThat(keys(start)).containsExactly(ModuleFileTransactions.Keys.REMOVAL_FAILED,
                ModuleFileTransactions.Keys.UPDATE_ABANDONED);
        assertThat(treeOf(transactions)).hasSize(1).allMatch(p -> p.startsWith("remove-"));
    }

    @Test
    @DisplayName("an install that writes the recorded file name again cancels its recorded deletion")
    void installOverAPendingRemoval_keepsTheNewInstall() throws IOException {
        new ModuleFileTransactions(dataFolder).recordDeferredRemoval("Demo", Collections.singletonList(oldJar));

        new ModuleFileTransactions(dataFolder).forgetDeferredRemoval(oldJar.getName());
        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(oldJar).exists();
        assertThat(start.pendingReports()).isEmpty();
        assertThat(treeOf(transactions)).isEmpty();
    }

    @Test
    @DisplayName("a JAR copied back after the uninstall (same bytes, another timestamp) is not deleted")
    void copiedBackJar_isNotDeleted() throws IOException {
        new ModuleFileTransactions(dataFolder).recordDeferredRemoval("Demo", Collections.singletonList(oldJar));
        assertThat(oldJar.setLastModified(oldJar.lastModified() + 60_000L)).isTrue();

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(oldJar).exists();
        assertThat(keys(start)).containsExactly(ModuleFileTransactions.Keys.REMOVAL_SKIPPED);
    }

    @Test
    @DisplayName("recorded deletions run before updates, whatever their record names sort as")
    void removalsRunBeforeUpdates() throws IOException {
        stage();
        new ModuleFileTransactions(dataFolder).recordDeferredRemoval("Demo", Arrays.asList(oldJar));

        ModuleFileTransactions start = new ModuleFileTransactions(dataFolder);
        start.applyBeforeLoad();

        assertThat(namesIn(modules)).isEmpty();
        assertThat(keys(start)).containsExactly(ModuleFileTransactions.Keys.REMOVED,
                ModuleFileTransactions.Keys.UPDATE_ABANDONED);
    }
}
