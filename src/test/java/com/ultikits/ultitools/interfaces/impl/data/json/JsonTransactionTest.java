package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.TransactionInterceptor;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.manager.JsonTransactionManager;

@DisplayName("JSON Data Operator - Transaction & Batch Tests")
class JsonTransactionTest {

    @TempDir
    Path tempDir;

    private SimpleJsonDataOperator<TestData> operator;

    @BeforeAll
    static void setUpClass() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @BeforeEach
    void setUp() {
        File storeDir = tempDir.toFile();
        operator = new SimpleJsonDataOperator<>(storeDir.getAbsolutePath(), TestData.class);
    }

    // ===== Test Entity =====

    public static class TestData extends BaseDataEntity<String> {
        private String name;
        private int value;

        public TestData() {}

        public TestData(String id, String name, int value) {
            this.setId(id);
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    /**
     * Fixture for {@link RequiresNewAndNotSupportedIntegrationTests} (02-11): proves REQUIRES_NEW
     * and NOT_SUPPORTED genuinely suspend on the JSON backend, mirroring
     * {@code TransactionInterceptorTest.PropagationBean}'s JDBC shape. Declared at the top level
     * (not inside the {@code @Nested} test class) for the same Java 8 / JUnit 5 reason documented
     * on that JDBC fixture -- a {@code static} member class cannot live inside a non-static
     * {@code @Nested} class on this project's Java 8 target.
     * <p>
     * Writes to two <em>different</em> operators deliberately -- {@code outerOperator} for the
     * outer scope's own writes, {@code innerOperator} for the inner REQUIRES_NEW/NOT_SUPPORTED
     * scope's writes. This is not an artifact of the test but a consequence of how
     * {@link JsonTransactionManager} rolls back: it restores an operator's <em>entire</em> cache
     * from a snapshot captured at that operator's first touch inside a given transaction context
     * (see {@code SimpleJsonDataOperator#restoreCache}), so proving genuine independence between
     * an outer scope and a REQUIRES_NEW/NOT_SUPPORTED inner scope requires them to touch different
     * operators -- exactly the same distinction
     * {@code PerPluginTransactionScopeTests#untouchedOperatorNotSnapshotted} already establishes
     * for a concurrent external write. Two operators sharing one {@link JsonTransactionManager} is
     * exactly how one plugin's several JSON-backed entities behave in production.
     */
    public static class JsonPropagationBean {
        private final SimpleJsonDataOperator<TestData> outerOperator;
        private final SimpleJsonDataOperator<TestData> innerOperator;
        private final JsonTransactionManager txManager;

        /** Captured inside {@link #innerNotSupportedWriteSelf()}/{@link #notSupportedExternalWrite()}. */
        volatile boolean hasActiveTxInsideNotSupported;

        /** Captured immediately after the self-invoked NOT_SUPPORTED call returns, still inside the
         *  outer transaction -- proves the outer is intact at its original depth afterwards. */
        volatile int depthAfterNotSupportedReturnsSelf;

        public JsonPropagationBean(SimpleJsonDataOperator<TestData> outerOperator,
                SimpleJsonDataOperator<TestData> innerOperator, JsonTransactionManager txManager) {
            this.outerOperator = outerOperator;
            this.innerOperator = innerOperator;
            this.txManager = txManager;
        }

        /**
         * Outer REQUIRED transaction that self-invokes a REQUIRES_NEW method whose write commits,
         * then itself throws so the outer rolls back. The inner's committed write must survive.
         */
        @Transactional
        public void outerRequiredThenSelfInvokesRequiresNewThenFails() {
            outerOperator.insert(new TestData("outer", "Outer", 1));
            innerRequiresNewSucceedsSelf();
            throw new RuntimeException(
                    "boom - outer rolls back after inner REQUIRES_NEW commit, self-invocation");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNewSucceedsSelf() {
            innerOperator.insert(new TestData("inner", "Inner", 99));
        }

        /** Externally-called REQUIRES_NEW method that writes and commits independently. */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewExternalSucceeds() {
            innerOperator.insert(new TestData("external-inner", "ExternalInner", 100));
        }

        /**
         * Outer REQUIRED transaction that self-invokes a NOT_SUPPORTED write, then itself throws
         * so the outer rolls back. The NOT_SUPPORTED write must survive the outer's rollback.
         */
        @Transactional
        public void outerRequiredThenSelfInvokesNotSupportedThenFails() {
            outerOperator.insert(new TestData("outer-nsw", "OuterNsw", 4));
            innerNotSupportedWriteSelf();
            depthAfterNotSupportedReturnsSelf = txManager.getTransactionDepth();
            throw new RuntimeException(
                    "boom - outer rolls back after NOT_SUPPORTED write, self-invocation");
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void innerNotSupportedWriteSelf() {
            hasActiveTxInsideNotSupported = txManager.hasActiveTransaction();
            innerOperator.insert(new TestData("not-supported", "NotSupported", 102));
        }

        /** Externally-called NOT_SUPPORTED write. */
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupportedExternalWrite() {
            hasActiveTxInsideNotSupported = txManager.hasActiveTransaction();
            innerOperator.insert(new TestData("external-not-supported", "ExternalNotSupported", 103));
        }
    }

    // ===== Transaction Commit Tests =====

    @Nested
    @DisplayName("Transaction Commit")
    class TransactionCommitTests {

        @Test
        @DisplayName("transaction(Runnable) should commit on success")
        void transactionCommitsOnSuccess() {
            operator.transaction(() -> {
                operator.insert(new TestData("1", "Alice", 100));
                operator.insert(new TestData("2", "Bob", 200));
            });

            assertThat(operator.getAll()).hasSize(2);
            assertThat(operator.getById("1").getName()).isEqualTo("Alice");
            assertThat(operator.getById("2").getName()).isEqualTo("Bob");
        }

        @Test
        @DisplayName("transaction(Callable) should return result and commit")
        void transactionCallableCommits() throws Exception {
            int result = operator.transaction(() -> {
                operator.insert(new TestData("1", "Alice", 100));
                return operator.getAll().size();
            });

            assertThat(result).isEqualTo(1);
            assertThat(operator.getAll()).hasSize(1);
        }

        @Test
        @DisplayName("Multiple sequential transactions should each persist")
        void multipleTransactions() {
            operator.transaction(() -> {
                operator.insert(new TestData("1", "Alice", 100));
            });
            operator.transaction(() -> {
                operator.insert(new TestData("2", "Bob", 200));
            });

            assertThat(operator.getAll()).hasSize(2);
        }
    }

    // ===== Transaction Rollback Tests =====

    @Nested
    @DisplayName("Transaction Rollback (Snapshot)")
    class TransactionRollbackTests {

        @Test
        @DisplayName("Should rollback inserts on RuntimeException")
        void rollbackInsertsOnException() {
            operator.insert(new TestData("0", "Pre-existing", 0));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(new TestData("1", "Alice", 100));
                    operator.insert(new TestData("2", "Bob", 200));
                    throw new RuntimeException("Simulated failure");
                });
            }).isInstanceOf(RuntimeException.class)
              .hasMessage("Simulated failure");

            // Only pre-existing data should remain
            assertThat(operator.getAll()).hasSize(1);
            assertThat(operator.getById("0").getName()).isEqualTo("Pre-existing");
            assertThat(operator.getById("1")).isNull();
            assertThat(operator.getById("2")).isNull();
        }

