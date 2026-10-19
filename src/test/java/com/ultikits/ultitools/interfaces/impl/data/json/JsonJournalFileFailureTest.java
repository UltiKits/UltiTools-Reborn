package com.ultikits.ultitools.interfaces.impl.data.json;

import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.commitName;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.entity;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.journalAt;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.read;
import static com.ultikits.ultitools.interfaces.impl.data.json.JsonJournalTest.recordNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

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

    // ===== Fixtures and helpers =====

    /** A table directory one level below {@code @TempDir}; its operator's journal is its sibling. */
    private Path tableDirectory(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name).resolve("rows"));
    }

    private static SimpleJsonDataOperator<JournalEntity> operatorOver(Path table) {
        return new SimpleJsonDataOperator<>(table.toString(), JournalEntity.class);
    }

    /** The committed records of a directly constructed operator's sibling journal. */
    private static List<String> committedRecords(Path table) throws IOException {
        Path journal = table.resolveSibling(table.getFileName() + JsonJournal.DIRECTORY_NAME);
        List<String> names = JsonJournalTest.names(journal);
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
