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
import java.nio.file.AtomicMoveNotSupportedException;
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

    @TempDir
    File serverRoot;

    /** {@code <server root>/plugins/UltiTools}, as on a real server; the records live under the server root. */
    private File dataFolder;

    private File modules;
    private File transactions;
    private File oldJar;
    private byte[] oldBytes;
    private UltiToolsPlugin loadedOld;

    /** What a crash looks like to the code: the thread stops at that point. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String point) {
            super("crash at " + point);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
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

        /**
         * The modules folder and the records folder on two file systems: every move between them is
         * refused the way the JDK refuses an atomic move across devices.
         */
        private ModuleFileTransactions acrossFileSystems() {
            Path modulesPath = modules.toPath();
            return new ModuleFileTransactions(modules, transactions, new ModuleFileTransactions.FileOps() {
                @Override
                public void move(Path from, Path to) throws IOException {
                    if (from.startsWith(modulesPath) != to.startsWith(modulesPath)) {
                        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(),
                                "Invalid cross-device link");
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
        @DisplayName("plugins/ on another file system: nothing in the modules folder changes, FAILED, one SEVERE line names both folders")
        void modulesFolderOnAnotherFileSystem_failsTruthfully() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = acrossFileSystems();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_CROSS_FILE_SYSTEM);
            assertThat(report.getArgs()).containsExactly("Demo", "1.0", "1.1", modules.getAbsolutePath(),
                    transactions.getAbsolutePath(), "1.0");
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
            // No copy fallback: the staged JAR is still only in the records folder.
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/staged/demo-1.1.jar"))
                    .noneMatch(p -> p.endsWith("/backup/demo-1.0.jar"));

            observeAbsent(start);
            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.StageResult again = stage(transactions());
            assertThat(again.getPreviousFailure()).isNotNull().contains(modules.getAbsolutePath())
                    .contains(transactions.getAbsolutePath());
        }

        @Test
        @DisplayName("on POSIX, a read-only modules folder fails the apply the same way, with nothing changed")
        void readOnlyModulesFolder_nothingChanges() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(modules.toPath()).supportsFileAttributeView("posix"));
            stage(transactions());
            Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("r-x------"));
            try {
                Assumptions.assumeFalse(Files.isWritable(modules.toPath()), "running as a user that ignores permissions");
                ModuleFileTransactions start = transactions();

                start.applyBeforeLoad();

                assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
            } finally {
                Files.setPosixFilePermissions(modules.toPath(), PosixFilePermissions.fromString("rwx------"));
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
        @DisplayName("an old JAR replaced after staging is never moved aside or deleted: the update is abandoned (Codex P2, #561)")
        void oldJarReplacedAfterStaging_isKeptAndTheUpdateAbandoned() throws IOException {
            stage(transactions());
            // For example /upm install of the current version, or a hand-made hotfix under the same name.
            byte[] replacement = Files.readAllBytes(moduleJar(oldJar, "Demo", "1.0-hotfix", "demo").toPath());
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();
            observeLoaded(start);

            assertThat(namesIn(modules)).containsExactly("demo-1.0.jar");
            assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(replacement);
            assertThat(treeOf(transactions)).isEmpty();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.WARNING);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.UPDATE_ABANDONED_REPLACED);
            assertThat(report.getArgs()).containsExactly("Demo", "1.1", oldJar.getAbsolutePath());
        }

        @Test
        @DisplayName("the same bytes put back under the old name are still the old JAR: the update applies")
        void oldJarRewrittenWithTheSameBytes_stillApplies() throws IOException {
            stage(transactions());
            Files.write(oldJar.toPath(), oldBytes);
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
            assertThat(recordText()).contains("\"state\": \"APPLIED\"");
        }

        @Test
        @DisplayName("a records folder that exists but cannot be listed is reported SEVERE, not treated as empty (Codex P2, #561)")
        void unlistableRecordsFolder_isReported() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
            stage(transactions());
            java.util.Set<java.nio.file.attribute.PosixFilePermission> original =
                    Files.getPosixFilePermissions(transactions.toPath());
            Files.setPosixFilePermissions(transactions.toPath(), PosixFilePermissions.fromString("-wx------"));
            ModuleFileTransactions start;
            try {
                Assumptions.assumeTrue(transactions.list() == null, "running as a user that ignores permissions");
                start = transactions();

                start.applyBeforeLoad();
            } finally {
                Files.setPosixFilePermissions(transactions.toPath(), original);
            }

            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORDS_UNLISTABLE);
            assertThat(report.getArgs()[0]).isEqualTo(transactions.getAbsolutePath());
            assertThat(recordText()).contains("\"state\": \"PENDING\"");
        }

        @Test
        @DisplayName("a records folder whose parent cannot be searched is reported the same way, not taken as absent")
        void unsearchableUltikitsFolder_isReported() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
            stage(transactions());
            Path ultikits = transactions.getParentFile().toPath();
            java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(ultikits);
            Files.setPosixFilePermissions(ultikits, PosixFilePermissions.fromString("rw-------"));
            ModuleFileTransactions start;
            try {
                Assumptions.assumeTrue(transactions.list() == null, "running as a user that ignores permissions");
                start = transactions();

                start.applyBeforeLoad();
            } finally {
                Files.setPosixFilePermissions(ultikits, original);
            }

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORDS_UNLISTABLE);
        }

        /** Leaves a kept old JAR whose record is gone, as a crash after the old JAR moved and a lost record would. */
        private File keptBackupWithoutRecord() throws IOException {
            stage(transactions());
            catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_OLD_MOVED).applyBeforeLoad());
            File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
            assertThat(records).hasSize(1);
            Files.delete(records[0].toPath());
            File[] backups = transactions.listFiles(File::isDirectory);
            assertThat(backups).hasSize(1);
            return new File(backups[0], "backup");
        }

        @Test
        @DisplayName("staging refuses when a kept-JAR folder exists but cannot be listed, as when it lists a JAR")
        void unlistableKeptFolder_isNotAdopted() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
            File backups = keptBackupWithoutRecord();
            moduleJar(oldJar, "Demo", "1.0", "demo");
            java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(backups.toPath());
            Files.setPosixFilePermissions(backups.toPath(), PosixFilePermissions.fromString("-wx------"));
            ModuleFileTransactions.StageResult result;
            try {
                Assumptions.assumeTrue(backups.list() == null, "running as a user that ignores permissions");

                result = stage(transactions());
            } finally {
                Files.setPosixFilePermissions(backups.toPath(), original);
            }

            assertThat(result.getOutcome()).isEqualTo(ModuleFileTransactions.StageResult.Outcome.FAILED);
            assertThat(result.getReasonKey()).isEqualTo(ModuleFileTransactions.Keys.REASON_LEFTOVER_BACKUP);
            assertThat(new File(backups, "demo-1.0.jar")).exists();
        }

        @Test
        @DisplayName("a start never deletes an orphan working folder whose kept-JAR folder cannot be listed; it reports it")
        void unlistableOrphanKeptFolder_isReportedNotDeleted() throws IOException {
            Assumptions.assumeTrue(Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
            File backups = keptBackupWithoutRecord();
            java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(backups.toPath());
            Files.setPosixFilePermissions(backups.toPath(), PosixFilePermissions.fromString("-wx------"));
            ModuleFileTransactions start;
            try {
                Assumptions.assumeTrue(backups.list() == null, "running as a user that ignores permissions");
                start = transactions();

                start.applyBeforeLoad();
            } finally {
                Files.setPosixFilePermissions(backups.toPath(), original);
            }

            assertThat(new File(backups, "demo-1.0.jar")).exists();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.ORPHAN_BACKUP);
            assertThat(report.getArgs()[0]).isEqualTo(backups.getAbsolutePath());
        }

        /**
         * The records folder as a security policy that denies listing it would present it: every
         * listing throws {@link SecurityException}. {@code recordsListable} keeps the record
         * listing working so that only the later listings are denied.
         */
        @SuppressWarnings("serial")
        private File deniedListing(boolean recordsListable) {
            return new File(transactions.getPath()) {
                @Override
                public File[] listFiles(java.io.FilenameFilter filter) {
                    if (recordsListable) {
                        return super.listFiles(filter);
                    }
                    throw new SecurityException("injected: listing denied");
                }

                @Override
                public File[] listFiles(java.io.FileFilter filter) {
                    throw new SecurityException("injected: listing denied");
                }

                @Override
                public String[] list() {
                    throw new SecurityException("injected: listing denied");
                }
            };
        }

        @Test
        @DisplayName("a security policy that denies listing the records folder is reported, never thrown out of the start (Codex P2, #561)")
        void deniedListing_isReportedNotThrown() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = new ModuleFileTransactions(modules, deniedListing(false),
                    ModuleFileTransactions.FileOps.DEFAULT, ModuleFileTransactions.CrashPoints.NONE);

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertThat(thrown).isNull();
            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.RECORDS_UNLISTABLE);
            assertThat(String.valueOf(report.getArgs()[1])).contains("injected");
            assertThat(recordText()).contains("\"state\": \"PENDING\"");
        }

        @Test
        @DisplayName("a listing denied after the records were read never ends the start: the staged update still applies")
        void deniedCleanupListing_neverEndsTheStart() throws IOException {
            stage(transactions());
            ModuleFileTransactions start = new ModuleFileTransactions(modules, deniedListing(true),
                    ModuleFileTransactions.FileOps.DEFAULT, ModuleFileTransactions.CrashPoints.NONE);

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertThat(thrown).isNull();
            assertThat(namesIn(modules)).containsExactly("demo-1.1.jar");
            assertThat(recordText()).contains("\"state\": \"APPLIED\"");
        }

        @Test
        @DisplayName("no records folder at all is not a failure: a start with nothing staged reports nothing")
        void absentRecordsFolder_isNotReported() {
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertThat(transactions).doesNotExist();
            assertThat(start.pendingReports()).isEmpty();
        }

        /** The staged JAR of the one staged update, found in the records folder. */
        private File stagedJar() throws IOException {
            List<String> staged = new java.util.ArrayList<>();
            for (String path : treeOf(transactions)) {
                if (path.endsWith("/staged/demo-1.1.jar")) {
                    staged.add(path);
                }
            }
            assertThat(staged).hasSize(1);
            return new File(transactions, staged.get(0));
        }

        @Test
        @DisplayName("a staged JAR changed after staging moves nothing: FAILED, one SEVERE line names the path and both hashes (round 4)")
        void stagedJarChangedAfterStaging_nothingMoves() throws IOException {
            stage(transactions());
            File staged = stagedJar();
            String expected = ModuleFileTransactions.sha256Of(staged);
            // Same identity and version, different bytes: not the file that was downloaded.
            moduleJar(staged, "Demo", "1.1", "demo");
            Files.write(staged.toPath(), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
            String actual = ModuleFileTransactions.sha256Of(staged);
            assertThat(actual).isNotEqualTo(expected);
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();
            observeAbsent(start);

            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.STAGED_JAR_MISMATCH);
            assertThat(report.getArgs()).containsExactly("Demo", staged.getAbsolutePath(), expected, actual);
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
            assertThat(ModuleFileTransactions.sha256Of(staged)).isEqualTo(actual);
        }

        @Test
        @DisplayName("a missing staged JAR moves nothing and is reported with the expected hash")
        void stagedJarMissing_nothingMoves() throws IOException {
            stage(transactions());
            File staged = stagedJar();
            String expected = ModuleFileTransactions.sha256Of(staged);
            Files.delete(staged.toPath());
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            ModuleFileTransactions.Report report = onlyReport(start);
            assertThat(report.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(report.getKey()).isEqualTo(ModuleFileTransactions.Keys.STAGED_JAR_MISMATCH);
            assertThat(report.getArgs()).containsExactly("Demo", staged.getAbsolutePath(), expected,
                    ModuleFileTransactions.MISSING);
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
        }

        @Test
        @DisplayName("a staged JAR changed after the old JAR was moved aside: the old JAR goes back, FAILED, reported")
        void stagedJarChangedAfterOldMoved_oldJarGoesBack() throws IOException {
            stage(transactions());
            File staged = stagedJar();
            catchThrowable(() -> crashingAt(ModuleFileTransactions.CrashPoints.AFTER_OLD_MOVED).applyBeforeLoad());
            assertThat(namesIn(modules)).isEmpty();
            Files.write(staged.toPath(), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
            ModuleFileTransactions start = transactions();

            start.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(start).getKey()).isEqualTo(ModuleFileTransactions.Keys.STAGED_JAR_MISMATCH);
            assertThat(recordText()).contains("\"state\": \"FAILED\"");
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
            Assumptions.assumeTrue(Files.getFileStore(serverRoot.toPath()).supportsFileAttributeView("posix"));
            stage(transactions());
            ModuleFileTransactions start = transactions();
            start.applyBeforeLoad();
            Files.setPosixFilePermissions(transactions.toPath(), PosixFilePermissions.fromString("r-x------"));
            try {
                Assumptions.assumeFalse(Files.isWritable(transactions.toPath()), "running as a user that ignores permissions");

                observeLoaded(start);

                assertThat(start.pendingReports()).extracting(ModuleFileTransactions.Report::getKey)
                        .doesNotContain(ModuleFileTransactions.Keys.COMMITTED)
                        .contains(ModuleFileTransactions.Keys.RECORD_WRITE_FAILED);
                assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/backup/demo-1.0.jar"));
            } finally {
                Files.setPosixFilePermissions(transactions.toPath(), PosixFilePermissions.fromString("rwx------"));
            }

            ModuleFileTransactions next = transactions();
            next.applyBeforeLoad();

            assertOnlyTheOldVersionIsInstalled();
            assertThat(onlyReport(next).getKey()).isEqualTo(ModuleFileTransactions.Keys.UNCONFIRMED_ROLLED_BACK);
        }
    }

    @Nested
    @DisplayName("an unchecked exception in one transaction's apply (round 4, orchestrator decision)")
    class UncheckedExceptionInOneTransaction {

        private File otherOldJar;
        private UltiToolsPlugin otherLoaded;

        @BeforeEach
        void stageTwoModules() throws IOException {
            otherOldJar = moduleJar(new File(modules, "other-1.0.jar"), "Other", "1.0", "other");
            otherLoaded = loadedModule("Other", "1.0", "other");
            stage(transactions());
            transactions().stageUpdate("other", Collections.singletonList(otherLoaded),
                    new ModuleUpdateFixtures.CodeSources().with(otherLoaded, otherOldJar), catalogue("other", "1.1"),
                    downloading("Other", "1.1", "other"));
        }

        /** File operations that throw {@code failure} from the existence check of a {@code demo} file. */
        private ModuleFileTransactions.FileOps failingExistenceCheck(RuntimeException failure) {
            return new ModuleFileTransactions.FileOps() {
                @Override
                public void move(Path from, Path to) throws IOException {
                    ModuleFileTransactions.FileOps.DEFAULT.move(from, to);
                }

                @Override
                public void delete(Path path) throws IOException {
                    ModuleFileTransactions.FileOps.DEFAULT.delete(path);
                }

                @Override
                public boolean exists(Path path) {
                    if (path.getFileName().toString().startsWith("demo")) {
                        throw failure;
                    }
                    return ModuleFileTransactions.FileOps.DEFAULT.exists(path);
                }
            };
        }

        /** A start that throws {@code failure} once, at {@code point}, while demo's transaction is at it. */
        private ModuleFileTransactions throwingAt(String point, String demoFileThere, RuntimeException failure) {
            java.util.concurrent.atomic.AtomicBoolean thrown = new java.util.concurrent.atomic.AtomicBoolean();
            return new ModuleFileTransactions(modules, transactions, ModuleFileTransactions.FileOps.DEFAULT, reached -> {
                if (reached.equals(point) && new File(modules, "demo-1.0.jar").exists() == "demo-1.0.jar".equals(demoFileThere)
                        && new File(modules, "demo-1.1.jar").exists() == "demo-1.1.jar".equals(demoFileThere)
                        && thrown.compareAndSet(false, true)) {
                    throw failure;
                }
            });
        }

        private void assertDemoFailedOtherApplied(ModuleFileTransactions start, Throwable thrown) throws IOException {
            assertThat(thrown).isNull();
            assertThat(namesIn(modules)).containsExactlyInAnyOrder("demo-1.0.jar", "other-1.1.jar");
            assertThat(Files.readAllBytes(oldJar.toPath())).isEqualTo(oldBytes);
            List<ModuleFileTransactions.Report> severe = new java.util.ArrayList<>();
            for (ModuleFileTransactions.Report report : start.pendingReports()) {
                if (report.getLevel() == Level.SEVERE) {
                    severe.add(report);
                }
            }
            assertThat(severe).hasSize(1);
            assertThat(severe.get(0).getKey()).isEqualTo(ModuleFileTransactions.Keys.APPLY_FAILED);
            assertThat(severe.get(0).getArgs()[0]).isEqualTo("Demo");
            assertThat(String.valueOf(severe.get(0).getArgs()[4])).contains("injected");
            File[] records = transactions.listFiles((dir, name) -> name.endsWith(".json"));
            assertThat(records).isNotNull();
            List<String> states = new java.util.ArrayList<>();
            for (File record : records) {
                String text = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8);
                states.add((text.contains("\"moduleName\": \"Demo\"") ? "Demo " : "Other ")
                        + text.replaceAll("(?s).*\"state\": \"([A-Z_]+)\".*", "$1"));
            }
            assertThat(states).containsExactlyInAnyOrder("Demo FAILED", "Other APPLIED");
        }

        @Test
        @DisplayName("a SecurityException from the existence check before any move: startup continues, FAILED, nothing moved")
        void securityExceptionBeforeAnyMove() throws IOException {
            ModuleFileTransactions start = new ModuleFileTransactions(modules, transactions,
                    failingExistenceCheck(new SecurityException("injected: access denied")),
                    ModuleFileTransactions.CrashPoints.NONE);

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertDemoFailedOtherApplied(start, thrown);
        }

        @Test
        @DisplayName("a plain RuntimeException from the existence check before any move: the same")
        void runtimeExceptionBeforeAnyMove() throws IOException {
            ModuleFileTransactions start = new ModuleFileTransactions(modules, transactions,
                    failingExistenceCheck(new IllegalStateException("injected: unexpected")),
                    ModuleFileTransactions.CrashPoints.NONE);

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertDemoFailedOtherApplied(start, thrown);
        }

        @Test
        @DisplayName("a SecurityException after the old JAR moved aside: the old JAR goes back, FAILED, startup continues")
        void securityExceptionAfterTheFirstMove() throws IOException {
            ModuleFileTransactions start = throwingAt(ModuleFileTransactions.CrashPoints.AFTER_OLD_MOVED, "none",
                    new SecurityException("injected: access denied"));

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertDemoFailedOtherApplied(start, thrown);
        }

        @Test
        @DisplayName("a RuntimeException after the new JAR moved in: it goes back to the staged folder, the old JAR back in place")
        void runtimeExceptionAfterTheNewJarMoved() throws IOException {
            ModuleFileTransactions start = throwingAt(ModuleFileTransactions.CrashPoints.AFTER_NEW_MOVED, "demo-1.1.jar",
                    new IllegalStateException("injected: unexpected"));

            Throwable thrown = catchThrowable(start::applyBeforeLoad);

            assertDemoFailedOtherApplied(start, thrown);
            assertThat(treeOf(transactions)).anyMatch(p -> p.endsWith("/staged/demo-1.1.jar"));
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
