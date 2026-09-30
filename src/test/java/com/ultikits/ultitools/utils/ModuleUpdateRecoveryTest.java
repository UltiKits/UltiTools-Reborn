package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.downloading;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.loadedModule;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.namesIn;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.treeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * The update transaction's rollback, its truthful failures and its recovery after a crash (#505,
 * #513). Every crash window is reproduced by crashing the real code at the named point -- not by
 * hand-building the files it would have left -- and the next start is a fresh instance reading only
 * what is on disk.
 */
@DisplayName("Module update transaction: rollback, truthful failures, crash windows (#505)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateRecoveryTest {

    /** What a crash looks like to the code: the thread stops at that point. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String point) {
            super("crash at " + point);
        }
    }

    @TempDir
    File dataFolder;

    private File modules;
    private File transactions;
    private File oldJar;
    private byte[] oldBytes;
    private UltiToolsPlugin loadedOld;

    @BeforeEach
    void setUp() throws IOException {
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        transactions = ModuleFileTransactions.transactionsFolder(dataFolder);
        oldJar = moduleJar(new File(modules, "demo-1.0.jar"), "Demo", "1.0", "demo");
        oldBytes = Files.readAllBytes(oldJar.toPath());
        loadedOld = loadedModule("Demo", "1.0", "demo");
    }

    private ModuleFileTransactions transactions() {
        return new ModuleFileTransactions(dataFolder);
    }

    private ModuleFileTransactions crashingAt(String point) {
        return new ModuleFileTransactions(modules, transactions, ModuleFileTransactions.FileOps.DEFAULT, reached -> {
            if (reached.equals(point)) {
                throw new SimulatedCrash(reached);
            }
        });
    }

    private ModuleFileTransactions.StageResult stage(ModuleFileTransactions tx) {
        return tx.stageUpdate("demo", Collections.singletonList(loadedOld),
                new ModuleUpdateFixtures.CodeSources().with(loadedOld, oldJar), catalogue("demo", "1.1"),
                downloading("Demo", "1.1", "demo"));
    }

    private File newJar() {
        return new File(modules, "demo-1.1.jar");
    }

    /** A start at which the module loaded from the new JAR at the new version. */
    private void observeLoaded(ModuleFileTransactions tx) {
        UltiToolsPlugin loadedNew = loadedModule("Demo", "1.1", "demo");
        tx.observeAfterLoad(Collections.singletonList(loadedNew),
                new ModuleUpdateFixtures.CodeSources().with(loadedNew, newJar()));
    }

    /** A start at which the module did not load at all. */
    private void observeAbsent(ModuleFileTransactions tx) {
        tx.observeAfterLoad(Collections.<UltiToolsPlugin>emptyList(), new ModuleUpdateFixtures.CodeSources());
    }

    private void assertOnlyTheOldVersionIsInstalled() throws IOException {
        assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
        assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(oldBytes);
    }

    private String recordText() throws IOException {
        File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(records).isNotNull().hasSize(1);
        return new String(Files.readAllBytes(records[0].toPath()), StandardCharsets.UTF_8);
    }

    private static ModuleFileTransactions.Report onlyReport(ModuleFileTransactions tx) {
        List<ModuleFileTransactions.Report> reports = tx.pendingReports();
        assertThat(reports).hasSize(1);
        return reports.get(0);
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("a module absent after the start is rolled back: old JAR restored, new removed, one line names both versions")
        void moduleAbsentAfterLoad_restoresTheOldVersion() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();

            observeAbsent(start);

            assertOnlyTheOldVersionIsInstalled();
            assertThat(treeOf(transactions)).isEmpty();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.WARNING);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLED_BACK);
            assertThat(report.getArgs()).containsExactly("Demo", "1.1", "1.0", "1.0");
        }

        @Test
        @DisplayName("a module loaded at the new version but from another JAR is not a confirmation")
        void moduleLoadedFromAnotherJar_isRolledBack() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            File stray = moduleJar(new File(dataFolder, "elsewhere/demo-copy.jar"), "Demo", "1.1", "demo");
            UltiToolsPlugin loadedFromStray = loadedModule("Demo", "1.1", "demo");

            start.observeAfterLoad(Collections.singletonList(loadedFromStray),
                    new ModuleUpdateFixtures.CodeSources().with(loadedFromStray, stray));

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLED_BACK);
        }

        @Test
        @DisplayName("a rollback that cannot remove the new JAR now is recorded and finished at the next start, before loading")
        void rollbackThatCannotRemoveTheNewJar_isFinishedAtTheNextStart() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = new ModuleFileTransactions(modules, transactions,
                    new ModuleFileTransactions.FileOps() {
                        @Override
                        public void move(Path from, Path to) throws IOException {
                            ModuleFileTransactions.FileOps.DEFAULT.move(from, to);
                        }

                        @Override
                        public void delete(Path path) throws IOException {
                            throw new java.nio.file.AccessDeniedException(path.toString(), null, "held open");
                        }
                    }, ModuleFileTransactions.CrashPoints.NONE);
            start.applyBeforeLoad();

            observeAbsent(start);

            assertThat(onlyReport(start).getLevel()).isEqualTo(Level.SEVERE);
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLBACK_DEFERRED);
            assertThat(recordText()).contains("\"state\": \"ROLLING_BACK\"");

            ModuleFileTransactions next = transactions();
            next.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(next).getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLBACK_FINISHED);
        }
    }

    @Nested
    @DisplayName("truthful failures")
    class TruthfulFailures {

        private ModuleFileTransactions failingMoveOf(File source) {
            return new ModuleFileTransactions(modules, transactions, new ModuleFileTransactions.FileOps() {
                @Override
                public void move(Path from, Path to) throws IOException {
                    if (from.toFile().getName().equals(source.getName())) {
                        throw new IOException("injected: cannot move " + from.getFileName());
                    }
                    ModuleFileTransactions.FileOps.DEFAULT.move(from, to);
                }

                @Override
                public void delete(Path path) throws IOException {
                    ModuleFileTransactions.FileOps.DEFAULT.delete(path);
                }
            }, ModuleFileTransactions.CrashPoints.NONE);
        }

        @Test
        @DisplayName("an old JAR that cannot be moved aside changes nothing, is reported SEVERE, and is kept as FAILED")
        void oldJarCannotBeMovedAside_nothingChanges() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = failingMoveOf(oldJar);

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
            assertThat(report.getArgs()).contains("Demo", "1.0", "1.1", oldJar.getAbsolutePath());
            assertThat(String.valueOf(report.getArgs()[4])).contains("injected");
            assertThat(recordText()).contains("\"state\": \"FAILED\"");

            observeAbsent(start);
            assertOnlyTheOldVersionIsInstalled();
        }

        @Test
        @DisplayName("on POSIX, a read-only modules folder fails the apply the same way, with nothing changed")
        void readOnlyModulesFolder_nothingChanges() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(modules.toPath()).supportsFileAttributeView("posix"));
            stage(transactions());
            Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                Assumptions.assumeFalse(Files.isWritable(modules.toPath()), "running as a user that ignores permissions");
                ModuleFileTransactions start = transactions();

                start.applyBeforeLoad();

                assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
            } finally {
                Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            assertOnlyTheOldVersionIsInstalled();
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
        }

        @Test
        @DisplayName("a new JAR that cannot be moved in puts the old JAR back, and is reported")
        void newJarCannotBeMovedIn_oldJarIsPutBack() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = failingMoveOf(newJar());

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
        }

        @Test
        @DisplayName("the next /upm update of that module first reports the failed apply, then stages again")
        void nextUpdateReportsTheFailedApply() throws IOException {
            stage(transactions());
            failingMoveOf(oldJar).applyBeforeLoad();

            ModuleFileTransactions.StageResult again = stage(transactions());

            assertThat(again.getPreviousFailure()).isNotNull().contains(oldJar.getAbsolutePath()).contains("injected");
            assertThat(again.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.STAGED);
            assertThat(recordText()).contains("\"state\": \"PENDING\"");
            assertOnlyTheOldVersionIsInstalled();
        }

        @Test
        @DisplayName("a module whose JAR was removed after staging is not installed again by its staged update")
        void moduleRemovedAfterStaging_isNotInstalledAgain() throws IOException {
            stage(transactions());
            Files.delete(oldJar.toPath());
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(namesIn(modules)).isEmpty();
            assertThat(treeOf(transactions)).isEmpty();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.WARNING);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.UPDATE_ABANDONED);
            assertThat(report.getArgs()).containsExactly("Demo", "1.1", oldJar.getAbsolutePath());
        }

        @Test
        @DisplayName("staging never adopts a kept old JAR left without its record: it is refused and the JAR is kept")
        void leftoverKeptJar_isNotAdopted() throws IOException {
            stage(transactions());
            catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_OLD_MOVED).applyBeforeLoad());
            File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
            assertThat(records).hasSize(1);
            Files.delete(records[0].toPath());
            moduleJar(oldJar, "Demo", "1.0", "demo");

            ModuleFileTransactions.StageResult result = stage(transactions());

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_LEFTOVER_BACKUP);
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"))
                    .noneMatch(p -> p.endsWith(".json"));
        }

        @Test
        @DisplayName("a file already at the new JAR's name is never replaced: the apply fails and nothing changes")
        void existingFileAtTheNewName_isNotReplaced() throws IOException {
            stage(transactions());
            byte[] squatter = "someone else's file".getBytes(StandardCharsets.UTF_8);
            Files.write(newJar().toPath(), squatter);
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(namesIn(modules)).containsExactly("demo-1.0.jar", "demo-1.1.jar");
            assertThat(Files.readAllBytes(newJar().toPath())).isEqualTo(squatter);
            assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(oldBytes);
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
        }
    }

    @Nested
    @DisplayName("crash windows -- each next start completes or reverses the transaction, both versions kept until the decision")
    class CrashWindows {

        @Test
        @DisplayName("window 1: after the record is written, before apply -- the next start applies it")
        void crashAfterRecordWritten_nextStartApplies() throws IOException {
            catchThrowable(() -> stage(crashingAt(ModuleFileTransactions.CrashPoints.AFTER_RECORD_WRITTEN)));
            assertOnlyTheOldVersionIsInstalled();
            assertThat(recordText()).contains("\"state\": \"PENDING\"");

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            observeLoaded(start);

            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.COMMITTED);
        }

        @Test
        @DisplayName("window 2: after the old JAR moved aside, before the new one moved in -- the next start completes the apply")
        void crashAfterOldMoved_nextStartCompletesTheApply() throws IOException {
            stage(transactions());
            Throwable crash = catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_OLD_MOVED)
                    .applyBeforeLoad());
            assertThat(crash).isInstanceOf(SimulatedCrash.class);
            assertThat(namesIn(modules)).isEmpty();
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"))
                    .anyMatch(p -> p.endsWith("/staged/demo-1.1.jar"));

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
            observeLoaded(start);

            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.COMMITTED);
        }

        @Test
        @DisplayName("window 3: after the new JAR moved in, before APPLIED is recorded -- the next start recognises it by content")
        void crashAfterNewMoved_nextStartRecognisesTheNewJar() throws IOException {
            stage(transactions());
            Throwable crash = catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_NEW_MOVED)
                    .applyBeforeLoad());
            assertThat(crash).isInstanceOf(SimulatedCrash.class);
            assertThat(recordText()).contains("\"state\": \"PENDING\"");
            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            assertThat(recordText()).contains("\"state\": \"APPLIED\"");
            observeAbsent(start);

            assertOnlyTheOldVersionIsInstalled();
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLED_BACK);
        }

        @Test
        @DisplayName("window 4: after APPLIED, before the decision -- the next start restores the old version before loading")
        void crashAfterAppliedBeforeDecision_nextStartRestoresBeforeLoading() throws IOException {
            stage(transactions());
            Throwable crash = catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_APPLIED_RECORDED)
                    .applyBeforeLoad());
            assertThat(crash).isInstanceOf(SimulatedCrash.class);
            assertThat(recordText()).contains("\"state\": \"APPLIED\"");
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"));

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(treeOf(transactions)).isEmpty();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.UNCONFIRMED_ROLLED_BACK);
            assertThat(report.getArgs()).containsExactly("Demo", "1.1", "1.0", "1.0");
        }

        @Test
        @DisplayName("window 5a: after the commit decision, before cleanup -- the next start finishes the commit")
        void crashAfterCommitDecision_nextStartFinishesTheCommit() throws IOException {
            stage(transactions());
            ModuleFileTransactions crashing = crashingAt(ModuleFileTransactions.CrashPoints.AFTER_DECISION_RECORDED);
            crashing.applyBeforeLoad();
            Throwable crash = catchThrowable(() -> observeLoaded(crashing));
            assertThat(crash).isInstanceOf(SimulatedCrash.class);
            assertThat(recordText()).contains("\"state\": \"COMMITTING\"");
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"));

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();

            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.COMMITTED);
        }

        @Test
        @DisplayName("window 5b: after the rollback decision, before cleanup -- the next start finishes the rollback")
        void crashAfterRollbackDecision_nextStartFinishesTheRollback() throws IOException {
            stage(transactions());
            ModuleFileTransactions crashing = crashingAt(ModuleFileTransactions.CrashPoints.AFTER_DECISION_RECORDED);
            crashing.applyBeforeLoad();
            Throwable crash = catchThrowable(() -> observeAbsent(crashing));
            assertThat(crash).isInstanceOf(SimulatedCrash.class);
            assertThat(recordText()).contains("\"state\": \"ROLLING_BACK\"");
            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");

            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(treeOf(transactions)).isEmpty();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.ROLLBACK_FINISHED);
        }
    }

    @Nested
    @DisplayName("a decision that cannot be recorded")
    class UnrecordableDecision {

        @Test
        @DisplayName("a commit whose decision cannot be written keeps both versions, and the next start restores the old one")
        void commitThatCannotBeRecorded_keepsBothVersions() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(transactions.toPath().getParent()).supportsFileAttributeView("posix"));
            stage(transactions());
            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            Files.setPosixFilePermissions(transactions.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                Assumptions.assumeFalse(Files.isWritable(transactions.toPath()), "running as a user that ignores permissions");

                observeLoaded(start);

                assertThat(start.pendingReports()).extracting(ModuleFileTransactions.Report::getKey)
                        .doesNotContain(ModuleFileTransactions.Keys.COMMITTED)
                        .contains(ModuleFileTransactions.Keys.RECORD_WRITE_FAILED);
                assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"));
            } finally {
                Files.setPosixFilePermissions(transactions.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            ModuleFileTransactions next = transactions();
            next.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(next).getKey()).isEqualTo(ModuleFileTransactions.Keys.UNCONFIRMED_ROLLED_BACK);
        }
    }

    @Nested
    @DisplayName("confinement of record paths")
    class Confinement {

        private File recordFile() {
            File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
            assertThat(records).isNotNull().hasSize(1);
            return records[0];
        }

        private void rewriteRecord(String field, String value) throws IOException {
            File record = recordFile();
            String text = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8);
            String rewritten = text.replaceFirst("\"" + field + "\": \"[^\"]*\"",
                    "\"" + field + "\": \"" + value.replace("\\", "\\\\\\\\") + "\"");
            assertThat(rewritten).isNotEqualTo(text);
            Files.write(record.toPath(), rewritten.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("a working folder whose backup folder is a link leaving it is refused and moves nothing")
        void linkedBackupFolder_isRefused() throws IOException {
            stage(transactions());
            File[] work = transactions.listFiles(File::isDirectory);
            assertThat(work).hasSize(1);
            File outside = new File(dataFolder, "elsewhere");
            moduleJar(new File(outside, "demo-1.0.jar"), "Demo", "0.9", "demo");
            try {
                Files.createSymbolicLink(new File(work[0], "backup").toPath(), outside.toPath());
            } catch (UnsupportedOperationException | IOException e) {
                Assumptions.abort("symbolic links are not available here: " + e);
            }
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(new File(outside, "demo-1.0.jar")).exists();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORD_REFUSED);
        }

        @Test
        @DisplayName("a record naming a file that is not a JAR is refused and moves nothing")
        void nameThatIsNotAJar_isRefused() throws IOException {
            stage(transactions());
            rewriteRecord("targetName", "demo-1.1.txt");
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORD_REFUSED);
        }

        @Test
        @DisplayName("a record missing a required field is refused with a warning, and the start goes on")
        void recordMissingAField_isRefused() throws IOException {
            stage(transactions());
            File record = recordFile();
            String text = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8);
            Files.write(record.toPath(), text.replaceFirst("\\s*\"newVersion\": \"[^\"]*\",", "")
                    .getBytes(StandardCharsets.UTF_8));
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();
            observeAbsent(start);

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORD_REFUSED);
        }

        @Test
        @DisplayName("an unconfirmed record with no kept old JAR never deletes the file it names")
        void unconfirmedRecordWithoutBackup_neverDeletesItsTarget() throws IOException {
            stage(transactions());
            rewriteRecord("state", "APPLIED");
            rewriteRecord("targetName", "demo-1.0.jar");
            rewriteRecord("stagedSha256", ModuleFileTransactions.sha256Of(oldJar));
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(start.pendingReports()).extracting(ModuleFileTransactions.Report::getKey)
                    .doesNotContain(ModuleFileTransactions.Keys.UNCONFIRMED_ROLLED_BACK);
        }

        @Test
        @DisplayName("a record naming ../ is refused with a warning and moves nothing")
        void parentTraversal_isRefused() throws IOException {
            stage(transactions());
            File outside = moduleJar(new File(dataFolder, "outside.jar"), "Other", "1.0", "other");
            byte[] outsideBytes = Files.readAllBytes(outside.toPath());
            rewriteRecord("oldName", "../outside.jar");
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(Files.readAllBytes(outside.toPath())).isEqualTo(outsideBytes);
            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getLevel()).isEqualTo(Level.WARNING);
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORD_REFUSED);
        }

        @Test
        @DisplayName("a record naming a link that leaves the modules folder is refused and moves nothing")
        void linkLeavingTheFolder_isRefused() throws IOException {
            stage(transactions());
            File outside = moduleJar(new File(dataFolder, "outside.jar"), "Other", "1.0", "other");
            Path link = new File(modules, "link.jar").toPath();
            try {
                Files.createSymbolicLink(link, outside.toPath());
            } catch (UnsupportedOperationException | IOException e) {
                Assumptions.abort("symbolic links are not available here: " + e);
            }
            rewriteRecord("oldName", "link.jar");
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(outside).exists();
            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(namesIn(modules)).containsExactly("demo-1.0.jar", "link.jar");
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORD_REFUSED);
        }
    }
}
