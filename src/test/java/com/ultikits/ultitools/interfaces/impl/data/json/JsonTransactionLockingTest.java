package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
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
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.JsonTransactionManager;

/**
 * Table write locks of the JSON transaction engine (questions 6 and 7 of the Phase 20.4
 * decisions, both answered with option A).
 * <p>
 * A transaction's first write to a table locks that table against every other writer until the
 * transaction commits or rolls back; a plain write takes the same lock for the duration of the
 * call. Readers at the default isolation never wait for a lock. A lock wait is bounded.
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

    private static final String A_COMMITTED = "A committed";
    private static final String B_UPDATE_RETURNED = "B update returned";
    private static final String B_PLAIN_UPDATE_RETURNED = "B plain update returned";

    @TempDir
    Path tempDir;

    private JsonStore store;
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
        DataScope scope = newDataScope("json-locking-test", dataFolder, LockRow.class, LockOther.class);
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
