package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.DataScope;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Reproductions of the three JSON transaction cases of Phase 20.4 (C03, C10, C12) and the
 * isolation guarantee of the JSON transaction engine, each run through a JSON-backed container:
 * {@code PluginManager.wireAop} (reached reflectively, as {@code AopActivationTest} does) binds the
 * {@code @Transactional} advisor to the manager a real {@link JsonStore} keeps for the test's
 * {@link DataScope}, and the bean's operators come from that same store for that same scope.
 * <p>
 * Every case asserts that a write which returned normally is still committed afterwards.
 * <p>
 * Lock compatibility: no thread that has written a table ever waits unboundedly for another
 * thread's write to that same table. Where a case needs another thread's write to land while a
 * transaction is still open, the transaction waits for it at most a one-second handover window
 * and then continues; the window's {@code false} result is not a failure. Once JSON transactions
 * take table write locks, the other thread's write waits for the open transaction's lock instead
 * and proceeds as soon as that transaction has ended, well inside the lock-wait bound.
 */
@Timeout(60)
@DisplayName("JSON transaction engine: C03, C10, C12 and isolation, through a JSON-backed container")
class JsonTransactionEngineTest {

    /** Bounded wait for every handshake that must happen. */
    private static final long HANDSHAKE_SECONDS = 10;

    /** The handover window a transaction gives another thread's write before it continues. */
    private static final long HANDOVER_WINDOW_SECONDS = 1;

    @TempDir
    Path tempDir;

