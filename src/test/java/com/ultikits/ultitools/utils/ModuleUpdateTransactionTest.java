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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The module update transaction, end to end (#505, #513): {@code /upm update} stages the new JAR
 * outside the modules folder, the next start swaps the files before the module class loader is
 * built, and the update is committed only when the modules that loaded show the module at the new
 * version, loaded from the new JAR. Nothing here predicts whether a JAR will load; the decision is
 * taken from what the start observed.
 */
@DisplayName("Module update transaction: stage, apply at the next start, observe, commit (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateTransactionTest {

    @TempDir
    File dataFolder;

    private File modules;
    private File transactions;
    private File oldJar;
    private UltiToolsPlugin loadedOld;
    private ModuleUpdateFixtures.CodeSources sources;

    @BeforeEach
    void setUp() throws IOException {
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
        oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        loadedOld = loadedModule("Demo", "1.0", "demo");
        sources = new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar);
    }

    private ModuleFileTransactions.StageResult stageDemo() {
        return new ModuleFileTransactions(dataFolder).stageUpdate("demo",
                Collections.singletonList(loadedOld), sources, catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));
    }

    @Test
    @DisplayName("the modules folder and transactions folder are siblings under the data folder")
    void foldersAreSiblingsOfTheDataFolder() throws IOException {
        assertThat(modules.getCanonicalFile()).isEqualTo(new File(dataFolder, "plugins").getCanonicalFile());
        assertThat(transactions.getParentFile().getCanonicalFile()).isEqualTo(dataFolder.getCanonicalFile());
        assertThat(transactions.getCanonicalPath()).doesNotStartWith(modules.getCanonicalPath() + File.separator);
    }

    @Test
    @DisplayName("staging leaves the modules folder unchanged, writes one PENDING record and the staged JAR")
    void stagingLeavesTheModulesFolderUnchanged() throws IOException {
        byte[] before = Files.readAllBytes(oldJar.toPath());

        ModuleFileTransactions.StageResult result = stageDemo();

        assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
        assertThat(result.getOldVersion()).isEqualTo("1.0");
        assertThat(result.getNewVersion()).isEqualTo("1.1");
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
        assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(before);
        List<String> tree = treeOf(transactions);
        assertThat(tree).filteredOn(p -> p.endsWith(".json")).hasSize(1);
        assertThat(tree).filteredOn(p -> p.endsWith("/staged/demo-1.1.jar")).hasSize(1);
        String record = recordText();
        assertThat(record).contains("\"state\": \"PENDING\"").contains("\"newVersion\": \"1.1\"")
                .contains("\"oldName\": \"demo-1.0.jar\"").contains("\"targetName\": \"demo-1.1.jar\"");
    }

    @Test
    @DisplayName("the next start applies before loading, then commits once the module is observed at the new version")
    void nextStartAppliesThenCommitsOnObservation() throws IOException {
        stageDemo();

        ModuleFileTransactions nextStart = new ModuleFileTransactions(dataFolder);
        nextStart.applyBeforeLoad();

        assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
        assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"));
        assertThat(recordText()).contains("\"state\": \"APPLIED\"");

        File newJar = new File(modules, "demo-1.1.jar");
        UltiToolsPlugin loadedNew = loadedModule("Demo", "1.1", "demo");
        nextStart.observeAfterLoad(Collections.singletonList(loadedNew),
                new ModuleUpdateFixtures.CodeSources().with(loadedNew, newJar));

        assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
        assertThat(treeOf(transactions)).isEmpty();
        List<ModuleFileTransactions.Report> reports = nextStart.pendingReports();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(reports.get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.COMMITTED);
        assertThat(reports.get(0).getArgs()).containsExactly("Demo", "1.0", "1.1");
    }

    private String recordText() throws IOException {
        File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(records).isNotNull().hasSize(1);
        return new String(Files.readAllBytes(records[0].toPath()), StandardCharsets.UTF_8);
    }
}
