package com.ultikits.ultitools.interfaces.impl.data.json;

import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.commitName;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.entity;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.journalAt;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.journalDir;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.read;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.recordNames;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.tableFiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Entry;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Kind;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.CapturingHandler;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.JournalEntity;
import com.ultikits.ultitools.manager.JsonTransactionManager;

/**
 * The JSON journal's file I/O against a failing file system (20.4-DECISIONS.md question 10 A;
 * 20.4-PRIOR-ART.md P1 and P3): a move another program briefly holds on Windows is retried within a
 * bound, and a directory force is classified.
 * <p>
 * The Windows behaviour is forced through the move-retry switch, and file-system failures are
 * scripted through the journal's mover and directory-forcer seams; every seam is reset after each
 * test. Table directories sit one level below {@code @TempDir}, so a directly constructed
 * operator's sibling journal stays inside it.
 */
@Timeout(60)
class JsonJournalFileFailureTest {

    private static final String USERS = "users";

    /** Bounded wait for work handed to another thread. */
    private static final long HANDSHAKE_SECONDS = 10;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpBukkit() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @AfterEach
    void resetSeams() {
        JsonJournal.resetMover();
        JsonJournal.resetMoveRetry();
        JsonJournal.resetDirectoryForcer();
        JsonJournal.resetStepHook();
    }

    // ===== Move retry (question 10 A, P1) =====

    @Nested
    @DisplayName("A journal move another program briefly holds is retried on Windows, within a bound")
    class MoveRetryTests {

        @Test
        @DisplayName("Tracer: a commit whose record publish is denied twice commits, is visible and survives a reopen")
        void commitRetriesARecordPublishHeldByAnotherProgram() throws Exception {
            Path table = tableDirectory("tracer");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            ScriptedMover mover = ScriptedMover.forTargets(isCommittedRecord(),
                    accessDenied(), accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            JsonTransactionManager manager = operator.transactionManager();
            Throwable thrown = catchThrowable(() -> {
                manager.begin();
                operator.insert(journalEntity("p1", 7));
                manager.commit();
            });
            if (thrown != null && manager.hasActiveTransaction()) {
                manager.rollback();
            }

            assertThat(thrown).as("commit() returns").isNull();
            assertThat(mover.attempts()).as("record publish attempts").isEqualTo(3);
            assertThat(committedRecords(table)).as("the committed record").hasSize(1);
            assertThat(valueSeenByAnotherThread(operator, "p1")).as("another thread reads the commit").isEqualTo(7);

            JsonJournal.resetMover();
            SimpleJsonDataOperator<JournalEntity> reopened = operatorOver(table);
            assertThat(reopened.getById("p1")).as("the value after a reopen").isNotNull();
            assertThat(reopened.getById("p1").getValue()).isEqualTo(7);
        }

        @Test
        @DisplayName("A record publish that meets a sharing violation (a plain FileSystemException) is retried")
        void recordPublishRetriesASharingViolation() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            ScriptedMover mover = ScriptedMover.forTargets(isCommittedRecord(),
                    sharingViolation(), sharingViolation());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("a", 1)));

