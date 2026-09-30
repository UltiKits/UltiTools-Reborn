package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The recovery invariant of a module update transaction (#505, #513; Codex round 14 on #561, swept
 * by defect class).
 *
 * <p><b>Invariant.</b> After any start, for every transaction record, the modules folder holds exactly
 * one JAR for that module (the old or the new, never both, never neither unless the record is a
 * removal), and a JAR not in the modules folder is either staged or in the kept-old location,
 * identified by its recorded hash. "Unless the record is a removal" covers the one update that ends
 * with neither: an uninstall took the old JAR before the update was applied, and the update is
 * abandoned rather than bringing back a module the operator removed. A JAR outside the modules folder
 * exists only while its record does: a finished transaction leaves no stray copy.
 *
 * <p><b>How this is checked.</b> Each case builds, on disk, one combination of record state and file
 * placement that a crash or a failed file operation can leave -- the old JAR in the modules folder,
 * kept aside, or (for a commit being cleaned up, or an uninstall) gone; the new JAR staged, in the
 * modules folder, or gone -- then runs one start (the before-load recovery, then the observation, with
 * the new version loading or not) with file operations that succeed, and asserts the invariant. A
 * second family does the same for {@code /upm update} discarding a {@code FAILED} record while the
 * server runs, the other place a failed apply is undone. Each file is identified by its recorded
 * SHA-256, never by its name, as the recovery identifies it. The reachability of every combination,
 * and what the next start does with it, is tabled under Codex round 14 in the phase review file.
 *
 * <p>A start whose file operations fail keeps the record for the next start and reports it; the
 * invariant is re-established by the first start whose operations succeed, which is what is run here.
 *
 * <p><b>Files the transaction cannot identify.</b> Another actor may change the modules folder or the
 * transaction folder while a transaction is pending -- replace the new JAR, drop a file where the old
 * one is kept, and so on. That space is open-ended, so it is closed by one rule rather than case by
 * case: <i>the transaction only ever moves, replaces or deletes a file whose SHA-256 matches the
 * record</i> (the staged new JAR, or the kept old JAR). A second family of cases puts a foreign file at
 * each location a record uses (the old JAR's name in the modules folder, the kept-old location, the
 * staged location, the new JAR's name in the modules folder), for every reachable row, and asserts
 * that the start never moves or deletes it, never deletes the last copy of the module it can identify,
 * never moves an identified JAR into the modules folder beside it, and leaves nothing a later start
 * would still act on.
 */
@DisplayName("Module update recovery keeps exactly one JAR of the module in the modules folder (Codex round 14, #561)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ModuleUpdateRecoveryInvariantTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Where the old JAR is: in the modules folder, kept aside, or gone. */
    enum Old { MODULES, KEPT, GONE }

    /** Where the new JAR is: staged, in the modules folder, or gone. */
    enum New { STAGED, MODULES, GONE }

    @TempDir
    File serverRoot;

    /**
     * Every record state with every placement a crash or a failure can leave it in (the table under
     * Codex round 14 gives the path to each), crossed with the old JAR sharing the new JAR's file name
     * or not, and the new version loading or not.
     */
    static Stream<Arguments> reachable() {
        Object[][] rows = {
            // PENDING: staged; crash after the old JAR moved; crash after the new JAR moved (or APPLIED
            // could not be written); an after-load rollback whose record writes failed, ending after or
            // before the old JAR went back (or the staged JAR removed by hand); an uninstall.
            {"PENDING", Old.MODULES, New.STAGED}, {"PENDING", Old.KEPT, New.STAGED},
            {"PENDING", Old.KEPT, New.MODULES}, {"PENDING", Old.MODULES, New.GONE},
            {"PENDING", Old.KEPT, New.GONE}, {"PENDING", Old.GONE, New.STAGED},
            // APPLIED: swapped, not decided; a rollback whose ROLLING_BACK could not be written, ending
            // before or after the old JAR went back.
            {"APPLIED", Old.KEPT, New.MODULES}, {"APPLIED", Old.KEPT, New.GONE}, {"APPLIED", Old.MODULES, New.GONE},
            // COMMITTING: decided; the kept JAR deleted before the record.
            {"COMMITTING", Old.KEPT, New.MODULES}, {"COMMITTING", Old.GONE, New.MODULES},
            // ROLLING_BACK: decided; the new JAR removed; the old JAR back before the record was deleted.
            {"ROLLING_BACK", Old.KEPT, New.MODULES}, {"ROLLING_BACK", Old.KEPT, New.GONE},
            {"ROLLING_BACK", Old.MODULES, New.GONE},
            // FAILED: undone; the old JAR could not go back; the new JAR could not go back (round 14);
            // the staged JAR was missing, with the old JAR back or not.
            {"FAILED", Old.MODULES, New.STAGED}, {"FAILED", Old.KEPT, New.STAGED}, {"FAILED", Old.KEPT, New.MODULES},
            {"FAILED", Old.MODULES, New.GONE}, {"FAILED", Old.KEPT, New.GONE},
        };
        List<Arguments> cases = new ArrayList<>();
        for (Object[] row : rows) {
            for (boolean sameName : new boolean[]{false, true}) {
                for (boolean newLoads : new boolean[]{true, false}) {
                    cases.add(Arguments.of(row[0], row[1], row[2], sameName, newLoads));
                }
            }
        }
        return cases.stream();
    }

    /** The {@code FAILED} placements, crossed with the old JAR sharing the new JAR's name or not. */
    static Stream<Arguments> failed() {
        return reachable().filter(arguments -> "FAILED".equals(arguments.get()[0]) && (Boolean) arguments.get()[4])
                .map(arguments -> Arguments.of(arguments.get()[1], arguments.get()[2], arguments.get()[3]));
    }

    /** Where a foreign file is put: one of the four locations a record uses. */
    enum Foreign { OLD_NAME, KEPT, STAGED, NEW_NAME }

    /** Every reachable row with a foreign file at each location, the new version loading or not. */
    static Stream<Arguments> foreign() {
        List<Arguments> cases = new ArrayList<>();
        reachable().filter(arguments -> !(Boolean) arguments.get()[3]).forEach(arguments -> {
            for (Foreign where : Foreign.values()) {
                Object[] row = arguments.get();
                cases.add(Arguments.of(row[0], row[1], row[2], where, row[4]));
            }
        });
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, old {1}, new {2}, foreign file at {3}, new loads {4}")
    @MethodSource("foreign")
    @DisplayName("a file the transaction cannot identify is never moved or deleted, and nothing is done beside it")
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertions live in Scenario#assertForeignUntouched
    void aForeignFile_isNeverTouched(String state, Old old, New neu, Foreign where, boolean newLoads)
            throws IOException {
        Scenario scenario = new Scenario(false).build(state, old, neu);
        File foreign = scenario.putForeign(where);
        Snapshot before = scenario.snapshot();

        ModuleFileTransactions first = scenario.start(newLoads);

        scenario.assertForeignUntouched(foreign, before);
        if (scenario.recordFile.exists() && scenario.held()) {
            assertThat(first.pendingReports()).as("a record held for the operator is reported once, SEVERE")
                    .anySatisfy(report -> {
                        assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.NEEDS_OPERATOR);
                        assertThat(report.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
                    });
        }
        if (scenario.recordFile.exists()) {
            Snapshot afterFirst = scenario.snapshot();
            scenario.start(newLoads);
            assertThat(scenario.snapshot().files).as("a later start does not act on what is left").isEqualTo(afterFirst.files);
        }
    }

    /** Every file under the server root with its SHA-256. */
    private static final class Snapshot {
        private final java.util.Map<String, String> files = new java.util.TreeMap<>();
    }

    @ParameterizedTest(name = "{0}, old {1}, new {2}, same name {3}, new loads {4}")
    @MethodSource("reachable")
    @DisplayName("after a start, the modules folder holds exactly one JAR of the module and nothing is stray")
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertions live in Scenario#assertInvariant
    void afterAStart_theInvariantHolds(String state, Old old, New neu, boolean sameName, boolean newLoads)
            throws IOException {
        Scenario scenario = new Scenario(sameName).build(state, old, neu);

        ModuleFileTransactions start = scenario.transactions();
        start.applyBeforeLoad();
        List<UltiToolsPlugin> loaded = new ArrayList<>();
        ModuleUpdateFixtures.CodeSources sources = scenario.load(newLoads, loaded);
        start.observeAfterLoad(loaded, sources);

        scenario.assertInvariant(old == Old.GONE && "PENDING".equals(state));
        if ("FAILED".equals(state)) {
            scenario.assertOldVersionInPlace();
        }
    }

    @ParameterizedTest(name = "FAILED, old {0}, new {1}, same name {2}")
    @MethodSource("failed")
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertions live in Scenario#assertInvariant
    @DisplayName("after /upm update discards a FAILED record, the old JAR alone is in the modules folder and nothing is stray")
    void afterDiscardingAFailedRecord_theInvariantHolds(Old old, New neu, boolean sameName) throws IOException {
        Scenario scenario = new Scenario(sameName).build("FAILED", old, neu);

        // The catalogue offers nothing, so staging stops after it has dealt with the FAILED record.
        scenario.transactions().stageUpdate("demo", Collections.singletonList(scenario.loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(scenario.loadedOld, scenario.oldJar),
                catalogue("other", "9.9"), downloading("Demo", "1.1", "demo"));

        scenario.assertInvariant(false);
        scenario.assertOldVersionInPlace();
    }

    /** One server layout with one staged update of {@code demo} from 1.0 to 1.1. */
    private final class Scenario {
        private final File modules;
        private final File transactions;
        private final File oldJar;
        private final UltiToolsPlugin loadedOld;
        private final String oldHash;
        private String newHash;
        private File recordFile;
        private File backup;
        private File staged;
        private File target;

        Scenario(boolean sameName) throws IOException {
            File dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
            modules = ModuleFileTransactions.modulesFolder(dataFolder);
            transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
            // With the same name, the old JAR already has the file name the new one is staged under.
            // Both names are this test's own constants, inside its temporary folder.
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            oldJar = moduleJar(new File(modules, sameName ? "demo-1.1.jar" : "demo-1.0.jar"), "Demo", "1.0", "demo");
            oldHash = ModuleFileTransactions.sha256Of(oldJar);
            loadedOld = loadedModule("Demo", "1.0", "demo");
        }

        ModuleFileTransactions transactions() {
            return new ModuleFileTransactions(modules, transactions, ModuleFileTransactions.FileOps.DEFAULT,
                    ModuleFileTransactions.CrashPoints.NONE);
        }

        /** Stages the update for real, then moves the files and sets the state a crash or failure leaves. */
        Scenario build(String state, Old old, New neu) throws IOException {
            ModuleFileTransactions.StageResult result = transactions().stageUpdate("demo",
                    Collections.singletonList(loadedOld), new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar),
                    catalogue("demo", "1.1"), downloading("Demo", "1.1", "demo"));
            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
            recordFile = onlyRecord();
            // Every name below comes from the record this test's own staging just wrote into its
            // temporary folder; nothing is external input.
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            File work = new File(transactions, recordFile.getName().replace(".json", ""));
            JsonObject record = readRecord();
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            backup = new File(new File(work, "backup"), record.get("oldName").getAsString());
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            staged = new File(new File(work, "staged"), record.get("stagedName").getAsString());
            // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
            target = new File(modules, record.get("targetName").getAsString());
            newHash = ModuleFileTransactions.sha256Of(staged);

            if (old == Old.KEPT) {
                Files.createDirectories(backup.getParentFile().toPath());
                Files.move(oldJar.toPath(), backup.toPath());
            } else if (old == Old.GONE) {
                Files.delete(oldJar.toPath());
            }
            if (neu == New.MODULES) {
                Files.move(staged.toPath(), target.toPath());
            } else if (neu == New.GONE) {
                Files.delete(staged.toPath());
            }
            record.addProperty("state", state);
            if ("FAILED".equals(state)) {
                record.addProperty("failure", "injected failure");
            }
            try (Writer writer = Files.newBufferedWriter(recordFile.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(record, writer);
            }
            return this;
        }

        /**
         * What the module loader does at this start: the first JAR of the module in file-name order
         * supplies it; the new version loads only when {@code newLoads}.
         */
        ModuleUpdateFixtures.CodeSources load(boolean newLoads, List<UltiToolsPlugin> loaded) throws IOException {
            ModuleUpdateFixtures.CodeSources sources = new ModuleUpdateFixtures.CodeSources();
            List<File> jars = jarsOfTheModuleIn(modules);
            if (jars.isEmpty()) {
                return sources;
            }
            File first = jars.get(0);
            boolean isNew = newHash.equals(ModuleFileTransactions.sha256Of(first));
            if (isNew && !newLoads) {
                return sources;
            }
            UltiToolsPlugin plugin = loadedModule("Demo", isNew ? "1.1" : "1.0", "demo");
            loaded.add(plugin);
            sources.with(plugin, first);
            return sources;
        }

        void assertInvariant(boolean removed) throws IOException {
            List<File> inModules = jarsOfTheModuleIn(modules);
            if (removed) {
                assertThat(inModules).as("the module was removed: no JAR of it comes back").isEmpty();
            } else {
                assertThat(inModules).as("exactly one JAR of the module in the modules folder").hasSize(1);
            }
            boolean recordLeft = recordFile.exists();
            for (String path : treeOf(transactions)) {
                // The path comes from listing this test's own temporary folder.
                // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
                File file = new File(transactions, path);
                String hash = ModuleFileTransactions.sha256Of(file);
                if (oldHash.equals(hash)) {
                    assertThat(file).as("the old JAR outside the modules folder is in the kept-old location")
                            .isEqualTo(backup);
                    assertThat(recordLeft).as("a kept old JAR exists only while its record does").isTrue();
                } else if (newHash.equals(hash)) {
                    assertThat(file).as("the new JAR outside the modules folder is staged").isEqualTo(staged);
                    assertThat(recordLeft).as("a staged JAR exists only while its record does").isTrue();
                }
            }
        }

        /**
         * A failed apply is fully undone once file operations succeed: the old version is the JAR in
         * the modules folder, whether or not it shares the new JAR's file name.
         */
        void assertOldVersionInPlace() throws IOException {
            assertThat(jarsOfTheModuleIn(modules)).as("the old version is back").hasSize(1)
                    .allSatisfy(jar -> assertThat(ModuleFileTransactions.sha256Of(jar)).isEqualTo(oldHash));
        }

        /** Puts a foreign JAR at {@code where}, replacing whatever the scenario left there. */
        File putForeign(Foreign where) throws IOException {
            File location = where == Foreign.OLD_NAME ? oldJar : where == Foreign.KEPT ? backup
                    : where == Foreign.STAGED ? staged : target;
            if (location.exists()) {
                Files.delete(location.toPath());
            }
            moduleJar(location, "Stranger", "9.9", "stranger");
            return location;
        }

        /** One start: the recovery before load, then the observation of what the loader loaded. */
        ModuleFileTransactions start(boolean newLoads) throws IOException {
            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            List<UltiToolsPlugin> loaded = new ArrayList<>();
            ModuleUpdateFixtures.CodeSources sources = load(newLoads, loaded);
            start.observeAfterLoad(loaded, sources);
            return start;
        }

        /** Whether the record is held for the operator. */
        boolean held() throws IOException {
            return "NEEDS_OPERATOR".equals(readRecord().get("state").getAsString());
        }

        Snapshot snapshot() throws IOException {
            Snapshot snapshot = new Snapshot();
            for (String path : treeOf(serverRoot)) {
                // The path comes from listing this test's own temporary folder.
                // nosemgrep: java_inject_rule-SpotbugsPathTraversalAbsolute
                snapshot.files.put(path, ModuleFileTransactions.sha256Of(new File(serverRoot, path)));
            }
            return snapshot;
        }

        /**
         * The foreign file is where it was, unchanged; the module keeps at most one identified JAR in the
         * modules folder; an identified copy of the module that existed still exists somewhere; and no
         * identified JAR was moved into the modules folder beside the foreign file.
         */
        void assertForeignUntouched(File foreign, Snapshot before) throws IOException {
            String foreignHash = before.files.get(relative(foreign));
            assertThat(foreign).as("the foreign file is never moved or deleted").exists();
            assertThat(ModuleFileTransactions.sha256Of(foreign)).as("the foreign file is never replaced")
                    .isEqualTo(foreignHash);
            assertThat(jarsOfTheModuleIn(modules)).as("at most one identified JAR of the module in the modules folder")
                    .hasSizeLessThanOrEqualTo(1);
            Snapshot after = snapshot();
            if (identified(before) > 0) {
                assertThat(identified(after)).as("the last identified copy of the module is never deleted")
                        .isPositive();
            }
            if (foreign.getParentFile().equals(modules)) {
                String modulesPrefix = relative(modules) + "/";
                for (java.util.Map.Entry<String, String> file : after.files.entrySet()) {
                    boolean identifiedInModules = file.getKey().startsWith(modulesPrefix)
                            && (oldHash.equals(file.getValue()) || newHash.equals(file.getValue()));
                    assertThat(identifiedInModules && !file.getValue().equals(before.files.get(file.getKey())))
                            .as("no identified JAR is moved into the modules folder beside a foreign file: " + file.getKey())
                            .isFalse();
                }
            }
        }

        private long identified(Snapshot snapshot) {
            return snapshot.files.values().stream().filter(hash -> oldHash.equals(hash) || newHash.equals(hash)).count();
        }

        private String relative(File file) {
            return serverRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        }

        private List<File> jarsOfTheModuleIn(File folder) throws IOException {
            List<File> jars = new ArrayList<>();
            File[] files = ModuleFileTransactions.moduleJars(folder);
            if (files == null) {
                return jars;
            }
            for (File file : files) {
                String hash = ModuleFileTransactions.sha256Of(file);
                if (oldHash.equals(hash) || newHash.equals(hash)) {
                    jars.add(file);
                }
            }
            return jars;
        }

        private File onlyRecord() {
            File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
            assertThat(records).hasSize(1);
            return records[0];
        }

        private JsonObject readRecord() throws IOException {
            try (Reader reader = Files.newBufferedReader(recordFile.toPath(), StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, JsonObject.class);
            }
        }
    }
}