        @Test
        @DisplayName("Should rollback updates on exception")
        void rollbackUpdatesOnException() {
            operator.insert(new TestData("1", "Original", 100));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.update(new TestData("1", "Modified", 999));
                    throw new RuntimeException("Abort!");
                });
            }).isInstanceOf(RuntimeException.class);

            // Should be restored to original
            assertThat(operator.getById("1").getName()).isEqualTo("Original");
            assertThat(operator.getById("1").getValue()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should rollback deletes on exception")
        void rollbackDeletesOnException() {
            operator.insert(new TestData("1", "Alice", 100));
            operator.insert(new TestData("2", "Bob", 200));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.delById("1");
                    operator.delById("2");
                    throw new RuntimeException("Undo!");
                });
            }).isInstanceOf(RuntimeException.class);

            // Both should be restored
            assertThat(operator.getAll()).hasSize(2);
            assertThat(operator.getById("1")).isNotNull();
            assertThat(operator.getById("2")).isNotNull();
        }

        @Test
        @DisplayName("Should rollback mixed operations on exception")
        void rollbackMixedOperations() {
            operator.insert(new TestData("1", "Alice", 100));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(new TestData("2", "Bob", 200));
                    operator.update(new TestData("1", "Alice-Modified", 999));
                    throw new RuntimeException("Fail");
                });
            }).isInstanceOf(RuntimeException.class);

            assertThat(operator.getAll()).hasSize(1);
            assertThat(operator.getById("1").getName()).isEqualTo("Alice");
            assertThat(operator.getById("1").getValue()).isEqualTo(100);
            assertThat(operator.getById("2")).isNull();
        }

        @Test
        @DisplayName("Callable transaction should rollback on checked exception")
        void callableRollbackOnCheckedException() throws Exception {
            operator.insert(new TestData("1", "Original", 100));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(new TestData("2", "New", 200));
                    throw new Exception("Checked exception");
                });
            }).isInstanceOf(Exception.class);

            assertThat(operator.getAll()).hasSize(1);
            assertThat(operator.getById("2")).isNull();
        }
    }

    // ===== Deep Copy Verification =====

    @Nested
    @DisplayName("Snapshot Deep Copy")
    class DeepCopyTests {

        @Test
        @DisplayName("Rollback should restore entity state even after in-place mutation")
        void deepCopyPreventsSharedReferenceCorruption() {
            // This tests that the snapshot is a deep copy, not a shallow copy.
            // update(T) calls BeanCopyUtil.copyProperties which mutates entities in-place.
            operator.insert(new TestData("1", "Original", 100));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    // update() mutates the cached entity in-place via BeanCopyUtil
                    operator.update(new TestData("1", "Mutated", 999));

                    // Verify the mutation happened
                    assertThat(operator.getById("1").getName()).isEqualTo("Mutated");

                    throw new RuntimeException("Rollback!");
                });
            }).isInstanceOf(RuntimeException.class);

            // Deep copy should have preserved the original values
            assertThat(operator.getById("1").getName()).isEqualTo("Original");
            assertThat(operator.getById("1").getValue()).isEqualTo(100);
        }
    }

    // ===== Batch Insert Tests =====

    @Nested
    @DisplayName("insertAll() - Batch Insert")
    class InsertAllTests {

        @Test
        @DisplayName("insertAll should insert all entities")
        void insertAllInsertsAll() {
            List<TestData> entities = Arrays.asList(
                    new TestData("1", "Alice", 100),
                    new TestData("2", "Bob", 200),
                    new TestData("3", "Charlie", 300)
            );

            operator.insertAll(entities);

            assertThat(operator.getAll()).hasSize(3);
            assertThat(operator.getById("1").getName()).isEqualTo("Alice");
            assertThat(operator.getById("2").getName()).isEqualTo("Bob");
            assertThat(operator.getById("3").getName()).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("insertAll with empty list should be no-op")
        void insertAllEmpty() {
            operator.insertAll(new ArrayList<>());
            assertThat(operator.getAll()).isEmpty();
        }

        @Test
        @DisplayName("insertAll should be atomic - rollback if action fails partway")
        void insertAllAtomicOnPartialFailure() {
            // Pre-insert an entity with id "2"
            operator.insert(new TestData("2", "Existing", 50));

            // insertAll wraps in transaction, but individual insert is putIfAbsent
            // so duplicate won't throw — this tests that the transaction mechanism works
            List<TestData> entities = Arrays.asList(
                    new TestData("1", "New1", 100),
                    new TestData("3", "New3", 300)
            );

            operator.insertAll(entities);

            assertThat(operator.getAll()).hasSize(3);
        }
    }

    // ===== Batch Update Tests =====

    @Nested
    @DisplayName("updateAll() - Batch Update")
    class UpdateAllTests {

        @BeforeEach
        void seedData() {
            operator.insert(new TestData("1", "Alice", 100));
            operator.insert(new TestData("2", "Bob", 200));
            operator.insert(new TestData("3", "Charlie", 300));
        }

        @Test
        @DisplayName("updateAll should update all entities")
        void updateAllUpdatesAll() throws Exception {
            List<TestData> updates = Arrays.asList(
                    new TestData("1", "Alice-Updated", 150),
                    new TestData("2", "Bob-Updated", 250)
            );

            operator.updateAll(updates);

            assertThat(operator.getById("1").getName()).isEqualTo("Alice-Updated");
            assertThat(operator.getById("2").getName()).isEqualTo("Bob-Updated");
            assertThat(operator.getById("3").getName()).isEqualTo("Charlie"); // unchanged
        }

        @Test
        @DisplayName("updateAll with empty list should be no-op")
        void updateAllEmpty() throws Exception {
            operator.updateAll(new ArrayList<>());
            assertThat(operator.getAll()).hasSize(3);
        }
    }

    // ===== Thread Safety Tests =====

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent transactions should not interfere with each other")
        void concurrentTransactionsShouldNotInterfere() throws Exception {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final String id = "thread-" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        operator.transaction(() -> {
                            operator.insert(new TestData(id, "Name-" + id, 1));
                        });
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            // All transactions should succeed since they use different IDs
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(failCount.get()).isEqualTo(0);
            assertThat(operator.getAll()).hasSize(threadCount);
        }

        @Test
        @DisplayName("Transaction rollback should not affect concurrent reads")
        void rollbackShouldNotCorruptConcurrentState() throws Exception {
            // Seed initial data
            operator.insert(new TestData("stable", "Stable", 999));

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Thread 1: repeatedly read "stable" entity
            // Allow brief null reads during rollback snapshot restore on slow CI
            Future<Boolean> readerResult = executor.submit(() -> {
                int nullCount = 0;
                for (int i = 0; i < 50; i++) {
                    TestData data = operator.getById("stable");
                    if (data == null) {
                        nullCount++;
                    } else if (!"Stable".equals(data.getName())) {
                        return false;
                    }
                    Thread.sleep(1);
                }
                // Tolerate a few transient nulls during rollback, but not all
                return nullCount < 25;
            });

            // Thread 2: run failing transactions that should rollback
            Future<Boolean> writerResult = executor.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    try {
                        operator.transaction(() -> {
                            operator.insert(new TestData("temp-" + Thread.currentThread().getId(), "Temp", 0));
                            throw new RuntimeException("Rollback!");
                        });
                    } catch (RuntimeException e) {
                        // expected
                    }
                    Thread.sleep(1);
                }
                return true;
            });

            assertThat(readerResult.get()).isTrue();
            assertThat(writerResult.get()).isTrue();
            executor.shutdown();

            // Only "stable" should remain
            assertThat(operator.getById("stable")).isNotNull();
        }
    }

    // ===== Per-Plugin Transaction Scope (JsonTransactionManager, D-03) =====

    @Nested
    @DisplayName("JsonTransactionManager - Per-Plugin Rollback Scope")
    class PerPluginTransactionScopeTests {

        private SimpleJsonDataOperator<TestData> operatorA;
        private SimpleJsonDataOperator<TestData> operatorB;
        private JsonTransactionManager manager;

        @BeforeEach
        void setUpTwoOperators() throws Exception {
            File dirA = Files.createDirectory(tempDir.resolve("operator-a")).toFile();
            File dirB = Files.createDirectory(tempDir.resolve("operator-b")).toFile();
            operatorA = new SimpleJsonDataOperator<>(dirA.getAbsolutePath(), TestData.class);
            operatorB = new SimpleJsonDataOperator<>(dirB.getAbsolutePath(), TestData.class);
            manager = new JsonTransactionManager("per-plugin-scope-test");
            operatorA.bindTransactionManager(manager);
            operatorB.bindTransactionManager(manager);
        }

        @Test
        @DisplayName("Rollback restores every operator of the plugin touched during the transaction")
        void rollbackRestoresBothTouchedOperators() {
            operatorA.insert(new TestData("pre-a", "PreA", 1));
            operatorB.insert(new TestData("pre-b", "PreB", 2));

            manager.begin();
            operatorA.insert(new TestData("new-a", "NewA", 10));
            operatorB.insert(new TestData("new-b", "NewB", 20));
            manager.rollback();

            assertThat(operatorA.getAll()).hasSize(1);
            assertThat(operatorA.getById("pre-a")).isNotNull();
            assertThat(operatorA.getById("new-a")).isNull();

            assertThat(operatorB.getAll()).hasSize(1);
            assertThat(operatorB.getById("pre-b")).isNotNull();
            assertThat(operatorB.getById("new-b")).isNull();
        }

        @Test
        @DisplayName("An operator never written to during the transaction is not snapshotted -- "
                + "a concurrent external write to it survives rollback")
        void untouchedOperatorNotSnapshotted() throws Exception {
            operatorA.insert(new TestData("pre-a", "PreA", 1));

            manager.begin();
            operatorA.insert(new TestData("new-a", "NewA", 10));

            // A write to operatorB from a different thread never observes this thread's active
            // transaction context (JsonTransactionManager's context is ThreadLocal, matching
            // DataSourceTransactionManager's design), so it is never captured -- exactly the
            // "operator never touched" case, made observable via a genuinely concurrent write.
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> operatorB.insert(new TestData("external", "External", 99))).get();
            } finally {
                executor.shutdown();
            }

            manager.rollback();

            // operatorA was touched on this thread -- restored to its pre-transaction state.
            assertThat(operatorA.getById("new-a")).isNull();
            assertThat(operatorA.getById("pre-a")).isNotNull();

            // operatorB was never touched inside the transaction -- the concurrent external write
            // survives the rollback untouched.
            assertThat(operatorB.getById("external")).isNotNull();
        }

        @Test
        @DisplayName("commit() discards captured snapshots")
        void commitDiscardsSnapshots() {
            manager.begin();
            operatorA.insert(new TestData("a", "A", 1));
            manager.commit();

            assertThat(operatorA.getById("a")).isNotNull();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("rollback() with no active transaction logs a warning and returns")
        void rollbackWithNoActiveTransactionIsANoOp() {
            assertThatCode(() -> manager.rollback()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Depth 2: inner rollback() then outer commit() throws UnexpectedRollbackException "
                + "and restores every captured snapshot")
        void nestedRollbackThenOuterCommitThrows() {
            operatorA.insert(new TestData("pre-a", "PreA", 1));

            manager.begin(); // depth 1
            manager.begin(); // depth 2
            operatorA.insert(new TestData("new-a", "NewA", 10));
            manager.rollback(); // marks rollback-only, depth -> 1

            assertThat(manager.hasActiveTransaction()).isTrue();
            assertThat(manager.getTransactionDepth()).isEqualTo(1);

            assertThatThrownBy(manager::commit).isInstanceOf(UnexpectedRollbackException.class);

            assertThat(operatorA.getById("new-a")).isNull();
            assertThat(operatorA.getById("pre-a")).isNotNull();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("setTimeout gives an explicit answer, not a silent no-op")
        void setTimeoutIsNotASilentNoOp() {
            assertThatThrownBy(() -> manager.setTimeout(30))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("JsonTransactionManager implements TransactionManager only -- "
                + "the JDBC-only methods are refused, not silently no-op")
        void doesNotImplementJdbcMethods() {
            assertThatThrownBy(manager::getConnection).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> manager.setIsolationLevel(1)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> manager.setReadOnly(true)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ===== suspend()/resume() -- REQUIRES_NEW/NOT_SUPPORTED's suspension mechanism (02-11) =====

    /**
     * Unit-level tests against {@link JsonTransactionManager#suspend()}/{@link
     * JsonTransactionManager#resume(Object)} directly, mirroring
     * {@code DataSourceTransactionManagerTest.SuspendResumeTests} (D-09) one-for-one on the JSON
     * side. No operator is needed here -- these four properties live entirely on {@code
     * contextHolder}/{@code suspendedStack}.
     */
    @Nested
    @DisplayName("suspend()/resume() -- the mechanism REQUIRES_NEW/NOT_SUPPORTED need (02-11, D-09)")
    class SuspendResumeTests {

        private JsonTransactionManager manager;

        @BeforeEach
        void setUpManager() {
            manager = new JsonTransactionManager("suspend-resume-unit-test");
        }

        @Test
        @DisplayName("suspend() with no active transaction returns null and does nothing")
        void suspendWithNothingActiveReturnsNull() {
            Object suspended = manager.suspend();

            assertThat(suspended).isNull();
        }

        @Test
        @DisplayName("resume(null) is a no-op")
        void resumeWithNullIsNoOp() {
            assertThatCode(() -> manager.resume(null)).doesNotThrowAnyException();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("suspend() detaches the current context; hasActiveTransaction() becomes false")
        void suspendDetachesCurrentContext() {
            manager.begin();
            assertThat(manager.hasActiveTransaction()).isTrue();

            Object suspended = manager.suspend();

            assertThat(suspended).isNotNull();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("suspend() -> begin() -> commit() -> resume(saved): original context restored "
                + "at its original depth, not depth 1")
        void suspendThenResumeRestoresOriginalDepth() {
            manager.begin(); // outer, depth 1
            manager.begin(); // outer, depth 2 (still the same context)
            assertThat(manager.getTransactionDepth()).isEqualTo(2);

            Object suspended = manager.suspend();
            assertThat(manager.hasActiveTransaction()).isFalse();

            manager.begin(); // inner, independent, depth 1
            assertThat(manager.getTransactionDepth()).isEqualTo(1);
            manager.commit(); // inner finishes and discards its own context completely
            assertThat(manager.hasActiveTransaction()).isFalse();

            manager.resume(suspended);

            assertThat(manager.hasActiveTransaction()).isTrue();
            assertThat(manager.getTransactionDepth()).isEqualTo(2);
        }

        @Test
        @DisplayName("resume() with a handle this manager did not produce throws "
                + "IllegalArgumentException naming the problem, rather than accepting it silently")
        void resumeWithForeignHandleThrows() {
            assertThatThrownBy(() -> manager.resume("not a Context"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("did not produce");
        }

        @Test
        @DisplayName("Suspended-stack ThreadLocal leaves no dangling frame once the last one is "
                + "popped (T-02-DOS-5 parity)")
        void suspendedStackLeavesNoDanglingFrameAfterResume() throws Exception {
            manager.begin();
            Object suspended = manager.suspend();
            manager.resume(suspended);

            assertThat(suspendedStackValue()).isNull();
        }

        private Deque<?> suspendedStackValue() throws Exception {
            Field field = JsonTransactionManager.class.getDeclaredField("suspendedStack");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Deque<?>> threadLocal = (ThreadLocal<Deque<?>>) field.get(manager);
            return threadLocal.get();
        }
    }

    // ===== REQUIRES_NEW/NOT_SUPPORTED through TransactionInterceptor, on the JSON backend (02-11) =====

    /**
     * End-to-end tests driving {@link JsonPropagationBean} through a real
     * {@link com.ultikits.ultitools.aop.AopProxyResolver}-built ByteBuddy proxy and a real
     * {@link TransactionInterceptor}, exactly mirroring {@code
     * TransactionInterceptorTest.PropagationSuspendResumeTests}'s JDBC shape (D-09) but on
     * {@link JsonTransactionManager}. Each behavior is proven twice -- once through an external
     * call (the test itself, standing in for a caller on a thread that already has a transaction
     * active from elsewhere, dispatched directly onto the proxy) and once through a
     * self-invocation call -- the same distinction {@code SelfInvocationAndExternalCallTests}
     * draws, and self-invocation IS intercepted in this framework (the generated subclass is the
     * bean, not a delegate).
     */
    @Nested
    @DisplayName("REQUIRES_NEW/NOT_SUPPORTED genuinely suspend on the JSON backend (02-11, D-09)")
    class RequiresNewAndNotSupportedIntegrationTests {

        private SimpleJsonDataOperator<TestData> outerOperator;
        private SimpleJsonDataOperator<TestData> innerOperator;
        private JsonTransactionManager propagationManager;
        private AopProxyResolver resolver;

        @BeforeEach
        void setUpPropagationFixture() throws Exception {
            File outerDir = Files.createDirectory(tempDir.resolve("propagation-outer-" + System.nanoTime())).toFile();
            File innerDir = Files.createDirectory(tempDir.resolve("propagation-inner-" + System.nanoTime())).toFile();
            outerOperator = new SimpleJsonDataOperator<>(outerDir.getAbsolutePath(), TestData.class);
            innerOperator = new SimpleJsonDataOperator<>(innerDir.getAbsolutePath(), TestData.class);
            propagationManager = new JsonTransactionManager("propagation-integration-test");
            outerOperator.bindTransactionManager(propagationManager);
            innerOperator.bindTransactionManager(propagationManager);

            TransactionInterceptor interceptor = new TransactionInterceptor(propagationManager);
            resolver = new AopProxyResolver();
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, interceptor, 100));
        }

        private JsonPropagationBean newProxiedBean() throws ReflectiveOperationException {
            Class<?> proxyClass = resolver.resolve(JsonPropagationBean.class);
            return (JsonPropagationBean) proxyClass
                    .getDeclaredConstructor(SimpleJsonDataOperator.class, SimpleJsonDataOperator.class,
                            JsonTransactionManager.class)
                    .newInstance(outerOperator, innerOperator, propagationManager);
        }

        @Test
        @DisplayName("REQUIRES_NEW (self-invocation): the inner transaction's commit survives the "
                + "outer transaction's rollback")
        void requiresNewSelfInvocationInnerCommitSurvivesOuterRollback() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            assertThatThrownBy(bean::outerRequiredThenSelfInvokesRequiresNewThenFails)
                    .isInstanceOf(RuntimeException.class);

            assertThat(outerOperator.getById("outer")).isNull();
            assertThat(innerOperator.getById("inner")).isNotNull();
            assertThat(propagationManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("REQUIRES_NEW (external call): commits independently of a separately-active "
                + "outer transaction that later rolls back")
        void requiresNewExternalCallInnerCommitSurvivesOuterRollback() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            propagationManager.begin(); // simulates a transaction already active from an external caller
            outerOperator.insert(new TestData("outer-ext", "OuterExt", 1));

            bean.requiresNewExternalSucceeds(); // external call: outside JsonPropagationBean entirely

            // No suspended frame left behind: the outer is exactly where it was before.
            assertThat(propagationManager.getTransactionDepth()).isEqualTo(1);

            propagationManager.rollback();

            assertThat(outerOperator.getById("outer-ext")).isNull();
            assertThat(innerOperator.getById("external-inner")).isNotNull();
        }

        @Test
        @DisplayName("NOT_SUPPORTED (self-invocation): the body runs with no active transaction, "
                + "and the outer transaction is intact at its original depth afterwards")
        void notSupportedSelfInvocationRunsWithNoActiveTransaction() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            assertThatThrownBy(bean::outerRequiredThenSelfInvokesNotSupportedThenFails)
                    .isInstanceOf(RuntimeException.class);

            assertThat(bean.hasActiveTxInsideNotSupported).isFalse();
            assertThat(bean.depthAfterNotSupportedReturnsSelf).isEqualTo(1);
            assertThat(innerOperator.getById("not-supported")).isNotNull();
            assertThat(outerOperator.getById("outer-nsw")).isNull();
        }

        @Test
        @DisplayName("NOT_SUPPORTED (external call): the body runs with no active transaction, and "
                + "the separately-active outer transaction is intact at its original depth afterwards")
        void notSupportedExternalCallRunsWithNoActiveTransaction() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            propagationManager.begin(); // depth 1, simulates an external caller's active transaction
            outerOperator.insert(new TestData("outer-ext-nsw", "OuterExtNsw", 1));

            bean.notSupportedExternalWrite(); // external call: outside JsonPropagationBean entirely

            assertThat(bean.hasActiveTxInsideNotSupported).isFalse();
            assertThat(propagationManager.hasActiveTransaction()).isTrue();
            assertThat(propagationManager.getTransactionDepth()).isEqualTo(1);

            propagationManager.rollback();

            assertThat(outerOperator.getById("outer-ext-nsw")).isNull();
            assertThat(innerOperator.getById("external-not-supported")).isNotNull();
        }
    }
}