            assertThat(thrown).as("writeRecord returns").isNull();
            assertThat(mover.attempts()).isEqualTo(3);
            assertThat(recordNames(root)).containsExactly(commitName(1));
        }

        @Test
        @DisplayName("A checkpoint whose entity move is denied once completes and the table file holds the value")
        void checkpointEntityMoveRetries() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, oneUser("x", 1));
            ScriptedMover mover = ScriptedMover.forTargets(isIn(root.resolve(USERS)), accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            Throwable thrown = catchThrowable(() -> journal.checkpoint(2L, oneUser("x", 2)));

            assertThat(thrown).as("the checkpoint completes").isNull();
            assertThat(mover.attempts()).isEqualTo(2);
            assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 2));
            assertThat(recordNames(root)).as("truncated").isEmpty();
        }

        @Test
        @DisplayName("A recovery whose entity move is denied once completes and deletes the applied record")
        void recoveryEntityMoveRetries() throws Exception {
            Path root = tempDir.resolve("tables");
            journalAt(root).writeRecord(1L, Kind.COMMIT, oneUser("r", 1));
            ScriptedMover mover = ScriptedMover.forTargets(isIn(root.resolve(USERS)), accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            Throwable thrown = catchThrowable(() -> journalAt(root).recover());

            assertThat(thrown).as("recovery completes").isNull();
            assertThat(mover.attempts()).isEqualTo(2);
            assertThat(read(root.resolve(USERS).resolve("r.json"))).isEqualTo(entity("r", 1));
            assertThat(recordNames(root)).as("the applied record is deleted").isEmpty();
        }

        @Test
        @DisplayName("The plain move after an unsupported atomic move is retried the same way")
        void fallbackMoveRetries() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            ScriptedMover mover = ScriptedMover.forTargets(isCommittedRecord(),
                    () -> new AtomicMoveNotSupportedException(null, null, "simulated: no atomic move"),
                    accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("f", 1)));

            assertThat(thrown).as("the fallback move completes").isNull();
            assertThat(mover.attempts()).as("atomic attempt, denied fallback, fallback retry").isEqualTo(3);
            assertThat(recordNames(root)).containsExactly(commitName(1));
        }

        @Test
        @DisplayName("A move that is always denied is attempted 5 times within a second, then fails and leaves nothing behind")
        void retriesAreBounded() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            ScriptedMover mover = ScriptedMover.alwaysFailing(isCommittedRecord(), accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);

            long started = System.nanoTime();
            Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("b", 1)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(mover.attempts()).as("attempts").isEqualTo(5);
            assertThat(elapsedMillis).as("elapsed milliseconds").isLessThan(1000L);
            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(Arrays.stream(thrown.getSuppressed()).filter(AccessDeniedException.class::isInstance).count())
                    .as("the earlier failures, attached as suppressed").isEqualTo(4L);
            assertThat(recordNames(root)).as("no temporary record is left").isEmpty();

            Path table = tableDirectory("bounded");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            JsonTransactionManager manager = operator.transactionManager();
            Throwable commitFailure = catchThrowable(() -> operator.transaction(
                    () -> operator.insert(journalEntity("lost", 1))));

            assertThat(commitFailure).isInstanceOf(DataAccessException.class);
            assertThat(((DataAccessException) commitFailure).getErrorCode()).isEqualTo(ErrorCode.TRANSACTION_FAILED);
            assertThat(manager.hasActiveTransaction()).as("rolled back").isFalse();
            assertThat(operator.getById("lost")).as("nothing committed").isNull();
            assertThat(mover.attempts()).as("the commit's attempts, after the 5 above").isEqualTo(10);
        }

        @Test
        @DisplayName("Characterization: a missing source or an existing target is attempted once")
        void definiteFailuresAreNotRetried() throws Exception {
            List<Supplier<IOException>> definite = Arrays.asList(
                    () -> new NoSuchFileException("simulated missing source"),
                    () -> new FileAlreadyExistsException("simulated existing target"));
            int index = 0;
            for (Supplier<IOException> failure : definite) {
                Path root = tempDir.resolve("definite-" + index++);
                JsonJournal journal = journalAt(root);
                ScriptedMover mover = ScriptedMover.alwaysFailing(isCommittedRecord(), failure);
                JsonJournal.installMover(mover);
                JsonJournal.installMoveRetry(true);

                Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("d", 1)));

                assertThat(thrown).isExactlyInstanceOf(failure.get().getClass());
                assertThat(mover.attempts()).as(thrown.getClass().getSimpleName()).isEqualTo(1);
                assertThat(recordNames(root)).isEmpty();
            }
        }

        @Test
        @DisplayName("Characterization: with retries off (any other operating system) a denied move is attempted once")
        void offWindowsAMoveIsAttemptedOnce() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            ScriptedMover mover = ScriptedMover.alwaysFailing(isCommittedRecord(), accessDenied());
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(false);

            Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("o", 1)));

            assertThat(thrown).isInstanceOf(AccessDeniedException.class);
            assertThat(mover.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("Characterization: an interrupt stops the retries, keeps the interrupt flag and reports the move failure")
        void anInterruptStopsTheRetries() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            ScriptedMover mover = ScriptedMover.alwaysFailing(isCommittedRecord(), () -> {
                Thread.currentThread().interrupt();
                return new AccessDeniedException("simulated: held by another program");
            });
            JsonJournal.installMover(mover);
            JsonJournal.installMoveRetry(true);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptedAfter = new AtomicBoolean();

            Thread thread = new Thread(() -> {
                thrown.set(catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("i", 1))));
                interruptedAfter.set(Thread.interrupted());
            }, "json-journal-interrupt");
            thread.setDaemon(true);
            thread.start();
            thread.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));

            assertThat(thread.isAlive()).as("the call finished").isFalse();
            assertThat(thrown.get()).isInstanceOf(AccessDeniedException.class);
            assertThat(mover.attempts()).isEqualTo(1);
            assertThat(interruptedAfter.get()).as("the interrupt flag is kept").isTrue();
        }

        @Test
        @DisplayName("Retries are on exactly when os.name names Windows")
        void retryFollowsTheOperatingSystemName() {
            assertThat(JsonJournal.retriesMovesOn("Windows 11")).isTrue();
            assertThat(JsonJournal.retriesMovesOn("Windows Server 2022")).isTrue();
            assertThat(JsonJournal.retriesMovesOn("Linux")).isFalse();
            assertThat(JsonJournal.retriesMovesOn("Mac OS X")).isFalse();
            assertThat(JsonJournal.retriesMovesOn(null)).isFalse();
        }
    }

    // ===== Directory force classification (question 10 A, P3) =====

    @Nested
    @DisplayName("A directory that cannot be opened is skipped; any other directory-force failure is a WARNING that never lets records go")
    class DirectoryForceFailureTests {

        @Test
        @DisplayName("Characterization: a directory that cannot be opened (access denied, unsupported) is skipped, logged once at FINE")
        void aDirectoryThatCannotBeOpenedIsSkipped() throws Exception {
            List<JsonJournal.DirectoryForcer> forcers = Arrays.asList(
                    directory -> {
                        throw new AccessDeniedException(directory.toString(), null, "simulated: cannot open");
                    },
                    directory -> {
                        throw new UnsupportedOperationException("simulated: cannot open a directory");
                    });
            int index = 0;
            for (JsonJournal.DirectoryForcer forcer : forcers) {
                Path root = tempDir.resolve("cannot-open-" + index++);
                AtomicInteger calls = new AtomicInteger();
                JsonJournal.installDirectoryForcer(directory -> {
                    calls.incrementAndGet();
                    forcer.force(directory);
                });
                JsonJournal journal = journalAt(root);

                try (JournalLog log = JournalLog.capture()) {
                    assertThat(catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("a", 1))))
                            .as("commit").isNull();
                    assertThat(catchThrowable(() -> journal.checkpoint(2L, oneUser("a", 2))))
                            .as("checkpoint").isNull();
                    journal.writeRecord(3L, Kind.COMMIT, oneUser("a", 3));
                    assertThat(catchThrowable(journal::recover)).as("recovery").isNull();

                    assertThat(calls.get()).as("directory forces attempted").isGreaterThan(1);
                    assertThat(log.withThrown(Level.FINE)).as("FINE records with the failure").hasSize(1);
                    assertThat(log.atOrAbove(Level.WARNING)).as("WARNING records").isEmpty();
                }
                assertThat(read(root.resolve(USERS).resolve("a.json"))).isEqualTo(entity("a", 3));
                assertThat(recordNames(root)).isEmpty();
            }
        }

        @Test
        @DisplayName("A commit whose journal-directory force fails after the rename still commits, with one WARNING")
        void aCommitWhoseJournalForceFailsStillCommits() throws Exception {
            Path root = tempDir.resolve("tables");
            IOException diskError = new IOException("simulated disk error");
            JsonJournal.installDirectoryForcer(failingFor(journalDir(root), diskError));
            JsonJournal journal = journalAt(root);

            try (JournalLog log = JournalLog.capture()) {
                Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("c", 1)));

                assertThat(thrown).as("writeRecord(COMMIT) returns").isNull();
                assertThat(recordNames(root)).containsExactly(commitName(1));
                assertOneWarning(log, journalDir(root), diskError);
            }

            Path table = tableDirectory("commit-force");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            IOException managerDiskError = new IOException("simulated disk error under the manager");
            JsonJournal.installDirectoryForcer(failingFor(siblingJournal(table), managerDiskError));
            JsonTransactionManager manager = operator.transactionManager();
            try (JournalLog log = JournalLog.capture()) {
                Throwable thrown = catchThrowable(() -> {
                    manager.begin();
                    operator.insert(journalEntity("c1", 5));
                    manager.commit();
                });
                if (thrown != null && manager.hasActiveTransaction()) {
                    manager.rollback();
                }

                assertThat(thrown).as("commit() returns").isNull();
                assertThat(valueSeenByAnotherThread(operator, "c1")).as("another thread reads it").isEqualTo(5);
                assertOneWarning(log, siblingJournal(table), managerDiskError);
            }
            JsonJournal.resetDirectoryForcer();
            SimpleJsonDataOperator<JournalEntity> reopened = operatorOver(table);
            assertThat(reopened.getById("c1")).as("present after a reopen").isNotNull();
            assertThat(reopened.getById("c1").getValue()).isEqualTo(5);
        }

        @Test
        @DisplayName("A checkpoint whose record's directory force fails stops before any entity file; flush keeps its dirty marks")
        void aCheckpointStopsWhenItsRecordsDirectoryForceFails() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, oneUser("x", 1));
            Map<String, String> before = tableFiles(root);
            failJournalForceFromStep(journalDir(root), "checkpoint.renamed");

            Throwable thrown = catchThrowable(() -> journal.checkpoint(2L, oneUser("x", 2)));

            assertThat(thrown).as("the checkpoint fails").isInstanceOf(IOException.class);
            assertThat(tableFiles(root)).as("no entity file of the checkpoint").isEqualTo(before);
            assertThat(recordNames(root)).as("every record at or below K").containsExactly(commitName(1), commitName(2));

            JsonJournal.resetStepHook();
            JsonJournal.resetDirectoryForcer();
            Path table = tableDirectory("checkpoint-record");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            operator.insert(journalEntity("x", 3));
            failJournalForceFromStep(siblingJournal(table), "checkpoint.renamed");

            assertPersistenceFailure(catchThrowable(operator::flush));
            assertThat(dirtyKeys(operator)).as("the dirty marks stay").containsExactly("x");
            assertThat(table.resolve("x.json")).as("no entity file").doesNotExist();

            JsonJournal.resetStepHook();
            JsonJournal.resetDirectoryForcer();
            operator.flush();
            assertThat(table.resolve("x.json")).as("the next flush writes it").exists();
            assertThat(JsonJournalTest.names(siblingJournal(table)).stream()
                    .filter(name -> name.endsWith(JsonJournal.COMMITTED_SUFFIX) || name.endsWith(JsonJournal.TEMPORARY_SUFFIX))
                    .collect(Collectors.toList())).as("no record at or below the new number").isEmpty();
            assertThat(dirtyKeys(operator)).isEmpty();
        }

        @Test
        @DisplayName("A checkpoint whose table-directory force fails writes its files but deletes no record; the next flush truncates")
        void aCheckpointStopsBeforeTruncatingWhenATableDirectoryForceFails() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, oneUser("x", 1));
            JsonJournal.installDirectoryForcer(failingFor(root.resolve(USERS), new IOException("simulated disk error")));

            Throwable thrown = catchThrowable(() -> journal.checkpoint(2L, oneUser("x", 2)));

            assertThat(thrown).as("the checkpoint fails").isInstanceOf(IOException.class);
            assertThat(read(root.resolve(USERS).resolve("x.json"))).as("entity file written").isEqualTo(entity("x", 2));
            assertThat(recordNames(root)).as("no record deleted").containsExactly(commitName(1), commitName(2));

            JsonJournal.resetDirectoryForcer();
            Path table = tableDirectory("checkpoint-table");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            operator.transaction(() -> operator.insert(journalEntity("t", 1)));
            operator.insert(journalEntity("u", 2));
            JsonJournal.installDirectoryForcer(failingFor(table, new IOException("simulated disk error")));

            assertPersistenceFailure(catchThrowable(operator::flush));
            assertThat(committedRecords(table)).as("no record deleted").hasSize(2);
            assertThat(dirtyKeys(operator)).as("the dirty marks stay").containsExactlyInAnyOrder("t", "u");

            JsonJournal.resetDirectoryForcer();
            operator.flush();
            assertThat(committedRecords(table)).as("the next flush truncates").isEmpty();
            assertThat(dirtyKeys(operator)).isEmpty();
        }

        @Test
        @DisplayName("A checkpoint whose journal-directory force fails after its truncation still reports failure")
        void aCheckpointReportsAFailedForceAfterItsTruncation() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, oneUser("x", 1));
            failJournalForceFromStep(journalDir(root), "checkpoint.betweenTruncations");

            Throwable thrown = catchThrowable(() -> journal.checkpoint(2L, oneUser("x", 2)));

            assertThat(thrown).as("the checkpoint fails").isInstanceOf(IOException.class);
            assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 2));

            JsonJournal.resetStepHook();
            JsonJournal.resetDirectoryForcer();
            Path table = tableDirectory("checkpoint-truncated");
            SimpleJsonDataOperator<JournalEntity> operator = operatorOver(table);
            operator.transaction(() -> operator.insert(journalEntity("a", 1)));
            operator.insert(journalEntity("b", 2));
            failJournalForceFromStep(siblingJournal(table), "checkpoint.betweenTruncations");

            assertPersistenceFailure(catchThrowable(operator::flush));
            assertThat(dirtyKeys(operator)).as("the dirty marks stay").containsExactlyInAnyOrder("a", "b");

            JsonJournal.resetStepHook();
            JsonJournal.resetDirectoryForcer();
            assertThat(catchThrowable(operator::flush)).as("the next flush completes").isNull();
            assertThat(dirtyKeys(operator)).isEmpty();
            assertThat(table.resolve("a.json")).exists();
            assertThat(table.resolve("b.json")).exists();
        }

        @Test
        @DisplayName("A recovery whose table-directory force fails deletes no applied record and keeps the identity closed")
        void recoveryStopsBeforeDeletingAppliedRecords() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, Arrays.asList(
                    Entry.put(USERS, "x", entity("x", 1)), Entry.put(USERS, "y", entity("y", 2))));
            JsonJournal.installDirectoryForcer(failingFor(root.resolve(USERS), new IOException("simulated disk error")));

            Throwable thrown = catchThrowable(() -> journalAt(root).recover());

            assertThat(thrown).as("recovery fails").isInstanceOf(IOException.class);
            assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 1));
            assertThat(read(root.resolve(USERS).resolve("y.json"))).isEqualTo(entity("y", 2));
            assertThat(recordNames(root)).as("the committed record").containsExactly(commitName(1));

            JsonJournal.resetDirectoryForcer();
            Path table = tableDirectory("recovery");
            SimpleJsonDataOperator<JournalEntity> writer = operatorOver(table);
            writer.transaction(() -> writer.insert(journalEntity("r", 9)));
            assertThat(committedRecords(table)).hasSize(1);

            JsonIdentity identity = JsonIdentity.forStandaloneTable(table.toString());
            JsonTransactionManager manager = new JsonTransactionManager(identity);
            JsonJournal.installDirectoryForcer(failingFor(table, new IOException("simulated disk error")));

            assertPersistenceFailure(catchThrowable(() -> operatorOver(table, manager)));
            assertThat(committedRecords(table)).as("the applied record is kept").hasSize(1);
            assertPersistenceFailure(catchThrowable(() -> operatorOver(table, manager)));

            JsonJournal.resetDirectoryForcer();
            SimpleJsonDataOperator<JournalEntity> reader = operatorOver(table, manager);
            assertThat(committedRecords(table)).as("a later request completes recovery").isEmpty();
            assertThat(reader.getById("r")).isNotNull();
            assertThat(reader.getById("r").getValue()).isEqualTo(9);
        }

        @Test
        @DisplayName("A recovery that deletes no committed record forces no directory")
        void aRecoveryThatDeletesNothingForcesNothing() throws Exception {
            Path root = tempDir.resolve("tables");
            Path journalDirectory = Files.createDirectories(journalDir(root).resolve(JsonJournal.TEMP_DIRECTORY_NAME))
                    .getParent();
            Files.write(journalDirectory.resolve(JsonJournal.recordFileName(5L, JsonJournal.TEMPORARY_SUFFIX)),
                    "partial".getBytes(StandardCharsets.UTF_8));
            Files.write(journalDirectory.resolve(JsonJournal.TEMP_DIRECTORY_NAME).resolve("entity-left.part"),
                    "partial".getBytes(StandardCharsets.UTF_8));
            List<Path> forced = new CopyOnWriteArrayList<>();
            JsonJournal.installDirectoryForcer(forced::add);

            journalAt(root).recover();

            assertThat(forced).as("directories forced").isEmpty();
            assertThat(recordNames(root)).as("the temporary record is deleted").isEmpty();
        }
    }

    // ===== Directories the journal creates (review round 1) =====

    @Nested
    @DisplayName("A directory the journal creates is forced into its parent before anything relies on it")
    class NewDirectoryTests {

        @Test
        @DisplayName("The first commit forces every directory it created into its parent, journal directory included")
        void aNewJournalDirectoryIsForcedIntoItsParent() throws Exception {
            Path fresh = tempDir.resolve("fresh");
            Path root = fresh.resolve("tables");
            List<Path> forced = new CopyOnWriteArrayList<>();
            JsonJournal.installDirectoryForcer(forced::add);

            journalAt(root).writeRecord(1L, Kind.COMMIT, oneUser("a", 1));

            assertThat(forced).as("directories forced by the first commit")
                    .contains(tempDir, fresh, root, journalDir(root));
        }

        @Test
        @DisplayName("A table directory a checkpoint or a recovery creates is forced into the identity root before the journal is truncated")
        void aNewTableDirectoryIsForcedBeforeTheJournalIsTruncated() throws Exception {
            Path root = tempDir.resolve("tables");
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, oneUser("x", 1));
            List<String> events = new CopyOnWriteArrayList<>();
            JsonJournal.installDirectoryForcer(directory -> events.add("force " + directory));
            JsonJournal.installStepHook(step -> events.add("step " + step));

            journal.checkpoint(2L, oneUser("x", 2));

            assertThat(events).as("checkpoint events").contains("force " + root);
            assertThat(events.indexOf("force " + root)).as("checkpoint events %s", events)
                    .isLessThan(events.indexOf("step checkpoint.tableDirsForced"));

            Path recovered = tempDir.resolve("recovered");
            journalAt(recovered).writeRecord(1L, Kind.COMMIT, oneUser("y", 1));
            events.clear();

            journalAt(recovered).recover();

            assertThat(events).as("recovery events").contains("force " + recovered);
            assertThat(events.indexOf("force " + recovered)).as("recovery events %s", events)
                    .isLessThan(events.indexOf("step recovery.tableDirsForced"));
            assertThat(read(recovered.resolve(USERS).resolve("y.json"))).isEqualTo(entity("y", 1));
        }

        @Test
        @DisplayName("A failed parent force after a commit is one WARNING; the next checkpoint repeats it and fails until it succeeds")
        void aFailedParentForceAfterACommitIsRepeatedByTheNextCheckpoint() throws Exception {
            Path root = tempDir.resolve("tables");
            IOException diskError = new IOException("simulated disk error in the parent");
            JsonJournal.installDirectoryForcer(failingFor(root, diskError));
            JsonJournal journal = journalAt(root);

            try (JournalLog log = JournalLog.capture()) {
                Throwable thrown = catchThrowable(() -> journal.writeRecord(1L, Kind.COMMIT, oneUser("c", 1)));

                assertThat(thrown).as("writeRecord(COMMIT) returns").isNull();
                assertThat(recordNames(root)).containsExactly(commitName(1));
                assertOneWarning(log, root, diskError);
            }

            Throwable failed = catchThrowable(() -> journal.checkpoint(2L, oneUser("c", 2)));
            assertThat(failed).as("the checkpoint fails while the parent force fails").isInstanceOf(IOException.class);
            assertThat(tableFiles(root)).as("no entity file of the failed checkpoint").isEmpty();

            List<Path> forced = new CopyOnWriteArrayList<>();
            JsonJournal.installDirectoryForcer(forced::add);
            journal.checkpoint(3L, oneUser("c", 3));

            assertThat(forced).as("the next checkpoint forces the parent again").contains(root);
            assertThat(read(root.resolve(USERS).resolve("c.json"))).isEqualTo(entity("c", 3));
            assertThat(recordNames(root)).isEmpty();
        }
    }

    // ===== Fixtures and helpers =====

    /** A forcer that throws {@code failure} for {@code failing} and forces nothing else. */
    private static JsonJournal.DirectoryForcer failingFor(Path failing, IOException failure) {
        return directory -> {
            if (failing.equals(directory)) {
                throw failure;
            }
        };
    }

    /** From the named journal step on, the force of {@code journalDirectory} fails with an I/O error. */
    private static void failJournalForceFromStep(Path journalDirectory, String step) {
        AtomicBoolean failing = new AtomicBoolean();
        JsonJournal.installStepHook(name -> {
            if (name.equals(step)) {
                failing.set(true);
            }
        });
        JsonJournal.installDirectoryForcer(directory -> {
            if (failing.get() && journalDirectory.equals(directory)) {
                throw new IOException("simulated disk error from " + step);
            }
        });
    }

    private static void assertOneWarning(JournalLog log, Path directory, IOException failure) {
        List<LogRecord> warnings = log.atOrAbove(Level.WARNING);
        assertThat(warnings).as("WARNING records").hasSize(1);
        assertThat(warnings.get(0).getMessage()).as("the WARNING names the directory")
                .contains(directory.toAbsolutePath().toString());
        assertThat(warnings.get(0).getThrown()).as("the WARNING carries the failure").isSameAs(failure);
    }

    private static void assertPersistenceFailure(Throwable thrown) {
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getErrorCode()).isEqualTo(ErrorCode.DATA_PERSISTENCE_FAILED);
    }

    /** The keys a checkpoint of {@code operator} would capture now. */
    private static List<Object> dirtyKeys(SimpleJsonDataOperator<?> operator) {
        List<JsonIdentity.Captured> captured = new ArrayList<>();
        operator.captureDirty(captured);
        List<Object> keys = new ArrayList<>();
        for (JsonIdentity.Captured each : captured) {
            keys.add(each.key);
        }
        return keys;
    }

    /** A directly constructed operator's sibling journal directory. */
    private static Path siblingJournal(Path table) {
        return table.resolveSibling(table.getFileName() + JsonJournal.DIRECTORY_NAME);
    }

    private static SimpleJsonDataOperator<JournalEntity> operatorOver(Path table, JsonTransactionManager manager) {
        return new SimpleJsonDataOperator<>(table.toString(), JournalEntity.class, manager, new JsonLockTable());
    }

    /** A table directory one level below {@code @TempDir}; its operator's journal is its sibling. */
    private Path tableDirectory(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name).resolve("rows"));
    }

    private static SimpleJsonDataOperator<JournalEntity> operatorOver(Path table) {
        return new SimpleJsonDataOperator<>(table.toString(), JournalEntity.class);
    }

    /** The committed records of a directly constructed operator's sibling journal. */
    private static List<String> committedRecords(Path table) throws IOException {
        List<String> names = JsonJournalTest.names(siblingJournal(table));
        names.removeIf(name -> !name.endsWith(JsonJournal.COMMITTED_SUFFIX));
        return names;
    }

    private static JournalEntity journalEntity(String id, int value) {
        JournalEntity entity = new JournalEntity();
        entity.setId(id);
        entity.setValue(value);
        return entity;
    }

    /** The value of {@code id} as a read on another thread sees it; {@code null} if absent. */
    private static Integer valueSeenByAnotherThread(SimpleJsonDataOperator<JournalEntity> operator, String id)
            throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            return other.submit(() -> {
                JournalEntity found = operator.getById(id);
                return found == null ? null : found.getValue();
            }).get(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
    }

    private static List<Entry> oneUser(String key, int value) {
        return Collections.singletonList(Entry.put(USERS, key, entity(key, value)));
    }

    private static Predicate<Path> isCommittedRecord() {
        return target -> target.getFileName().toString().endsWith(JsonJournal.COMMITTED_SUFFIX);
    }

    private static Predicate<Path> isIn(Path directory) {
        return target -> directory.equals(target.getParent());
    }

    private static Supplier<IOException> accessDenied() {
        return () -> new AccessDeniedException("simulated: held by another program");
    }

    /** What the JDK reports for a Windows sharing or lock violation: exactly a FileSystemException. */
    private static Supplier<IOException> sharingViolation() {
        return () -> new FileSystemException("simulated", null,
                "The process cannot access the file because it is being used by another process");
    }

    /** Captures every record JsonJournal's logger publishes, at FINE and above, while open. */
    static final class JournalLog implements AutoCloseable {
        private final Logger logger = Logger.getLogger(JsonJournal.class.getName());
        private final Level previousLevel = logger.getLevel();
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final CapturingHandler handler = new CapturingHandler(records);

        static JournalLog capture() {
            JournalLog log = new JournalLog();
            log.logger.addHandler(log.handler);
            log.logger.setLevel(Level.FINE);
            return log;
        }

        /** Records at {@code level} that carry a throwable. */
        List<LogRecord> withThrown(Level level) {
            return records.stream().filter(record -> record.getLevel().equals(level) && record.getThrown() != null)
                    .collect(Collectors.toList());
        }

        List<LogRecord> atOrAbove(Level level) {
            return records.stream().filter(record -> record.getLevel().intValue() >= level.intValue())
                    .collect(Collectors.toList());
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    /**
     * A mover that, for moves whose target matches, throws scripted failures before delegating to
     * {@link Files#move}, and counts those moves' attempts. Moves of other targets pass straight
     * through.
     */
    static final class ScriptedMover implements JsonJournal.Mover {
        private final Predicate<Path> targets;
        private final Deque<Supplier<IOException>> script;
        private final Supplier<IOException> forever;
        private final AtomicInteger attempts = new AtomicInteger();

        private ScriptedMover(Predicate<Path> targets, List<Supplier<IOException>> script,
                Supplier<IOException> forever) {
            this.targets = targets;
            this.script = new ArrayDeque<>(script);
            this.forever = forever;
        }

        @SafeVarargs
        static ScriptedMover forTargets(Predicate<Path> targets, Supplier<IOException>... failures) {
            return new ScriptedMover(targets, Arrays.asList(failures), null);
        }

        static ScriptedMover alwaysFailing(Predicate<Path> targets, Supplier<IOException> failure) {
            return new ScriptedMover(targets, Collections.<Supplier<IOException>>emptyList(), failure);
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (targets.test(target)) {
                attempts.incrementAndGet();
                Supplier<IOException> next;
                synchronized (script) {
                    next = script.pollFirst();
                }
                if (next == null) {
                    next = forever;
                }
                if (next != null) {
                    throw next.get();
                }
            }
            Files.move(source, target, options);
        }
    }
}
