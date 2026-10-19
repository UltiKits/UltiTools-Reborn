package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.JsonTransactionManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Table write locks of the JSON transaction engine (questions 6 and 7 of the Phase 20.4
 * decisions, both answered with option A).
 * <p>
 * A transaction's first write to a table locks that table against every other writer until the
 * transaction commits or rolls back; a plain write takes the same lock for the duration of the
 * call. Readers at the default isolation never wait for a lock. A lock wait is bounded; a request
 * that would close a wait-for cycle fails at once with a deadlock. {@code REPEATABLE_READ} and
 * {@code SERIALIZABLE} lock a table at the first read, outside the visibility lock. A read-only
 * transaction rejects writes before staging, and {@code @Transactional(timeout)} sets a deadline
 * every operation and lock wait observes.
 * <p>
 * Determinism: every interleaving is forced by {@link CountDownLatch} handshakes with bounded
 * awaits ({@value #HANDSHAKE_SECONDS} s). A thread that holds a table never waits unboundedly for
 * another thread's write to that table: where it must wait for one, it gives it a bounded
 * handover window ({@value #HANDOVER_WINDOW_SECONDS} s; a timed-out window is not a failure) and
 * then continues. "Has not happened yet" is asserted only with a {@value #NOT_YET_MILLIS} ms
 * bounded await that must return {@code false}, followed by the release and the positive
 * assertion. Lock-wait bounds are injected through {@link JsonStore}'s test seam, never slept.
 */
@Timeout(60)
@DisplayName("JSON transaction engine: table write locks, bounded waits, isolation")
class JsonTransactionLockingTest {

    /** Bounded wait for every handshake that must happen. */
    private static final long HANDSHAKE_SECONDS = 10;

    /** The handover window a table holder gives another thread's write before it continues. */
    private static final long HANDOVER_WINDOW_SECONDS = 1;

    /** Bounded await for "this has not happened yet". */
    private static final long NOT_YET_MILLIS = 300;

    /** The lock-wait bound of the tests that wake a waiter only after its deadline has passed. */
    private static final long LATE_WAKE_BOUND_MILLIS = 200;

    private static final String A_COMMITTED = "A committed";
    private static final String B_UPDATE_RETURNED = "B update returned";
    private static final String B_PLAIN_UPDATE_RETURNED = "B plain update returned";

    @TempDir
    Path tempDir;

    private JsonStore store;
    private DataScope scope;
    private SimpleJsonDataOperator<LockRow> rows;
    private SimpleJsonDataOperator<LockOther> others;
    private JsonTransactionManager manager;

    @BeforeAll
    static void setUpServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @BeforeEach
    void openStore() throws Exception {
        File dataFolder = Files.createDirectory(tempDir.resolve("plugin-data")).toFile();
        scope = newDataScope("json-locking-test", dataFolder, LockRow.class, LockOther.class);
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            store = new JsonStore(tempDir.resolve("store").toString());
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            rows = (SimpleJsonDataOperator<LockRow>) store.getOperator(scope, LockRow.class);
            others = (SimpleJsonDataOperator<LockOther>) store.getOperator(scope, LockOther.class);
        }
        manager = rows.transactionManager();
    }

    @AfterEach
    void resetStepHooks() {
        JsonLockTable.resetStepHook();
    }

    // ===== Writers take turns =====

    @Test
    @DisplayName("A second transaction's write to a table waits until the transaction holding it has ended")
    void writeWaitsForTheTransactionHoldingTheTable() throws Exception {
        rows.insert(new LockRow("x", "0"));
        Queue<String> events = new ConcurrentLinkedQueue<>();
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bUpdateReturned = new CountDownLatch(1);

        Worker a = Worker.start("lock-a", () -> {
            inTransaction(() -> {
                update(rows, new LockRow("x", "a"));
                aWrote.countDown();
                awaitHandover(bUpdateReturned);
            });
            events.add(A_COMMITTED);
        });
        Worker b = Worker.start("lock-b", () -> {
            awaitHandshake(aWrote, "thread A never wrote");
            inTransaction(() -> {
                update(rows, new LockRow("x", "b"));
                events.add(B_UPDATE_RETURNED);
                bUpdateReturned.countDown();
            });
        });
        a.finish();
        b.finish();

        assertThat(new ArrayList<>(events))
                .as("B's update must return only after the transaction holding the table has committed")
                .containsExactly(A_COMMITTED, B_UPDATE_RETURNED);
        assertThat(rows.getById("x").getName()).as("the later writer's value stays").isEqualTo("b");
    }

    @Test
    @DisplayName("A plain write to a table waits until the transaction holding it has committed, then applies")
    void plainWriteWaitsForTransaction() throws Exception {
        rows.insert(new LockRow("x", "0"));
        Queue<String> events = new ConcurrentLinkedQueue<>();
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch plainReturned = new CountDownLatch(1);
        AtomicReference<String> readInside = new AtomicReference<>();

        Worker a = Worker.start("lock-a", () -> {
            inTransaction(() -> {
                update(rows, new LockRow("x", "a"));
                aWrote.countDown();
                awaitHandover(plainReturned);
                readInside.set(rows.getById("x").getName());
            });
            events.add(A_COMMITTED);
        });
        Worker b = Worker.start("lock-b", () -> {
            awaitHandshake(aWrote, "thread A never wrote");
            update(rows, new LockRow("x", "b"));
            events.add(B_PLAIN_UPDATE_RETURNED);
            plainReturned.countDown();
        });
        a.finish();
        b.finish();

        assertThat(new ArrayList<>(events))
                .as("the plain update must return only after the transaction holding the table has committed")
                .containsExactly(A_COMMITTED, B_PLAIN_UPDATE_RETURNED);
        assertThat(readInside.get())
                .as("a read inside the transaction just before its commit shows its own value")
                .isEqualTo("a");
        assertThat(rows.getById("x").getName()).as("the plain write applies after the commit").isEqualTo("b");
    }

    @Test
    @DisplayName("A lock wait past its bound fails with a lock-wait timeout and rolls the waiter back; the holder commits")
    void lockWaitTimesOut() throws Exception {
        store.setLockWaitBoundMillis(200);
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<RuntimeException> bFailure = new AtomicReference<>();
        AtomicLong bWaitNanos = new AtomicLong();
        AtomicBoolean bContextLeft = new AtomicBoolean(true);

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "a"));
            aWrote.countDown();
            // The holder waits (bounded) for the waiter's result before committing.
            awaitHandshake(bDone, "thread B never finished");
        }));
        Worker b = Worker.start("lock-b", () -> {
            awaitHandshake(aWrote, "thread A never wrote");
            long start = System.nanoTime();
            try {
                inTransaction(() -> {
                    others.insert(new LockOther("y", "written before the wait"));
                    update(rows, new LockRow("x", "b"));
                });
            } catch (DataAccessException e) {
                bFailure.set(e);
            } finally {
                bWaitNanos.set(System.nanoTime() - start);
                bContextLeft.set(manager.hasActiveTransaction());
                bDone.countDown();
            }
        });
        a.finish();
        b.finish();

        assertThat(bFailure.get())
                .as("the waiting write fails once its 200 ms bound has passed")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("lock-wait timeout");
        assertThat(TimeUnit.NANOSECONDS.toMillis(bWaitNanos.get()))
                .as("the wait lasted about its bound, not the default and not unbounded")
                .isBetween(150L, 3000L);
        assertThat(bContextLeft.get()).as("the waiter's transaction is rolled back and gone").isFalse();
        assertThat(others.getById("y")).as("the waiter's earlier write is rolled back").isNull();
        assertThat(rows.getById("x").getName()).as("the holder commits unaffected").isEqualTo("a");
    }

    // ===== Read-modify-write =====

    @Test
    @DisplayName("updateIf checks its condition under the table lock, so two updateIf calls expecting the same value cannot both win")
    void updateIfPreventsTheLostUpdate() throws Exception {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aDone = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<Boolean> aResult = new AtomicReference<>();
        AtomicReference<Boolean> bResult = new AtomicReference<>();

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            aResult.set(rows.updateIf(new LockRow("x", "1"), nameIs("0")));
            aDone.countDown();
            awaitHandover(bDone);
        }));
        Worker b = Worker.start("lock-b", () -> {
            awaitHandshake(aDone, "thread A never called updateIf");
            inTransaction(() -> {
                bResult.set(rows.updateIf(new LockRow("x", "1"), nameIs("0")));
                bDone.countDown();
            });
        });
        a.finish();
        b.finish();

        assertThat(Arrays.asList(aResult.get(), bResult.get()))
                .as("exactly one of the two updateIf calls expecting 0 succeeds")
                .containsExactlyInAnyOrder(true, false);
        assertThat(rows.getById("x").getName()).isEqualTo("1");
    }

    /**
     * Characterization of a documented limitation, not a defect: at the default isolation reads
     * take no lock, so a read-modify-write can lose an update. This is the corrected wording of
     * question 6 option A (2026-10-18): dirty writes and blind overwrites are prevented, but a
     * write computed from an earlier plain read is not. Module code that needs the update kept
     * uses {@code updateIf} ({@link #updateIfPreventsTheLostUpdate()}) or
     * {@code @Transactional(isolation = REPEATABLE_READ)} or {@code SERIALIZABLE}
     * ({@code repeatableReadPreventsTheLostUpdate}).
     */
    @Test
    @DisplayName("At the default isolation a read-modify-write can lose an update (documented limitation)")
    void defaultIsolationReadModifyWriteCanLoseAnUpdate() throws Exception {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aRead = new CountDownLatch(1);
        CountDownLatch bRead = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);

        Worker a = Worker.start("lock-a", () -> {
            inTransaction(() -> {
                int read = Integer.parseInt(rows.getById("x").getName());
                aRead.countDown();
                awaitHandshake(bRead, "thread B never read");
                update(rows, new LockRow("x", String.valueOf(read + 1)));
            });
            aCommitted.countDown();
        });
        Worker b = Worker.start("lock-b", () -> inTransaction(() -> {
            awaitHandshake(aRead, "thread A never read");
            int read = Integer.parseInt(rows.getById("x").getName());
            bRead.countDown();
            awaitHandshake(aCommitted, "thread A never committed");
            update(rows, new LockRow("x", String.valueOf(read + 1)));
        }));
        a.finish();
        b.finish();

        assertThat(rows.getById("x").getName())
                .as("both transactions read 0 before either wrote, so one increment is lost")
                .isEqualTo("1");
    }

    // ===== Readers =====

    @Test
    @DisplayName("Readers never wait for a table lock and read committed data while a transaction holds the table")
    void readersNeverWaitForTableLocks() throws Exception {
        rows.insert(new LockRow("x", "committed"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch readsDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> byId = new AtomicReference<>();
        AtomicReference<List<String>> all = new AtomicReference<>();
        AtomicReference<List<String>> listed = new AtomicReference<>();

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "staged"));
            rows.insert(new LockRow("z", "staged insert"));
            aWrote.countDown();
            awaitHandshake(release, "the test never released the holder");
        }));
        Worker reader = Worker.start("lock-reader", () -> {
            try {
                awaitHandshake(aWrote, "thread A never wrote");
                byId.set(rows.getById("x").getName());
                all.set(names(rows.getAll()));
                listed.set(names(rows.query().list()));
            } finally {
                readsDone.countDown();
            }
        });

        boolean readsReturned = readsDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        release.countDown();
        a.finish();
        reader.finish();

        assertThat(readsReturned).as("the reads returned while the transaction still held the table").isTrue();
        assertThat(byId.get()).isEqualTo("committed");
        assertThat(all.get()).containsExactly("committed");
        assertThat(listed.get()).containsExactly("committed");
    }

    // ===== Same-thread re-entry =====

    @Test
    @DisplayName("REQUIRES_NEW and NOT_SUPPORTED bodies on the same thread re-enter the suspended transaction's lock without waiting")
    void sameThreadReentry() throws Exception {
        rows.insert(new LockRow("x", "0"));
        long requiresNewNanos;
        long notSupportedNanos;

        manager.begin();
        try {
            update(rows, new LockRow("x", "outer"));

            // REQUIRES_NEW, driven as TransactionInterceptor drives it.
            Object suspended = manager.suspend();
            long start = System.nanoTime();
            try {
                manager.begin();
                update(rows, new LockRow("x", "requires-new"));
                manager.commit();
            } finally {
                manager.resume(suspended);
            }
            requiresNewNanos = System.nanoTime() - start;

            // NOT_SUPPORTED: the body runs with no transaction.
            suspended = manager.suspend();
            start = System.nanoTime();
            try {
                update(rows, new LockRow("x", "not-supported"));
            } finally {
                manager.resume(suspended);
            }
            notSupportedNanos = System.nanoTime() - start;

            manager.commit();
        } finally {
            rollbackIfStillActive();
        }

        assertThat(TimeUnit.NANOSECONDS.toMillis(requiresNewNanos)).as("REQUIRES_NEW body did not wait").isLessThan(1000L);
        assertThat(TimeUnit.NANOSECONDS.toMillis(notSupportedNanos)).as("NOT_SUPPORTED body did not wait").isLessThan(1000L);
        assertThat(rows.getById("x").getName())
                .as("the outer transaction's later commit is the value that stays")
                .isEqualTo("outer");

        CountDownLatch otherDone = new CountDownLatch(1);
        Worker other = Worker.start("lock-other", () -> {
            update(rows, new LockRow("x", "other"));
            otherDone.countDown();
        });
        assertThat(otherDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                .as("after the outer transaction ended, another thread locks X")
                .isTrue();
        other.finish();
        assertThat(rows.getById("x").getName()).isEqualTo("other");
    }

    @Test
    @DisplayName("An inner REQUIRES_NEW or NOT_SUPPORTED context releases only its own holds; the outer transaction keeps the table")
    void innerContextsReleaseOnlyTheirOwnHolds() throws Exception {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch otherDone = new CountDownLatch(1);
        Worker other;
        boolean otherReturnedWhileOuterOpen;

        manager.begin();
        try {
            update(rows, new LockRow("x", "outer"));

            Object suspended = manager.suspend();
            try {
                manager.begin();
                update(rows, new LockRow("x", "requires-new"));
                manager.commit();
            } finally {
                manager.resume(suspended);
            }

            suspended = manager.suspend();
            try {
                update(rows, new LockRow("x", "not-supported"));
            } finally {
                manager.resume(suspended);
            }

            other = Worker.start("lock-other", () -> {
                update(rows, new LockRow("x", "other"));
                otherDone.countDown();
            });
            otherReturnedWhileOuterOpen = otherDone.await(NOT_YET_MILLIS, TimeUnit.MILLISECONDS);
            manager.commit();
        } finally {
            rollbackIfStillActive();
        }

        assertThat(otherReturnedWhileOuterOpen)
                .as("the outer transaction still holds X after its inner contexts ended, so another thread waits")
                .isFalse();
        assertThat(otherDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                .as("once the outer transaction commits, the waiting write proceeds")
                .isTrue();
        other.finish();
        assertThat(rows.getById("x").getName()).isEqualTo("other");
    }

    // ===== Deadlock detection =====

    @Test
    @DisplayName("A request that would close a wait-for cycle fails at once with a deadlock; the other transaction commits")
    void deadlockFailsTheRequesterAtOnce() throws Exception {
        store.setLockWaitBoundMillis(3000);
        CountDownLatch t2Wrote = new CountDownLatch(1);
        CountDownLatch t1Waiting = new CountDownLatch(1);
        JsonLockTable.installStepHook(waiterRegisteredBy("deadlock-t1", t1Waiting));
        Outcome o1 = new Outcome();
        Outcome o2 = new Outcome();

        Worker t1 = Worker.start("deadlock-t1", () -> o1.run(() -> inTransaction(() -> {
            rows.insert(new LockRow("p1", "t1"));
            awaitHandshake(t2Wrote, "T2 never wrote Q");
            o1.requested();
            others.insert(new LockOther("q1", "t1"));
        })));
        Worker t2 = Worker.start("deadlock-t2", () -> o2.run(() -> inTransaction(() -> {
            others.insert(new LockOther("q2", "t2"));
            t2Wrote.countDown();
            awaitHandshake(t1Waiting, "T1 never registered as waiting for Q");
            o2.requested();
            rows.insert(new LockRow("p2", "t2"));
        })));
        t1.finish();
        t2.finish();

        assertExactlyOneDeadlockLoser(o1, o2, "p1", "q1", "p2", "q2");
    }

    @Test
    @DisplayName("Two requests that close a cycle at the same moment: in every one of 20 rounds exactly one fails at once")
    void simultaneousCycleClosersExactlyOneFails() throws Exception {
        store.setLockWaitBoundMillis(3000);
        for (int round = 0; round < 20; round++) {
            String p1 = "p1-" + round;
            String q1 = "q1-" + round;
            String p2 = "p2-" + round;
            String q2 = "q2-" + round;
            CountDownLatch holding = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Outcome o1 = new Outcome();
            Outcome o2 = new Outcome();

            Worker t1 = Worker.start("closer-t1-" + round, () -> o1.run(() -> inTransaction(() -> {
                rows.insert(new LockRow(p1, "t1"));
                holding.countDown();
                awaitHandshake(start, "the round never started");
                o1.requested();
                others.insert(new LockOther(q1, "t1"));
            })));
            Worker t2 = Worker.start("closer-t2-" + round, () -> o2.run(() -> inTransaction(() -> {
                others.insert(new LockOther(q2, "t2"));
                holding.countDown();
                awaitHandshake(start, "the round never started");
                o2.requested();
                rows.insert(new LockRow(p2, "t2"));
            })));
            assertThat(holding.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("round " + round + ": both hold").isTrue();
            start.countDown();
            t1.finish();
            t2.finish();

            assertExactlyOneDeadlockLoser(o1, o2, p1, q1, p2, q2);
        }
    }

    // ===== Isolation levels =====

    @Test
    @DisplayName("REPEATABLE_READ locks the table at the first read, so a read-modify-write keeps both updates")
    void repeatableReadPreventsTheLostUpdate() throws Exception {
        assertStricterIsolationPreventsTheLostUpdate(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    @DisplayName("SERIALIZABLE locks the table at the first read, so a read-modify-write keeps both updates")
    void serializablePreventsTheLostUpdate() throws Exception {
        assertStricterIsolationPreventsTheLostUpdate(Connection.TRANSACTION_SERIALIZABLE);
    }

    @Test
    @DisplayName("At READ_COMMITTED a transaction's read leaves another thread's write of the same table free to proceed")
    void readCommittedReadDoesNotBlockAWriter() throws Exception {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Worker reader = Worker.start("rc-reader", () -> inTransaction(Connection.TRANSACTION_READ_COMMITTED, () -> {
            rows.getById("x");
            read.countDown();
            awaitHandshake(release, "the test never released the reader");
        }));
        Worker writer = Worker.start("rc-writer", () -> {
            awaitHandshake(read, "the reader never read");
            update(rows, new LockRow("x", "written"));
            writerDone.countDown();
        });

        boolean writeReturned = writerDone.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS);
        release.countDown();
        reader.finish();
        writer.finish();

        assertThat(writeReturned).as("the write did not wait for the READ_COMMITTED reader").isTrue();
        assertThat(rows.getById("x").getName()).isEqualTo("written");
    }

    @Test
    @DisplayName("A REPEATABLE_READ first read waits for the table lock outside the visibility lock: other reads, "
            + "a plain write and the holder's commit go on")
    void repeatableReadFirstReadWaitsOutsideTheVisibilityLock() throws Exception {
        store.setLockWaitBoundMillis(3000);
        rows.insert(new LockRow("x", "old"));
        others.insert(new LockOther("q", "q0"));
        CountDownLatch tWrote = new CountDownLatch(1);
        CountDownLatch tRelease = new CountDownLatch(1);
        CountDownLatch tCommitted = new CountDownLatch(1);
        CountDownLatch rWaitingOrReturned = new CountDownLatch(1);
        CountDownLatch rDone = new CountDownLatch(1);
        AtomicReference<String> rRead = new AtomicReference<>();
        JsonLockTable.installStepHook(waiterRegisteredBy("rr-reader", rWaitingOrReturned));

        Worker holder = Worker.start("rr-holder", () -> {
            inTransaction(() -> {
                update(rows, new LockRow("x", "new"));
                tWrote.countDown();
                awaitHandshake(tRelease, "the test never released the holder");
            });
            tCommitted.countDown();
        });
        Worker reader = Worker.start("rr-reader", () -> {
            try {
                awaitHandshake(tWrote, "the holder never wrote");
                inTransaction(Connection.TRANSACTION_REPEATABLE_READ, () -> rRead.set(rows.getById("x").getName()));
            } finally {
                rDone.countDown();
                rWaitingOrReturned.countDown();
            }
        });

        assertThat(rWaitingOrReturned.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                .as("the REPEATABLE_READ reader registered as waiting or returned").isTrue();
        assertThat(rDone.getCount()).as("the REPEATABLE_READ reader waits for the holder's table lock").isEqualTo(1L);

        CountDownLatch rowById = new CountDownLatch(1);
        CountDownLatch rowAll = new CountDownLatch(1);
        CountDownLatch otherById = new CountDownLatch(1);
        CountDownLatch otherAll = new CountDownLatch(1);
        CountDownLatch otherWrite = new CountDownLatch(1);
        Worker third = Worker.start("rr-third", () -> {
            rows.getById("x");
            rowById.countDown();
            rows.getAll();
            rowAll.countDown();
            others.getById("q");
            otherById.countDown();
            others.getAll();
            otherAll.countDown();
            others.update(new LockOther("q", "q1"));
            otherWrite.countDown();
        });
        assertThat(rowById.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("getById on P").isTrue();
        assertThat(rowAll.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("getAll on P").isTrue();
        assertThat(otherById.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("getById on Q").isTrue();
        assertThat(otherAll.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("getAll on Q").isTrue();
        assertThat(otherWrite.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("plain write to Q").isTrue();

        tRelease.countDown();
        assertThat(tCommitted.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS)).as("the holder's commit").isTrue();
        assertThat(rDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("the reader returned").isTrue();
        holder.finish();
        reader.finish();
        third.finish();
        assertThat(rRead.get()).as("the reader sees the holder's committed value").isEqualTo("new");
        assertThat(others.getById("q").getLabel()).isEqualTo("q1");
    }

    // ===== Read-only =====

    @Test
    @DisplayName("A read-only transaction rejects every write before staging; reads work and no table is locked")
    void readOnlyRejectsWrites() throws Exception {
        rows.insert(new LockRow("x", "0"));
        rows.insert(new LockRow("keep", "keep"));
        CountDownLatch otherDone = new CountDownLatch(1);
        boolean otherWriteProceeded;

        manager.begin();
        try {
            setReadOnly(true);
            assertThatThrownBy(() -> rows.insert(new LockRow("n", "new")))
                    .as("insert").isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
            assertThatThrownBy(() -> rows.update(new LockRow("x", "1")))
                    .as("update").isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
            assertThatThrownBy(() -> rows.updateIf(new LockRow("x", "1"), nameIs("0")))
                    .as("updateIf").isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
            assertThatThrownBy(() -> rows.delById("x"))
                    .as("delById").isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");
            assertThatThrownBy(() -> rows.del(nameIs("keep")))
                    .as("del").isInstanceOf(DataAccessException.class).hasMessageContaining("read-only");

            assertThat(rows.getById("x").getName()).as("reads work").isEqualTo("0");
            assertThat(names(rows.getAll())).containsExactlyInAnyOrder("0", "keep");
            assertThat(manager.currentTransaction().hasStagedWrites(rows)).as("nothing staged").isFalse();

            Worker other = Worker.start("ro-other", () -> {
                update(rows, new LockRow("x", "other"));
                otherDone.countDown();
            });
            otherWriteProceeded = otherDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
            other.finish();
            manager.commit();
        } finally {
            rollbackIfStillActive();
        }

        assertThat(otherWriteProceeded).as("a rejected write took no table lock").isTrue();
        assertThat(rows.getById("x").getName()).isEqualTo("other");
        assertThat(rows.getById("n")).isNull();
        assertThat(rows.getById("keep")).isNotNull();
    }

    // ===== Timeout =====

    @Test
    @DisplayName("@Transactional(timeout = 1) on JSON: an operation after the deadline and a lock wait crossing it "
            + "fail and roll back; nothing is left on the thread")
    void timeoutDeadline() throws Exception {
        TimedBean bean = timedBean();

        // An operation started after the deadline.
        long before = System.nanoTime();
        assertThatThrownBy(() -> bean.writeWaitForTimeThenWrite("t-first", "t-second"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("timeout");
        assertThat(bean.deadlineSeen.get()).as("getTimeoutDeadlineNanos reports the deadline").isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(bean.deadlineSeen.get() - before))
                .as("deadline about one second after begin").isBetween(500L, 1500L);
        assertThat(rows.getById("t-first")).as("the earlier write is rolled back").isNull();
        assertThat(rows.getById("t-second")).isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left on the thread").isFalse();
        assertThat(manager.getTimeoutDeadlineNanos()).isNull();

        // A lock wait that crosses the deadline (the default bound, 5 s, is longer).
        CountDownLatch holderWrote = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Worker holder = Worker.start("timeout-holder", () -> inTransaction(() -> {
            others.insert(new LockOther("held", "holder"));
            holderWrote.countDown();
            awaitHandshake(holderRelease, "the test never released the holder");
        }));
        awaitHandshake(holderWrote, "the holder never wrote");
        long start = System.nanoTime();
        AtomicReference<RuntimeException> waitFailure = new AtomicReference<>();
        try {
            bean.writeRowThenOther("t-row", "t-other");
        } catch (DataAccessException e) {
            waitFailure.set(e);
        }
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        holderRelease.countDown();
        holder.finish();

        assertThat(waitFailure.get()).as("the lock wait crossing the deadline fails")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("lock-wait timeout");
        assertThat(waitedMillis).as("bounded by the transaction's deadline, not the 5 s default").isBetween(500L, 3000L);
        assertThat(rows.getById("t-row")).as("the transaction is rolled back").isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left on the thread").isFalse();

        CountDownLatch afterDone = new CountDownLatch(1);
        Worker after = Worker.start("timeout-after", () -> {
            rows.insert(new LockRow("after", "after"));
            others.insert(new LockOther("after", "after"));
            afterDone.countDown();
        });
        assertThat(afterDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("no table lock is left behind").isTrue();
        after.finish();
    }

    // ===== A lock failure marks its transaction rollback-only (question 7 A) =====

    @Test
    @DisplayName("A caught lock-wait timeout marks the transaction rollback-only: its commit rolls back everything and "
            + "throws UnexpectedRollbackException; the holder commits")
    void caughtLockWaitTimeoutMarksTheTransactionRollbackOnly() throws Exception {
        store.setLockWaitBoundMillis(200);
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<DataAccessException> caught = new AtomicReference<>();
        AtomicReference<RuntimeException> commitFailure = new AtomicReference<>();
        AtomicBoolean bContextLeft = new AtomicBoolean(true);

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "a"));
            aWrote.countDown();
            awaitHandshake(bDone, "thread B never finished");
        }));
        Worker b = Worker.start("lock-b", () -> {
            try {
                awaitHandshake(aWrote, "thread A never wrote");
                manager.begin();
                others.insert(new LockOther("y", "written before the wait"));
                try {
                    update(rows, new LockRow("x", "b"));
                } catch (DataAccessException e) {
                    caught.set(e);
                }
                others.insert(new LockOther("z", "written after the caught failure"));
                try {
                    manager.commit();
                } catch (RuntimeException e) {
                    commitFailure.set(e);
                }
            } finally {
                rollbackIfStillActive();
                bContextLeft.set(manager.hasActiveTransaction());
                bDone.countDown();
            }
        });
        a.finish();
        b.finish();

        assertThat(caught.get()).as("the waiting write fails with a lock-wait timeout")
                .hasMessageContaining("lock-wait timeout");
        assertThat(commitFailure.get())
                .as("catching the failure does not save the transaction: its commit rolls back")
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(others.getById("y")).as("the write before the failure is rolled back").isNull();
        assertThat(others.getById("z")).as("the write after the caught failure is rolled back").isNull();
        assertThat(bContextLeft.get()).as("no context left on B's thread").isFalse();
        assertThat(rows.getById("x").getName()).as("the holder commits unaffected").isEqualTo("a");
    }

    @Test
    @DisplayName("A caught deadlock marks the cycle-closer's transaction rollback-only; the other transaction commits")
    void caughtDeadlockMarksTheCycleClosersTransactionRollbackOnly() throws Exception {
        store.setLockWaitBoundMillis(3000);
        CountDownLatch t2Wrote = new CountDownLatch(1);
        CountDownLatch t1Waiting = new CountDownLatch(1);
        JsonLockTable.installStepHook(waiterRegisteredBy("deadlock-t1", t1Waiting));
        AtomicReference<DataAccessException> caught = new AtomicReference<>();
        AtomicReference<RuntimeException> t2CommitFailure = new AtomicReference<>();

        Worker t1 = Worker.start("deadlock-t1", () -> inTransaction(() -> {
            rows.insert(new LockRow("p1", "t1"));
            awaitHandshake(t2Wrote, "T2 never wrote Q");
            others.insert(new LockOther("q1", "t1"));
        }));
        Worker t2 = Worker.start("deadlock-t2", () -> {
            try {
                manager.begin();
                others.insert(new LockOther("q2", "t2"));
                t2Wrote.countDown();
                awaitHandshake(t1Waiting, "T1 never registered as waiting for Q");
                try {
                    rows.insert(new LockRow("p2", "t2"));
                } catch (DataAccessException e) {
                    caught.set(e);
                }
                others.insert(new LockOther("q2b", "written after the caught deadlock"));
                try {
                    manager.commit();
                } catch (RuntimeException e) {
                    t2CommitFailure.set(e);
                }
            } finally {
                rollbackIfStillActive();
            }
        });
        t1.finish();
        t2.finish();

        assertThat(caught.get()).as("the cycle-closing request fails with a deadlock").hasMessageContaining("deadlock");
        assertThat(t2CommitFailure.get())
                .as("catching the deadlock does not save the transaction: its commit rolls back")
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(others.getById("q2")).as("the cycle-closer's write before the failure is rolled back").isNull();
        assertThat(others.getById("q2b")).as("the cycle-closer's write after the failure is rolled back").isNull();
        assertThat(rows.getById("p2")).isNull();
        assertThat(rows.getById("p1")).as("the other transaction commits").isNotNull();
        assertThat(others.getById("q1")).as("the other transaction commits").isNotNull();
    }

    @Test
    @DisplayName("A marked transaction keeps its table locks until it ends: another writer waits for its rollback")
    void markedTransactionKeepsItsLocksUntilItEnds() throws Exception {
        store.setLockWaitBoundMillis(200);
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bMarked = new CountDownLatch(1);
        CountDownLatch bRelease = new CountDownLatch(1);
        CountDownLatch thirdReturned = new CountDownLatch(1);
        AtomicReference<RuntimeException> commitFailure = new AtomicReference<>();

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "a"));
            aWrote.countDown();
            awaitHandshake(bMarked, "thread B never failed its lock wait");
        }));
        Worker b = Worker.start("lock-b", () -> {
            try {
                awaitHandshake(aWrote, "thread A never wrote");
                manager.begin();
                others.insert(new LockOther("y", "b"));
                try {
                    update(rows, new LockRow("x", "b"));
                } catch (DataAccessException e) {
                    bMarked.countDown();
                }
                awaitHandshake(bRelease, "the test never released B");
                try {
                    manager.commit();
                } catch (RuntimeException e) {
                    commitFailure.set(e);
                }
            } finally {
                rollbackIfStillActive();
            }
        });
        awaitHandshake(bMarked, "thread B never failed its lock wait");
        a.finish();
        store.setLockWaitBoundMillis(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        Worker third = Worker.start("lock-third", () -> {
            others.insert(new LockOther("w", "third"));
            thirdReturned.countDown();
        });

        boolean returnedEarly = thirdReturned.await(NOT_YET_MILLIS, TimeUnit.MILLISECONDS);
        bRelease.countDown();
        b.finish();
        third.finish();

        assertThat(returnedEarly).as("another writer of the marked transaction's table waits for it").isFalse();
        assertThat(commitFailure.get()).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(others.getById("w")).as("the waiting writer applies after the rollback").isNotNull();
        assertThat(others.getById("y")).isNull();
    }

    @Test
    @DisplayName("A caught lock-wait timeout of a REPEATABLE_READ first read marks the transaction rollback-only")
    void caughtRepeatableReadLockTimeoutMarksTheTransactionRollbackOnly() throws Exception {
        store.setLockWaitBoundMillis(200);
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<DataAccessException> caught = new AtomicReference<>();
        AtomicReference<RuntimeException> commitFailure = new AtomicReference<>();

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "a"));
            aWrote.countDown();
            awaitHandshake(bDone, "thread B never finished");
        }));
        Worker b = Worker.start("lock-b", () -> {
            try {
                awaitHandshake(aWrote, "thread A never wrote");
                manager.begin();
                setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
                others.insert(new LockOther("y", "written before the read"));
                try {
                    rows.getById("x");
                } catch (DataAccessException e) {
                    caught.set(e);
                }
                try {
                    manager.commit();
                } catch (RuntimeException e) {
                    commitFailure.set(e);
                }
            } finally {
                rollbackIfStillActive();
                bDone.countDown();
            }
        });
        a.finish();
        b.finish();

        assertThat(caught.get()).as("the first read's lock wait fails").hasMessageContaining("lock-wait timeout");
        assertThat(commitFailure.get()).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(others.getById("y")).as("the transaction's write is rolled back").isNull();
        assertThat(rows.getById("x").getName()).isEqualTo("a");
    }

    @Test
    @DisplayName("@Transactional: a lock-wait timeout caught in the method body still rolls the transaction back "
            + "with UnexpectedRollbackException")
    void transactionalMethodCatchingALockWaitTimeoutRollsBack() throws Exception {
        store.setLockWaitBoundMillis(200);
        CatchingBean bean = catchingBean();
        CountDownLatch holderWrote = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Worker holder = Worker.start("catching-holder", () -> inTransaction(() -> {
            others.insert(new LockOther("held", "holder"));
            holderWrote.countDown();
            awaitHandshake(holderRelease, "the test never released the holder");
        }));
        awaitHandshake(holderWrote, "the holder never wrote");

        Throwable thrown;
        try {
            thrown = catchThrowable(() -> bean.writeRowThenCatchOtherFailure("c-row", "c-other"));
        } finally {
            holderRelease.countDown();
            holder.finish();
        }

        assertThat(bean.caught.get()).as("the body caught the lock-wait timeout").hasMessageContaining("lock-wait timeout");
        assertThat(thrown).as("the method's commit rolls back").isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows.getById("c-row")).as("the method's earlier write is rolled back").isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left on the thread").isFalse();
        assertThat(others.getById("held")).as("the holder commits unaffected").isNotNull();
    }

    @Test
    @DisplayName("@Transactional(timeout = 1): a lock wait ended by the transaction's deadline and caught in the body "
            + "still rolls the transaction back with UnexpectedRollbackException")
    void transactionalDeadlineLockWaitCaughtInTheBodyRollsBack() throws Exception {
        CatchingBean bean = catchingBean();
        CountDownLatch holderWrote = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        Worker holder = Worker.start("deadline-holder", () -> inTransaction(() -> {
            others.insert(new LockOther("held", "holder"));
            holderWrote.countDown();
            awaitHandshake(holderRelease, "the test never released the holder");
        }));
        awaitHandshake(holderWrote, "the holder never wrote");

        long start = System.nanoTime();
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> bean.timedWriteRowThenCatchOtherFailure("d-row", "d-other"));
        } finally {
            holderRelease.countDown();
            holder.finish();
        }
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(bean.caught.get()).as("the body caught the lock-wait timeout").hasMessageContaining("lock-wait timeout");
        assertThat(waitedMillis).as("bounded by the transaction's deadline, not the 5 s default").isBetween(500L, 3000L);
        assertThat(thrown).as("the method's commit rolls back").isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows.getById("d-row")).as("the method's earlier write is rolled back").isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left on the thread").isFalse();
    }

    @Test
    @DisplayName("A plain write outside a transaction that times out just fails: nothing is applied, nothing is marked, "
            + "and the next plain write applies")
    void plainWriteLockWaitTimeoutJustFails() throws Exception {
        store.setLockWaitBoundMillis(200);
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<DataAccessException> failure = new AtomicReference<>();
        AtomicBoolean bHadContext = new AtomicBoolean(true);

        Worker a = Worker.start("lock-a", () -> inTransaction(() -> {
            update(rows, new LockRow("x", "a"));
            aWrote.countDown();
            awaitHandshake(bDone, "thread B never finished");
        }));
        Worker b = Worker.start("lock-b", () -> {
            try {
                awaitHandshake(aWrote, "thread A never wrote");
                try {
                    update(rows, new LockRow("x", "b"));
                } catch (DataAccessException e) {
                    failure.set(e);
                }
                bHadContext.set(manager.hasActiveTransaction());
                others.insert(new LockOther("y", "plain after the failure"));
            } finally {
                bDone.countDown();
            }
        });
        a.finish();
        b.finish();

        assertThat(failure.get()).as("the plain write fails").hasMessageContaining("lock-wait timeout");
        assertThat(bHadContext.get()).as("no transaction context is created for a plain write").isFalse();
        assertThat(others.getById("y")).as("the next plain write applies").isNotNull();
        assertThat(rows.getById("x").getName()).as("the failed plain write was not applied").isEqualTo("a");
    }

    // ===== An expired deadline marks the transaction rollback-only (Spring's checkTransactionTimeout) =====

    @Test
    @DisplayName("A write refused because the transaction's deadline has passed, caught by the caller, marks the "
            + "transaction rollback-only: its commit rolls back everything and throws UnexpectedRollbackException")
    void caughtDeadlineRefusalOfAWriteMarksTheTransactionRollbackOnly() {
        RuntimeException caught = null;
        RuntimeException commitFailure = null;
        manager.begin();
        try {
            rows.insert(new LockRow("before", "written before the deadline"));
            manager.currentTransaction().setDeadlineNanos(System.nanoTime() - 1L);
            try {
                rows.insert(new LockRow("after", "refused"));
            } catch (DataAccessException e) {
                caught = e;
            }
            try {
                manager.commit();
            } catch (RuntimeException e) {
                commitFailure = e;
            }
        } finally {
            rollbackIfStillActive();
        }

        assertThat(caught).as("the write after the deadline is refused").hasMessageContaining("deadline passed");
        assertThat(commitFailure).as("catching the refusal does not save the transaction")
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows.getById("before")).as("the write before the deadline is rolled back").isNull();
        assertThat(rows.getById("after")).isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left").isFalse();
    }

    @Test
    @DisplayName("A read refused because the transaction's deadline has passed, caught by the caller, marks the "
            + "transaction rollback-only")
    void caughtDeadlineRefusalOfAReadMarksTheTransactionRollbackOnly() {
        RuntimeException caught = null;
        RuntimeException commitFailure = null;
        manager.begin();
        try {
            rows.insert(new LockRow("before", "written before the deadline"));
            manager.currentTransaction().setDeadlineNanos(System.nanoTime() - 1L);
            try {
                rows.getById("before");
            } catch (DataAccessException e) {
                caught = e;
            }
            try {
                manager.commit();
            } catch (RuntimeException e) {
                commitFailure = e;
            }
        } finally {
            rollbackIfStillActive();
        }

        assertThat(caught).as("the read after the deadline is refused").hasMessageContaining("deadline passed");
        assertThat(commitFailure).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows.getById("before")).as("the transaction's write is rolled back").isNull();
    }

    @Test
    @DisplayName("@Transactional(timeout = 1): a write refused after the deadline and caught in the method body still "
            + "rolls the transaction back with UnexpectedRollbackException")
    void transactionalMethodCatchingADeadlineRefusalRollsBack() throws Exception {
        CatchingBean bean = catchingBean();

        Throwable thrown = catchThrowable(() -> bean.timedWriteWaitThenCatchRefusal("e-first", "e-second"));

        assertThat(bean.caught.get()).as("the body caught the deadline refusal").hasMessageContaining("deadline passed");
        assertThat(thrown).as("the method's commit rolls back").isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rows.getById("e-first")).as("the write before the deadline is rolled back").isNull();
        assertThat(rows.getById("e-second")).isNull();
        assertThat(manager.hasActiveTransaction()).as("no context left on the thread").isFalse();
    }

    // ===== Statement-level refusals do not mark the transaction (JSON = JDBC) =====

    @Test
    @DisplayName("An insert refused for an id that cannot name its file, caught by the caller, leaves the transaction "
            + "committable")
    void caughtInvalidIdRefusalLeavesTheTransactionCommittable() {
        assertCaughtRefusalLeavesTheTransactionCommittable(() -> rows.insert(new LockRow("a/b", "refused")));
    }

    @Test
    @DisplayName("An update refused for a null id, caught by the caller, leaves the transaction committable")
    void caughtNullIdRefusalLeavesTheTransactionCommittable() {
        assertCaughtRefusalLeavesTheTransactionCommittable(() -> rows.update(new LockRow(null, "refused")));
    }

    @Test
    @DisplayName("A delete refused for having no condition, caught by the caller, leaves the transaction committable")
    void caughtConditionlessDeleteRefusalLeavesTheTransactionCommittable() {
        assertCaughtRefusalLeavesTheTransactionCommittable(() -> rows.del());
    }

    @Test
    @DisplayName("An updateIf refused for an unknown column, caught by the caller, leaves the transaction committable")
    void caughtUpdateIfConditionRefusalLeavesTheTransactionCommittable() {
        assertCaughtRefusalLeavesTheTransactionCommittable(() -> rows.updateIf(new LockRow("kept-1", "refused"),
                WhereCondition.builder().column("no_such_column").value("x").build()));
    }

    @Test
    @DisplayName("An update refused for a value that is not serialisable, caught by the caller, leaves the transaction "
            + "committable")
    void caughtUnserialisableValueRefusalLeavesTheTransactionCommittable() {
        assertCaughtRefusalLeavesTheTransactionCommittable(() -> rows.update("name", new Object(), "kept-1"));
    }

    /**
     * Inside one transaction: a write, the refused call (caught), another write, commit. The
     * refusal is a statement-level caller error, as a failed statement inside a MySQL or SQLite
     * transaction is: the commit succeeds and both writes stay.
     */
    private void assertCaughtRefusalLeavesTheTransactionCommittable(Runnable refused) {
        RuntimeException caught = null;
        manager.begin();
        try {
            rows.insert(new LockRow("kept-1", "before the refusal"));
            try {
                refused.run();
            } catch (DataAccessException e) {
                caught = e;
            }
            rows.insert(new LockRow("kept-2", "after the refusal"));
            manager.commit();
        } finally {
            rollbackIfStillActive();
        }

        assertThat(caught).as("the call is refused").isNotNull();
        assertThat(rows.getById("kept-1")).as("the write before the refusal commits").isNotNull();
        assertThat(rows.getById("kept-2")).as("the write after the refusal commits").isNotNull();
        assertThat(manager.hasActiveTransaction()).isFalse();
    }

    // ===== A waiter woken past its deadline fails, even with the table free (question 7 A) =====

    @Test
    @DisplayName("A REPEATABLE_READ first read woken past its lock-wait deadline with the table free fails with a "
            + "lock-wait timeout and marks its transaction rollback-only")
    void repeatableReadWokenPastItsDeadlineWithTheTableFreeTimesOut() throws Exception {
        rows.insert(new LockRow("x", "0"));
        AtomicReference<String> readValue = new AtomicReference<>();
        AtomicReference<DataAccessException> caught = new AtomicReference<>();
        AtomicReference<RuntimeException> commitFailure = new AtomicReference<>();

        wakeWaiterPastItsDeadlineWithTheTableFree("late-reader", () -> {
            manager.begin();
            try {
                setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
                try {
                    readValue.set(rows.getById("x").getName());
                } catch (DataAccessException e) {
                    caught.set(e);
                }
                try {
                    manager.commit();
                } catch (RuntimeException e) {
                    commitFailure.set(e);
                }
            } finally {
                rollbackIfStillActive();
            }
        });

        assertThat(readValue.get()).as("the read does not go on past its deadline").isNull();
        assertThat(caught.get()).as("the read fails with a lock-wait timeout").isNotNull()
                .hasMessageContaining("lock-wait timeout");
        assertThat(commitFailure.get()).as("the failed wait marks the transaction rollback-only")
                .isInstanceOf(UnexpectedRollbackException.class);
    }

    @Test
    @DisplayName("A plain write woken past its lock-wait deadline with the table free fails and is not applied")
    void plainWriteWokenPastItsDeadlineWithTheTableFreeIsNotApplied() throws Exception {
        rows.insert(new LockRow("x", "0"));
        AtomicReference<DataAccessException> caught = new AtomicReference<>();

        wakeWaiterPastItsDeadlineWithTheTableFree("late-writer", () -> {
            try {
                update(rows, new LockRow("x", "late"));
            } catch (DataAccessException e) {
                caught.set(e);
            }
        });

        assertThat(caught.get()).as("the write fails with a lock-wait timeout").isNotNull()
                .hasMessageContaining("lock-wait timeout");
        assertThat(rows.getById("x").getName()).as("the write was not applied").isEqualTo("0");
    }

    /**
     * Runs {@code waiter} on a thread named {@code waiterName} while this thread owns {@code rows}'
     * table, and lets the waiter go on only once its lock-wait deadline has passed and the table is
     * free. This thread holds the lock table's monitor from the moment the waiter parks in its
     * timed wait until both are true, so the waiter, signalled or timed out, cannot leave the wait
     * before then: no sleep orders anything. Afterwards no waiter is left registered and the table
     * is free.
     */
    private void wakeWaiterPastItsDeadlineWithTheTableFree(String waiterName, Runnable waiter) throws Exception {
        long boundNanos = TimeUnit.MILLISECONDS.toNanos(LATE_WAKE_BOUND_MILLIS);
        store.setLockWaitBoundMillis(LATE_WAKE_BOUND_MILLIS);
        JsonLockTable lockTable = rows.lockTable();
        ReentrantLock monitor = monitorOf(lockTable);
        CountDownLatch waiterRegistered = new CountDownLatch(1);
        AtomicLong registeredAt = new AtomicLong();
        JsonLockTable.installStepHook(step -> {
            if (JsonLockTable.STEP_WAITER_REGISTERED.equals(step) && waiterName.equals(Thread.currentThread().getName())) {
                registeredAt.set(System.nanoTime());
                waiterRegistered.countDown();
            }
        });

        lockTable.acquire(rows, System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDSHAKE_SECONDS));
        boolean owned = true;
        try {
            Worker worker = Worker.start(waiterName, waiter);
            awaitHandshake(waiterRegistered, waiterName + " never registered as waiting");
            // The waiter registered holding the monitor and keeps it until its timed wait parks, so
            // this waits for that, and from here on the waiter stays parked.
            monitor.lock();
            try {
                // The waiter's deadline was taken before it registered, so it has passed once one
                // whole bound has elapsed since the registration.
                while (System.nanoTime() - registeredAt.get() - boundNanos <= 0L) {
                    Thread.yield();
                }
                lockTable.release(rows);
                owned = false;
            } finally {
                monitor.unlock();
            }
            worker.finish();
        } finally {
            if (owned) {
                lockTable.release(rows);
            }
        }

        assertThat(waitersOf(lockTable)).as("no waiter is left registered").isEmpty();
        lockTable.acquire(rows, System.nanoTime());
        lockTable.release(rows);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static ReentrantLock monitorOf(JsonLockTable lockTable) throws ReflectiveOperationException {
        Field field = JsonLockTable.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return (ReentrantLock) field.get(lockTable);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Map<?, ?> waitersOf(JsonLockTable lockTable) throws ReflectiveOperationException {
        Field field = JsonLockTable.class.getDeclaredField("waitsFor");
        field.setAccessible(true);
        ReentrantLock monitor = monitorOf(lockTable);
        monitor.lock();
        try {
            return new java.util.HashMap<>((Map<?, ?>) field.get(lockTable));
        } finally {
            monitor.unlock();
        }
    }

    // ===== Helpers =====

    /**
     * Runs {@code body} in a transaction of the store's manager on the calling thread, as
     * {@code TransactionInterceptor} does: begin, body, commit; a failure rolls back and is
     * rethrown.
     */
    private void inTransaction(Runnable body) {
        boolean committed = false;
        manager.begin();
        try {
            body.run();
            manager.commit();
            committed = true;
        } finally {
            if (!committed) {
                rollbackIfStillActive();
            }
        }
    }

    /**
     * Runs {@code body} in a transaction at {@code isolationLevel} (a {@link Connection} constant),
     * set right after begin, as {@code TransactionInterceptor} sets it.
     */
    private void inTransaction(int isolationLevel, Runnable body) {
        boolean committed = false;
        manager.begin();
        try {
            setIsolationLevel(isolationLevel);
            body.run();
            manager.commit();
            committed = true;
        } finally {
            if (!committed) {
                rollbackIfStillActive();
            }
        }
    }

    /** {@code TransactionManager#setIsolationLevel} is deprecated on the base interface (JDBC-flavoured name). */
    @SuppressWarnings({"deprecation", "removal"})
    private void setIsolationLevel(int level) {
        manager.setIsolationLevel(level);
    }

    /** {@code TransactionManager#setReadOnly} is deprecated on the base interface (JDBC-flavoured name). */
    @SuppressWarnings({"deprecation", "removal"})
    private void setReadOnly(boolean readOnly) {
        manager.setReadOnly(readOnly);
    }

    /**
     * X = 0; A reads X at {@code level} (locking the table), B then reads X and waits for A; A gives
     * B's read a one-second handover window, writes its read + 1 and commits; B, released by A's
     * commit, reads 1 and writes 2.
     */
    private void assertStricterIsolationPreventsTheLostUpdate(int level) throws InterruptedException {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aRead = new CountDownLatch(1);
        CountDownLatch bReadReturned = new CountDownLatch(1);
        AtomicReference<Integer> bRead = new AtomicReference<>();

        Worker a = Worker.start("strict-a", () -> inTransaction(level, () -> {
            int read = Integer.parseInt(rows.getById("x").getName());
            aRead.countDown();
            awaitHandover(bReadReturned);
            update(rows, new LockRow("x", String.valueOf(read + 1)));
        }));
        Worker b = Worker.start("strict-b", () -> {
            awaitHandshake(aRead, "thread A never read");
            inTransaction(level, () -> {
                int read = Integer.parseInt(rows.getById("x").getName());
                bRead.set(read);
                bReadReturned.countDown();
                update(rows, new LockRow("x", String.valueOf(read + 1)));
            });
        });
        a.finish();
        b.finish();

        assertThat(bRead.get()).as("B's first read waited for A's commit").isEqualTo(1);
        assertThat(rows.getById("x").getName()).as("both increments are kept").isEqualTo("2");
    }

    /**
     * Exactly one of the two transactions failed with a deadlock less than one second after its
     * request and was rolled back; the other committed.
     */
    private void assertExactlyOneDeadlockLoser(Outcome o1, Outcome o2, String p1, String q1, String p2, String q2) {
        List<Outcome> losers = new ArrayList<>();
        for (Outcome outcome : Arrays.asList(o1, o2)) {
            if (outcome.failure.get() != null) {
                losers.add(outcome);
            }
        }
        assertThat(losers).as("exactly one transaction fails").hasSize(1);
        Outcome loser = losers.get(0);
        assertThat(loser.failure.get()).hasMessageContaining("deadlock");
        assertThat(TimeUnit.NANOSECONDS.toMillis(loser.failedAfterNanos.get()))
                .as("the deadlock is raised at once, not after the lock-wait bound").isLessThan(1000L);
        boolean t1Lost = loser == o1;
        assertThat(rows.getById(p1) != null).as(p1).isEqualTo(!t1Lost);
        assertThat(others.getById(q1) != null).as(q1).isEqualTo(!t1Lost);
        assertThat(rows.getById(p2) != null).as(p2).isEqualTo(t1Lost);
        assertThat(others.getById(q2) != null).as(q2).isEqualTo(t1Lost);
    }

    /** A step hook that counts down {@code latch} when the named thread registers as a lock waiter. */
    private static JsonTransaction.StepHook waiterRegisteredBy(String threadName, CountDownLatch latch) {
        return step -> {
            if (JsonLockTable.STEP_WAITER_REGISTERED.equals(step) && threadName.equals(Thread.currentThread().getName())) {
                latch.countDown();
            }
        };
    }

    /**
     * A {@link TimedBean} from a JSON-backed container: {@code PluginManager.wireAop} binds the
     * {@code @Transactional} advisor to the manager this test's store keeps for its scope.
     */
    private TimedBean timedBean() throws ReflectiveOperationException {
        SimpleContainer context = new SimpleContainer();
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            invokeWireAop(context, scope);
        }
        context.registerBean(TimedBean.class);
        context.refresh();
        TimedBean bean = context.getBean(TimedBean.class);
        bean.rows = rows;
        bean.others = others;
        bean.manager = manager;
        return bean;
    }

    /** {@code PluginManager.wireAop} is package-private in another package; reflection reaches the production method. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void invokeWireAop(SimpleContainer context, DataScope scope) throws ReflectiveOperationException {
        Method wireAop = PluginManager.class.getDeclaredMethod("wireAop", SimpleContainer.class, DataScope.class);
        wireAop.setAccessible(true);
        wireAop.invoke(null, context, scope);
    }

    /** What one deadlock-test transaction did: when it made its contested request, and how it failed. */
    private static final class Outcome {
        final AtomicLong requestedAt = new AtomicLong();
        final AtomicReference<DataAccessException> failure = new AtomicReference<>();
        final AtomicLong failedAfterNanos = new AtomicLong();

        void requested() {
            requestedAt.set(System.nanoTime());
        }

        void run(Runnable body) {
            try {
                body.run();
            } catch (DataAccessException e) {
                failedAfterNanos.set(System.nanoTime() - requestedAt.get());
                failure.set(e);
            }
        }
    }

    /** The container bean of {@link #timeoutDeadline()}; its operators are set after the container hands it out. */
    public static class TimedBean {
        volatile SimpleJsonDataOperator<LockRow> rows;
        volatile SimpleJsonDataOperator<LockOther> others;
        volatile JsonTransactionManager manager;
        final AtomicReference<Long> deadlineSeen = new AtomicReference<>();

        /** Writes, lets the one-second deadline pass by waiting for time on a latch nobody counts down, writes again. */
        @Transactional(timeout = 1)
        public void writeWaitForTimeThenWrite(String first, String second) {
            rows.insert(new LockRow(first, "before the deadline"));
            deadlineSeen.set(manager.getTimeoutDeadlineNanos());
            try {
                new CountDownLatch(1).await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rows.insert(new LockRow(second, "after the deadline"));
        }

        /** Writes a row, then writes a table another transaction holds. */
        @Transactional(timeout = 1)
        public void writeRowThenOther(String rowId, String otherId) {
            rows.insert(new LockRow(rowId, "row"));
            others.insert(new LockOther(otherId, "other"));
        }
    }

    /** A {@link CatchingBean} from a JSON-backed container, wired as {@link #timedBean()} wires its bean. */
    private CatchingBean catchingBean() throws ReflectiveOperationException {
        SimpleContainer context = new SimpleContainer();
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            invokeWireAop(context, scope);
        }
        context.registerBean(CatchingBean.class);
        context.refresh();
        CatchingBean bean = context.getBean(CatchingBean.class);
        bean.rows = rows;
        bean.others = others;
        return bean;
    }

    /** A container bean whose methods catch a lock failure in their body and return normally. */
    public static class CatchingBean {
        volatile SimpleJsonDataOperator<LockRow> rows;
        volatile SimpleJsonDataOperator<LockOther> others;
        final AtomicReference<DataAccessException> caught = new AtomicReference<>();

        /** Writes a row, then writes a table another transaction holds and catches the failure. */
        @Transactional
        public void writeRowThenCatchOtherFailure(String rowId, String otherId) {
            rows.insert(new LockRow(rowId, "row"));
            try {
                others.insert(new LockOther(otherId, "other"));
            } catch (DataAccessException e) {
                caught.set(e);
            }
        }

        /** Writes, lets the one-second deadline pass, then catches the refusal of a second write. */
        @Transactional(timeout = 1)
        public void timedWriteWaitThenCatchRefusal(String first, String second) {
            rows.insert(new LockRow(first, "before the deadline"));
            try {
                new CountDownLatch(1).await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                rows.insert(new LockRow(second, "after the deadline"));
            } catch (DataAccessException e) {
                caught.set(e);
            }
        }

        /** The same with a one-second deadline, which ends the lock wait before the 5-second default. */
        @Transactional(timeout = 1)
        public void timedWriteRowThenCatchOtherFailure(String rowId, String otherId) {
            rows.insert(new LockRow(rowId, "row"));
            try {
                others.insert(new LockOther(otherId, "other"));
            } catch (DataAccessException e) {
                caught.set(e);
            }
        }
    }

    private void rollbackIfStillActive() {
        if (manager.hasActiveTransaction()) {
            manager.rollback();
        }
    }

    private static void update(SimpleJsonDataOperator<LockRow> operator, LockRow row) {
        operator.update(row);
    }

    private static WhereCondition nameIs(String name) {
        return WhereCondition.builder().column("name").value(name).build();
    }

    private static List<String> names(List<LockRow> entities) {
        List<String> names = new ArrayList<>(entities.size());
        for (LockRow entity : entities) {
            names.add(entity.getName());
        }
        return names;
    }

    private static void awaitHandshake(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, e);
        }
    }

    /** A bounded handover window: a timed-out wait is not a failure. */
    private static void awaitHandover(CountDownLatch latch) {
        try {
            latch.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@link DataScope}'s constructor is package-private in another package. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static DataScope newDataScope(String pluginName, File dataFolder, Class<?>... owned)
            throws ReflectiveOperationException {
        Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, java.util.Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pluginName, dataFolder, new HashSet<>(Arrays.asList(owned)));
    }

    /** A test thread whose failure is captured and reported when it is joined. */
    private static final class Worker {
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Worker(String name, Runnable body) {
            this.thread = new Thread(() -> run(body), name);
        }

        // PMD.AvoidCatchingThrowable: an assertion failure or Error on the worker thread is
        // captured and rethrown on the JUnit thread by finish(), never swallowed.
        @SuppressWarnings("PMD.AvoidCatchingThrowable")
        private void run(Runnable body) {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }

        static Worker start(String name, Runnable body) {
            Worker worker = new Worker(name, body);
            worker.thread.start();
            return worker;
        }

        void finish() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS * 2));
            assertThat(thread.isAlive()).as(thread.getName() + " finished").isFalse();
            if (failure.get() != null) {
                throw new AssertionError(thread.getName() + " failed", failure.get());
            }
        }
    }

    // ===== Programmatic transaction(...) locks at first read (question 11 A) =====

    /**
     * The gate 1 reviewer's lost-update scenario through {@code DataOperator#transaction(Callable)}:
     * 6.2.5 ran each JSON {@code transaction(...)} callback alone per operator ({@code synchronized}),
     * so both increments were kept. Question 11 A restores that: a transaction begun by
     * {@code transaction(...)} locks a table at its first read, so B's read waits for A's commit.
     */
    @Test
    @DisplayName("transaction(Callable): two concurrent read-modify-write callbacks keep both updates (question 11 A)")
    void programmaticCallableTransactionReadModifyWriteKeepsBothUpdates() throws Exception {
        assertProgrammaticReadModifyWriteKeepsBothUpdates(body -> rows.transaction(() -> {
            body.run();
            return null;
        }));
    }

    @Test
    @DisplayName("transaction(Runnable): two concurrent read-modify-write callbacks keep both updates (question 11 A)")
    void programmaticRunnableTransactionReadModifyWriteKeepsBothUpdates() throws Exception {
        assertProgrammaticReadModifyWriteKeepsBothUpdates(body -> rows.transaction(body));
    }

    @Test
    @DisplayName("@Transactional default reads without waiting for a table a programmatic transaction holds")
    void transactionalDefaultReadDoesNotWaitForAProgrammaticTransactionsTableLock() throws Exception {
        assertReadDoesNotWaitWhileAProgrammaticTransactionHoldsTheTable(IsolationBean::readName);
    }

    /**
     * A {@code transaction(...)} nested in an active {@code @Transactional} transaction joins it and
     * keeps that transaction's isolation, as a participating scope does in Spring
     * ({@code AbstractPlatformTransactionManager} applies a definition's isolation only when it
     * starts a new transaction) and as a JDBC connection keeps the isolation its transaction began
     * with: the joined read takes no table lock at the default isolation.
     */
    @Test
    @DisplayName("transaction(...) nested in @Transactional default keeps read committed: its read does not wait")
    void nestedProgrammaticTransactionInsideTransactionalDefaultKeepsReadCommitted() throws Exception {
        assertReadDoesNotWaitWhileAProgrammaticTransactionHoldsTheTable(IsolationBean::readNameThroughNestedTransaction);
    }

    /**
     * X = 0; A reads X inside {@code entry} and waits until B has either read X or registered as a
     * waiter for the table; A then writes its read + 1 and commits; B writes its read + 1.
     */
    private void assertProgrammaticReadModifyWriteKeepsBothUpdates(ProgrammaticEntry entry) throws InterruptedException {
        rows.insert(new LockRow("x", "0"));
        CountDownLatch aRead = new CountDownLatch(1);
        CountDownLatch bReadOrWaiting = new CountDownLatch(1);
        AtomicReference<Integer> bRead = new AtomicReference<>();
        JsonLockTable.installStepHook(waiterRegisteredBy("programmatic-b", bReadOrWaiting));

        Worker a = Worker.start("programmatic-a", () -> runEntry(entry, () -> {
            int read = Integer.parseInt(rows.getById("x").getName());
            aRead.countDown();
            awaitHandshake(bReadOrWaiting, "thread B neither read nor waited for the table");
            update(rows, new LockRow("x", String.valueOf(read + 1)));
        }));
        Worker b = Worker.start("programmatic-b", () -> {
            awaitHandshake(aRead, "thread A never read");
            runEntry(entry, () -> {
                int read = Integer.parseInt(rows.getById("x").getName());
                bRead.set(read);
                bReadOrWaiting.countDown();
                update(rows, new LockRow("x", String.valueOf(read + 1)));
            });
        });
        a.finish();
        b.finish();

        assertThat(rows.getById("x").getName()).as("both increments are kept, as in 6.2.5").isEqualTo("2");
        assertThat(bRead.get()).as("B's first read waited for A's commit").isEqualTo(1);
    }

    /**
     * A programmatic transaction on another thread reads and writes X and keeps the table locked;
     * {@code read} runs on a reader thread through an {@link IsolationBean} method and must return
     * the committed value without registering as a lock waiter.
     */
    private void assertReadDoesNotWaitWhileAProgrammaticTransactionHoldsTheTable(BeanRead read) throws Exception {
        rows.insert(new LockRow("x", "committed"));
        IsolationBean bean = isolationBean();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch readerWaited = new CountDownLatch(1);
        CountDownLatch readDone = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();
        JsonLockTable.installStepHook(waiterRegisteredBy("isolation-reader", readerWaited));

        Worker holder = Worker.start("isolation-holder", () -> runEntry(body -> rows.transaction(body), () -> {
            rows.getById("x");
            update(rows, new LockRow("x", "staged"));
            holds.countDown();
            awaitHandshake(release, "the test never released the holder");
        }));
        awaitHandshake(holds, "the holder never locked the table");
        Worker reader = Worker.start("isolation-reader", () -> {
            try {
                seen.set(read.apply(bean, "x"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                readDone.countDown();
            }
        });

        boolean returned = readDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        long waiterRegistrations = readerWaited.getCount();
        release.countDown();
        holder.finish();
        reader.finish();

        assertThat(returned).as("the read returned while the programmatic transaction held the table").isTrue();
        assertThat(waiterRegistrations).as("the reader never registered as a lock waiter").isEqualTo(1L);
        assertThat(seen.get()).as("the read sees committed data").isEqualTo("committed");
        assertThat(rows.getById("x").getName()).as("the holder commits afterwards").isEqualTo("staged");
    }

    /** Runs {@code body} through {@code entry}, rethrowing a checked failure unchecked on the worker thread. */
    private static void runEntry(ProgrammaticEntry entry, Runnable body) {
        try {
            entry.run(body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An {@link IsolationBean} from a JSON-backed container, wired as {@link #timedBean()} wires its bean. */
    private IsolationBean isolationBean() throws ReflectiveOperationException {
        SimpleContainer context = new SimpleContainer();
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            invokeWireAop(context, scope);
        }
        context.registerBean(IsolationBean.class);
        context.refresh();
        IsolationBean bean = context.getBean(IsolationBean.class);
        bean.rows = rows;
        return bean;
    }

    /** One way into a programmatic transaction: {@code transaction(Callable)} or {@code transaction(Runnable)}. */
    @FunctionalInterface
    private interface ProgrammaticEntry {
        void run(Runnable body) throws Exception;
    }

    /** One read made through an {@link IsolationBean} method. */
    @FunctionalInterface
    private interface BeanRead {
        String apply(IsolationBean bean, String id) throws Exception;
    }

    /** A container bean whose {@code @Transactional} methods (default isolation) read a row. */
    public static class IsolationBean {
        volatile SimpleJsonDataOperator<LockRow> rows;

        /** Reads a row in a {@code @Transactional} transaction at the default isolation. */
        @Transactional
        public String readName(String id) {
            return rows.getById(id).getName();
        }

        /** Reads a row through a {@code transaction(...)} joined to a default-isolation {@code @Transactional} one. */
        @Transactional
        public String readNameThroughNestedTransaction(String id) throws Exception {
            return rows.transaction(() -> rows.getById(id).getName());
        }
    }

    // ===== Fixtures =====

    @Table("locking_rows")
    public static class LockRow extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public LockRow() {
        }

        public LockRow(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Table("locking_other")
    public static class LockOther extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public LockOther() {
        }

        public LockOther(String id, String label) {
            setId(id);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