    private EngineBean bean;
    private DataOperator<EngineRow> rows;
    private DataOperator<EngineOther> others;

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
    void assembleJsonBackedContainer() throws Exception {
        File dataFolder = Files.createDirectory(tempDir.resolve("plugin-data")).toFile();
        DataScope scope = newDataScope("json-engine-test", dataFolder, EngineRow.class, EngineOther.class);
        JsonStore store;
        SimpleContainer context = new SimpleContainer();
        try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
            UltiTools mockUltiTools = mock(UltiTools.class);
            store = new JsonStore(tempDir.resolve("store").toString());
            when(mockUltiTools.getDataStore()).thenReturn(store);
            ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            invokeWireAop(context, scope);
            rows = store.getOperator(scope, EngineRow.class);
            others = store.getOperator(scope, EngineOther.class);
        }
        context.registerBean(EngineBean.class);
        context.refresh();
        bean = context.getBean(EngineBean.class);
        bean.rows = rows;
    }

    // ===== C12 =====

    @Test
    @DisplayName("C12: a REQUIRES_NEW method's commit on the same operator survives the outer rollback")
    void c12_requiresNewSameOperatorInnerCommitSurvives() {
        assertThatThrownBy(() -> bean.writeThenRequiresNewThenFail("c12-x", "c12-y"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows.getById("c12-y"))
                .as("Y was written by a REQUIRES_NEW method that returned normally, so it is committed")
                .isNotNull();
        assertThat(rows.getById("c12-x"))
                .as("X was written by the outer transaction, which rolled back")
                .isNull();
    }

    // ===== C03 =====

    @Test
    @DisplayName("C03: a failed @Transactional call cannot delete another thread's committed @Transactional write")
    void c03_failedTransactionCannotDeleteAnotherThreadsCommit() throws Exception {
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<Throwable> aFailure = new AtomicReference<>();
        AtomicReference<Throwable> bFailure = new AtomicReference<>();
        AtomicBoolean bReturned = new AtomicBoolean();

        // Thread A never waits for B's write to finish: it gives B a one-second handover window
        // and then rolls back. Once table write locks exist, B's write waits for A's lock and
        // proceeds as soon as A has rolled back, well inside the five-second lock-wait bound.
        Thread a = new Thread(() -> {
            try {
                bean.writeHandOverThenFail("c03-a", aWrote, bDone);
            } catch (Throwable t) {
                aFailure.set(t);
            }
        }, "c03-thread-a");
        Thread b = new Thread(() -> {
            try {
                if (!aWrote.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                    bFailure.set(new AssertionError("thread A never wrote"));
                    return;
                }
                bean.write("c03-b");
                bReturned.set(true);
            } catch (Throwable t) {
                bFailure.set(t);
            } finally {
                bDone.countDown();
            }
        }, "c03-thread-b");

        a.start();
        b.start();
        a.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        b.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));

        assertThat(a.isAlive()).as("thread A finished").isFalse();
        assertThat(b.isAlive()).as("thread B finished").isFalse();
        assertThat(aFailure.get()).as("A's transaction failed as scripted").isInstanceOf(IllegalStateException.class);
        assertThat(bFailure.get()).as("B's call raised nothing").isNull();
        assertThat(bReturned.get()).as("B's @Transactional call returned normally").isTrue();
        assertThat(rows.getById("c03-b"))
                .as("B's write returned normally from a committed transaction, so A's rollback must not delete it")
                .isNotNull();
        assertThat(rows.getById("c03-a")).as("A's own write is rolled back").isNull();
    }

    // ===== C10 =====

    @Test
    @DisplayName("C10: a failed @Transactional call cannot delete a plain write another thread made meanwhile")
    void c10_failedTransactionCannotDeleteAPlainWrite() throws Exception {
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicReference<Throwable> aFailure = new AtomicReference<>();
        AtomicReference<Throwable> bFailure = new AtomicReference<>();
        AtomicBoolean bReturned = new AtomicBoolean();

        // Same handover as C03: A waits for B's plain insert at most one second, then rolls back.
        // Once table write locks exist, B's plain insert waits for A's lock and proceeds as soon
        // as A has rolled back, well inside the five-second lock-wait bound.
        Thread a = new Thread(() -> {
            try {
                bean.writeHandOverThenFail("c10-a", aWrote, bDone);
            } catch (Throwable t) {
                aFailure.set(t);
            }
        }, "c10-thread-a");
        Thread b = new Thread(() -> {
            try {
                if (!aWrote.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                    bFailure.set(new AssertionError("thread A never wrote"));
                    return;
                }
                rows.insert(new EngineRow("c10-b", "plain"));
                bReturned.set(true);
            } catch (Throwable t) {
                bFailure.set(t);
            } finally {
                bDone.countDown();
            }
        }, "c10-thread-b");

        a.start();
        b.start();
        a.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        b.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));

        assertThat(a.isAlive()).as("thread A finished").isFalse();
        assertThat(b.isAlive()).as("thread B finished").isFalse();
        assertThat(aFailure.get()).as("A's transaction failed as scripted").isInstanceOf(IllegalStateException.class);
        assertThat(bFailure.get()).as("B's insert raised nothing").isNull();
        assertThat(bReturned.get()).as("B's plain insert returned normally").isTrue();
        assertThat(rows.getById("c10-b"))
                .as("B's plain insert returned normally, so A's rollback must not delete it")
                .isNotNull();
        assertThat(rows.getById("c10-a")).as("A's own write is rolled back").isNull();
    }

    // ===== Isolation =====

    @Test
    @DisplayName("Another thread sees only committed data while a transaction is open, and every change after commit")
    void otherThreadNeverSeesStagedWrites() throws Exception {
        rows.insert(new EngineRow("keep", "old"));
        rows.insert(new EngineRow("gone", "old"));
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                bean.runInTransaction(() -> {
                    rows.insert(new EngineRow("new", "fresh"));
                    updateQuietly(rows, new EngineRow("keep", "changed"));
                    rows.delById("gone");
                    others.insert(new EngineOther("other-new", "fresh"));
                    staged.countDown();
                    awaitQuietly(release);
                });
            } catch (Throwable t) {
                writerFailure.set(t);
            }
        }, "isolation-writer");
        writer.start();
        try {
            assertThat(staged.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("the writer staged its changes").isTrue();

            // This thread only reads.
            assertThat(rows.getById("new")).as("staged insert, getById").isNull();
            assertThat(rows.getById("keep").getName()).as("staged update, getById").isEqualTo("old");
            assertThat(rows.getById("gone")).as("staged delete, getById").isNotNull();
            assertThat(others.getById("other-new")).as("staged insert on the second table, getById").isNull();
            assertThat(rows.getAll(nameIs("changed"))).as("staged update, getAll(conditions)").isEmpty();
            assertThat(rows.getAll(nameIs("fresh"))).as("staged insert, getAll(conditions)").isEmpty();
            assertThat(ids(rows.getAll(nameIs("old")))).as("committed rows, getAll(conditions)")
                    .containsExactlyInAnyOrder("keep", "gone");
            assertThat(ids(rows.query().where("name").eq("old").list())).as("committed rows, query().list()")
                    .containsExactlyInAnyOrder("keep", "gone");
            assertThat(others.getAll(WhereCondition.builder().column("label").value("fresh").build()))
                    .as("staged insert on the second table, getAll(conditions)").isEmpty();
            assertThat(others.query().list()).as("staged insert on the second table, query().list()").isEmpty();
        } finally {
            release.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        }

        assertThat(writer.isAlive()).as("writer finished").isFalse();
        assertThat(writerFailure.get()).as("the writer's transaction committed").isNull();
        assertThat(rows.getById("new")).as("insert after commit").isNotNull();
        assertThat(rows.getById("keep").getName()).as("update after commit").isEqualTo("changed");
        assertThat(rows.getById("gone")).as("delete after commit").isNull();
        assertThat(others.getById("other-new")).as("second table's insert after commit").isNotNull();
        assertThat(ids(rows.query().where("name").eq("fresh").list())).as("query().list() after commit")
                .containsExactly("new");
    }

    // ===== Helpers =====

    private static WhereCondition nameIs(String name) {
        return WhereCondition.builder().column("name").value(name).build();
    }

    private static List<String> ids(List<? extends BaseDataEntity<String>> entities) {
        String[] ids = new String[entities.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = entities.get(i).getId();
        }
        return Arrays.asList(ids);
    }

    /** {@code DataOperator#update(T)} declares a checked exception a {@link Runnable} cannot throw. */
    static <T extends BaseDataEntity<String>> void updateQuietly(DataOperator<T> operator, T entity) {
        try {
            operator.update(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code PluginManager.wireAop} is package-private in another package; reflection reaches the
     * production method itself, exactly as {@code AopActivationTest.invokeWireAop} does.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static void invokeWireAop(SimpleContainer context, DataScope scope) throws ReflectiveOperationException {
        Method wireAop = PluginManager.class.getDeclaredMethod("wireAop", SimpleContainer.class, DataScope.class);
        wireAop.setAccessible(true);
        wireAop.invoke(null, context, scope);
    }

    /** {@link DataScope}'s constructor is package-private in another package. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    static DataScope newDataScope(String pluginName, File dataFolder, Class<?>... owned)
            throws ReflectiveOperationException {
        Constructor<DataScope> ctor = DataScope.class.getDeclaredConstructor(String.class, File.class, java.util.Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pluginName, dataFolder, new HashSet<>(Arrays.asList(owned)));
    }

    // ===== Fixtures =====

    @Table("engine_rows")
    public static class EngineRow extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public EngineRow() {
        }

        public EngineRow(String id, String name) {
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

    @Table("engine_other")
    public static class EngineOther extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public EngineOther() {
        }

        public EngineOther(String id, String label) {
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

    /**
     * The container bean every case runs through. Its operator is set after the container hands
     * the (proxied) bean out, because the operator comes from the same store and scope that
     * {@code wireAop} bound the advisor to.
     */
    public static class EngineBean {
        volatile DataOperator<EngineRow> rows;

        /** C12: writes X, self-invokes a REQUIRES_NEW method that writes Y and returns, then fails. */
        @Transactional
        public void writeThenRequiresNewThenFail(String x, String y) {
            rows.insert(new EngineRow(x, "outer"));
            writeInNewTransaction(y);
            throw new IllegalStateException("outer transaction fails after the inner REQUIRES_NEW commit");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void writeInNewTransaction(String y) {
            rows.insert(new EngineRow(y, "inner"));
        }

        /**
         * C03/C10 thread A: writes, signals, gives the other thread's write a bounded handover
         * window (a timed-out wait is not a failure), then fails so the transaction rolls back.
         */
        @Transactional
        public void writeHandOverThenFail(String id, CountDownLatch wrote, CountDownLatch otherDone) {
            rows.insert(new EngineRow(id, "rolled-back"));
            wrote.countDown();
            try {
                otherDone.await(HANDOVER_WINDOW_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("transaction A fails after the handover window");
        }

        /** C03 thread B: one write in its own transaction, returning normally. */
        @Transactional
        public void write(String id) {
            rows.insert(new EngineRow(id, "committed"));
        }

        /** Runs {@code body} inside one transaction of this bean's manager. */
        @Transactional
        public void runInTransaction(Runnable body) {
            body.run();
        }
    }
}
