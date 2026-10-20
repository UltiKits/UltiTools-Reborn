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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.abstracts.data.DataEntityTest;
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.TransactionInterceptor;
import com.ultikits.ultitools.exceptions.UnexpectedRollbackException;
import com.ultikits.ultitools.manager.DataScope;
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
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
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

    /** {@link TestData} under a {@code @Table}, so a {@link JsonStore} can hand out its operator. */
    @Table("tx_rows_a")
    public static class RowA extends TestData {
        public RowA() {}

        public RowA(String id, String name, int value) {
            super(id, name, value);
        }
    }

    /** A second table of the same plugin. */
    @Table("tx_rows_b")
    public static class RowB extends TestData {
        public RowB() {}

        public RowB(String id, String name, int value) {
            super(id, name, value);
        }
    }

    /**
     * Fixture for {@link RequiresNewAndNotSupportedIntegrationTests} (02-11): proves REQUIRES_NEW
     * and NOT_SUPPORTED genuinely suspend on the JSON backend, mirroring
     * {@code TransactionInterceptorTest.PropagationBean}'s JDBC shape. Declared at the top level
     * (not inside the {@code @Nested} test class) for the same Java 8 / JUnit 5 reason documented
     * on that JDBC fixture -- a {@code static} member class cannot live inside a non-static
     * {@code @Nested} class on this project's Java 8 target.
     * <p>
     * The outer scope and the inner REQUIRES_NEW/NOT_SUPPORTED scope write the <em>same</em>
     * operator: each transaction stages its writes apart, so the outer rollback discards only the
     * outer scope's staged writes and the inner scope's committed write stays.
     */
    public static class JsonPropagationBean {
        private final SimpleJsonDataOperator<RowA> operator;
        private final JsonTransactionManager txManager;

        /** Captured inside {@link #innerNotSupportedWriteSelf()}/{@link #notSupportedExternalWrite()}. */
        volatile boolean hasActiveTxInsideNotSupported;

        /** Captured immediately after the self-invoked NOT_SUPPORTED call returns, still inside the
         *  outer transaction -- proves the outer is intact at its original depth afterwards. */
        volatile int depthAfterNotSupportedReturnsSelf;

        public JsonPropagationBean(SimpleJsonDataOperator<RowA> operator, JsonTransactionManager txManager) {
            this.operator = operator;
            this.txManager = txManager;
        }

        /**
         * Outer REQUIRED transaction that self-invokes a REQUIRES_NEW method whose write commits,
         * then itself throws so the outer rolls back. The inner's committed write must survive.
         */
        @Transactional
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        public void outerRequiredThenSelfInvokesRequiresNewThenFails() {
            operator.insert(new RowA("outer", "Outer", 1));
            innerRequiresNewSucceedsSelf();
            throw new RuntimeException(
                    "boom - outer rolls back after inner REQUIRES_NEW commit, self-invocation");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void innerRequiresNewSucceedsSelf() {
            operator.insert(new RowA("inner", "Inner", 99));
        }

        /** Externally-called REQUIRES_NEW method that writes and commits independently. */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNewExternalSucceeds() {
            operator.insert(new RowA("external-inner", "ExternalInner", 100));
        }

        /**
         * Outer REQUIRED transaction that self-invokes a NOT_SUPPORTED write, then itself throws
         * so the outer rolls back. The NOT_SUPPORTED write must survive the outer's rollback.
         */
        @Transactional
        public void outerRequiredThenSelfInvokesNotSupportedThenFails() {
            operator.insert(new RowA("outer-nsw", "OuterNsw", 4));
            innerNotSupportedWriteSelf();
            depthAfterNotSupportedReturnsSelf = txManager.getTransactionDepth();
            throw new RuntimeException(
                    "boom - outer rolls back after NOT_SUPPORTED write, self-invocation");
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void innerNotSupportedWriteSelf() {
            hasActiveTxInsideNotSupported = txManager.hasActiveTransaction();
            operator.insert(new RowA("not-supported", "NotSupported", 102));
        }

        /** Externally-called NOT_SUPPORTED write. */
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupportedExternalWrite() {
            hasActiveTxInsideNotSupported = txManager.hasActiveTransaction();
            operator.insert(new RowA("external-not-supported", "ExternalNotSupported", 103));
        }
    }

    /**
     * One plugin's JSON storage, as a module gets it: a {@link JsonStore} over the temporary
     * folder and one {@link DataScope} owning {@link RowA} and {@link RowB}. The operators and the
     * manager come from that store for that scope, so they share one transaction manager.
     */
    private final class PluginStore {
        private final JsonStore store;
        private final DataScope scope;

        PluginStore(String name) throws Exception {
            File dataFolder = Files.createDirectories(tempDir.resolve(name + "-data")).toFile();
            scope = JsonTransactionEngineTest.newDataScope(name, dataFolder, RowA.class, RowB.class);
            store = new JsonStore(tempDir.resolve(name + "-store").toString());
        }

        @SuppressWarnings("unchecked")
        <E extends BaseDataEntity<String>> SimpleJsonDataOperator<E> operator(Class<E> entity) {
            return (SimpleJsonDataOperator<E>) store.getOperator(scope, entity);
        }

        JsonTransactionManager manager() {
            return store.transactionManagerFor(scope);
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
    @DisplayName("Transaction Rollback (staged writes discarded)")
    class TransactionRollbackTests {

        @Test
        @DisplayName("Should rollback inserts on RuntimeException")
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
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
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
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
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
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
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
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
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
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

    // ===== Staged values: the committed value is never changed in place =====

    @Nested
    @DisplayName("Staged update: the committed value is never changed in place")
    class StagedValueTests {

        @Test
        @DisplayName("A staged update is seen inside the transaction only; rollback leaves the committed value as it was")
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        void stagedUpdateNeverReachesTheCommittedValue() {
            operator.insert(new TestData("1", "Original", 100));
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try {
                assertThatThrownBy(() -> {
                    operator.transaction(() -> {
                        operator.update(new TestData("1", "Mutated", 999));

                        // This transaction reads its own staged update...
                        assertThat(operator.getById("1").getName()).isEqualTo("Mutated");
                        // ...while another thread still reads the committed value.
                        assertThat(reader.submit(() -> operator.getById("1").getName()).get(10, TimeUnit.SECONDS))
                                .isEqualTo("Original");

                        throw new RuntimeException("Rollback!");
                    });
                }).isInstanceOf(RuntimeException.class);
            } finally {
                reader.shutdown();
            }

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

    // ===== Batch atomicity on the staging engine =====

    /**
     * An entity whose onCreate/onUpdate throws a chosen failure for one chosen id, so a batch can
     * fail part-way.
     */
    public static class FailingHookData extends TestData {
        static volatile String failOnCreateId;
        static volatile String failOnUpdateId;
        static volatile Throwable failure;

        public FailingHookData() {}

        public FailingHookData(String id, String name, int value) {
            super(id, name, value);
        }

        static void reset() {
            failOnCreateId = null;
            failOnUpdateId = null;
            failure = null;
        }

        private void failIf(String failingId) {
            if (failingId != null && failingId.equals(getId())) {
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw (RuntimeException) failure;
            }
        }

        @Override
        public void onCreate() {
            failIf(failOnCreateId);
        }

        @Override
        public void onUpdate() {
            failIf(failOnUpdateId);
        }
    }

    @Nested
    @DisplayName("insertAll()/updateAll() are atomic engine transactions")
    class BatchAtomicityTests {

        private SimpleJsonDataOperator<FailingHookData> batchOperator;

        @BeforeEach
        void setUpBatchOperator() throws Exception {
            File dir = Files.createDirectory(tempDir.resolve("batch-" + System.nanoTime())).toFile();
            batchOperator = new SimpleJsonDataOperator<>(dir.getAbsolutePath(), FailingHookData.class);
            FailingHookData.reset();
        }

        @AfterEach
        void resetFailure() {
            FailingHookData.reset();
        }

        private List<FailingHookData> three(String prefix) {
            return Arrays.asList(new FailingHookData(prefix + "1", "one", 1),
                    new FailingHookData(prefix + "2", "two", 2),
                    new FailingHookData(prefix + "3", "three", 3));
        }

        @Test
        @DisplayName("insertAll whose third onCreate throws a RuntimeException stores none and rethrows that instance")
        void insertAllRuntimeExceptionStoresNone() {
            IllegalStateException boom = new IllegalStateException("third insert fails");
            FailingHookData.failOnCreateId = "i3";
            FailingHookData.failure = boom;

            assertThatThrownBy(() -> batchOperator.insertAll(three("i"))).isSameAs(boom);

            assertThat(batchOperator.getAll()).isEmpty();
        }

        @Test
        @DisplayName("insertAll whose third onCreate throws an Error stores none and rethrows that instance")
        void insertAllErrorStoresNone() {
            AssertionError boom = new AssertionError("third insert fails with an Error");
            FailingHookData.failOnCreateId = "e3";
            FailingHookData.failure = boom;

            assertThatThrownBy(() -> batchOperator.insertAll(three("e"))).isSameAs(boom);

            assertThat(batchOperator.getAll()).isEmpty();
        }

        @Test
        @DisplayName("updateAll whose third onUpdate throws a RuntimeException changes none and rethrows that instance")
        void updateAllRuntimeExceptionChangesNone() {
            batchOperator.insertAll(three("u"));
            List<FailingHookData> updates = Arrays.asList(new FailingHookData("u1", "changed", 10),
                    new FailingHookData("u2", "changed", 20), new FailingHookData("u3", "changed", 30));
            IllegalStateException boom = new IllegalStateException("third update fails");
            FailingHookData.failOnUpdateId = "u3";
            FailingHookData.failure = boom;

            assertThatThrownBy(() -> batchOperator.updateAll(updates)).isSameAs(boom);

            assertThat(batchOperator.getById("u1").getName()).isEqualTo("one");
            assertThat(batchOperator.getById("u2").getName()).isEqualTo("two");
            assertThat(batchOperator.getById("u3").getName()).isEqualTo("three");
        }

        @Test
        @DisplayName("updateAll whose third onUpdate throws an Error changes none and rethrows that instance")
        void updateAllErrorChangesNone() {
            batchOperator.insertAll(three("v"));
            List<FailingHookData> updates = Arrays.asList(new FailingHookData("v1", "changed", 10),
                    new FailingHookData("v2", "changed", 20), new FailingHookData("v3", "changed", 30));
            AssertionError boom = new AssertionError("third update fails with an Error");
            FailingHookData.failOnUpdateId = "v3";
            FailingHookData.failure = boom;

            assertThatThrownBy(() -> batchOperator.updateAll(updates)).isSameAs(boom);

            assertThat(batchOperator.getById("v1").getName()).isEqualTo("one");
            assertThat(batchOperator.getById("v3").getName()).isEqualTo("three");
        }

        @Test
        @DisplayName("A batch inside an active transaction joins it, and the outer rollback undoes the batch")
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        void batchJoinsTheActiveTransaction() throws Exception {
            batchOperator.insert(new FailingHookData("kept", "kept", 0));

            assertThatThrownBy(() -> batchOperator.transaction(() -> {
                batchOperator.insertAll(three("j"));
                batchOperator.updateAll(Arrays.asList(new FailingHookData("kept", "changed", 1)));
                assertThat(batchOperator.transactionManager().getTransactionDepth())
                        .as("the batches joined the outer transaction").isEqualTo(1);
                assertThat(batchOperator.getAll()).as("the batches' writes, seen inside").hasSize(4);
                throw new RuntimeException("outer rollback");
            })).hasMessage("outer rollback");

            assertThat(batchOperator.getAll()).hasSize(1);
            assertThat(batchOperator.getById("kept").getName()).isEqualTo("kept");
        }
    }

    // ===== Hook timing on the staging engine =====

    @Nested
    @DisplayName("Entity hooks fire at the call inside a transaction, never again at commit")
    class HookTimingTests {

        private SimpleJsonDataOperator<DataEntityTest.CountingAuditableEntity> hookOperator;

        @BeforeEach
        void setUpHookOperator() throws Exception {
            File dir = Files.createDirectory(tempDir.resolve("hooks-" + System.nanoTime())).toFile();
            hookOperator = new SimpleJsonDataOperator<>(dir.getAbsolutePath(),
                    DataEntityTest.CountingAuditableEntity.class);
            DataEntityTest.CountingAuditableEntity.resetCounters();
        }

        @AfterEach
        void resetCounters() {
            DataEntityTest.CountingAuditableEntity.resetCounters();
        }

        private DataEntityTest.CountingAuditableEntity entity(String label) {
            DataEntityTest.CountingAuditableEntity entity = new DataEntityTest.CountingAuditableEntity();
            entity.setLabel(label);
            return entity;
        }

        private void assertCounts(int create, int update, int delete) {
            assertThat(DataEntityTest.CountingAuditableEntity.onCreateCount()).as("onCreate").isEqualTo(create);
            assertThat(DataEntityTest.CountingAuditableEntity.onUpdateCount()).as("onUpdate").isEqualTo(update);
            assertThat(DataEntityTest.CountingAuditableEntity.onDeleteCount()).as("onDelete").isEqualTo(delete);
        }

        @Test
        @DisplayName("onCreate, onUpdate and onDelete fire once each at the call; commit fires none of them again")
        void hooksFireAtTheCallAndNotAtCommit() throws Exception {
            DataEntityTest.CountingAuditableEntity kept = entity("kept");
            DataEntityTest.CountingAuditableEntity removed = entity("removed");

            hookOperator.transaction(() -> {
                hookOperator.insert(kept);
                hookOperator.insert(removed);
                assertCounts(2, 0, 0);
                kept.setLabel("kept-updated");
                hookOperator.update(kept);
                assertCounts(2, 1, 0);
                hookOperator.delById(removed.getId());
                assertCounts(2, 1, 1);
                return null;
            });

            assertCounts(2, 1, 1);
            assertThat(hookOperator.getById(kept.getId()).getLabel()).isEqualTo("kept-updated");
            assertThat(hookOperator.getById(removed.getId())).isNull();
        }

        @Test
        @DisplayName("After a rollback the counters show the call-time firing only")
        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        void rollbackShowsCallTimeFiringOnly() {
            DataEntityTest.CountingAuditableEntity committed = entity("committed");
            hookOperator.insert(committed);
            DataEntityTest.CountingAuditableEntity.resetCounters();

            assertThatThrownBy(() -> hookOperator.transaction(() -> {
                hookOperator.insert(entity("staged"));
                committed.setLabel("staged-update");
                hookOperator.update(committed);
                hookOperator.delById(committed.getId());
                throw new RuntimeException("roll back");
            })).hasMessage("roll back");

            assertCounts(1, 1, 1);
            assertThat(hookOperator.getById(committed.getId()).getLabel()).isEqualTo("committed");
        }

        @Test
        @DisplayName("onLoad fires on staged entities a read inside the transaction returns")
        void onLoadFiresOnStagedEntities() throws Exception {
            hookOperator.transaction(() -> {
                DataEntityTest.CountingAuditableEntity first = entity("first");
                hookOperator.insert(first);
                hookOperator.insert(entity("second"));
                assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount()).isZero();

                assertThat(hookOperator.getById(first.getId())).isNotNull();
                assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount()).isEqualTo(1);

                assertThat(hookOperator.getAll()).hasSize(2);
                assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount()).isEqualTo(3);
                return null;
            });
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

            // Thread 1: repeatedly read "stable" entity. A rollback only discards staged writes and
            // never touches committed entries, so no read may ever miss it.
            Future<Boolean> readerResult = executor.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    TestData data = operator.getById("stable");
                    if (data == null || !"Stable".equals(data.getName())) {
                        return false;
                    }
                    Thread.sleep(1);
                }
                return true;
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
    @DisplayName("JsonTransactionManager - one transaction across a plugin's operators")
    class PerPluginTransactionScopeTests {

        private SimpleJsonDataOperator<RowA> operatorA;
        private SimpleJsonDataOperator<RowB> operatorB;
        private JsonTransactionManager manager;

        @BeforeEach
        void setUpTwoOperators() throws Exception {
            PluginStore plugin = new PluginStore("per-plugin-scope");
            operatorA = plugin.operator(RowA.class);
            operatorB = plugin.operator(RowB.class);
            manager = plugin.manager();
        }

        @Test
        @DisplayName("Rollback discards the staged writes of every operator of the plugin")
        void rollbackDiscardsStagedWritesOfBothOperators() {
            operatorA.insert(new RowA("pre-a", "PreA", 1));
            operatorB.insert(new RowB("pre-b", "PreB", 2));

            manager.begin();
            operatorA.insert(new RowA("new-a", "NewA", 10));
            operatorB.insert(new RowB("new-b", "NewB", 20));
            assertThat(manager.currentTransaction().hasStagedWrites(operatorA)).isTrue();
            assertThat(manager.currentTransaction().hasStagedWrites(operatorB)).isTrue();
            manager.rollback();

            assertThat(operatorA.getAll()).hasSize(1);
            assertThat(operatorA.getById("pre-a")).isNotNull();
            assertThat(operatorA.getById("new-a")).isNull();

            assertThat(operatorB.getAll()).hasSize(1);
            assertThat(operatorB.getById("pre-b")).isNotNull();
            assertThat(operatorB.getById("new-b")).isNull();
        }

        @Test
        @DisplayName("A concurrent plain write to an operator the transaction has not written survives rollback")
        void concurrentWriteToAnUnwrittenOperatorSurvivesRollback() throws Exception {
            operatorA.insert(new RowA("pre-a", "PreA", 1));

            manager.begin();
            operatorA.insert(new RowA("new-a", "NewA", 10));

            // A write from a different thread never joins this thread's transaction (the context
            // is ThreadLocal, matching DataSourceTransactionManager), so it is applied at once.
            // It writes operatorB, which this transaction has not written.
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> operatorB.insert(new RowB("external", "External", 99))).get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdown();
            }
            assertThat(manager.currentTransaction().hasStagedWrites(operatorB)).isFalse();

            manager.rollback();

            // operatorA's staged insert is discarded; its committed entry is untouched.
            assertThat(operatorA.getById("new-a")).isNull();
            assertThat(operatorA.getById("pre-a")).isNotNull();

            // The other thread's committed write survives the rollback.
            assertThat(operatorB.getById("external")).isNotNull();
        }

        @Test
        @DisplayName("commit() applies the staged writes and ends the transaction")
        void commitAppliesStagedWrites() {
            manager.begin();
            operatorA.insert(new RowA("a", "A", 1));
            operatorB.insert(new RowB("b", "B", 2));
            manager.commit();

            assertThat(operatorA.getById("a")).isNotNull();
            assertThat(operatorB.getById("b")).isNotNull();
            assertThat(manager.hasActiveTransaction()).isFalse();
            assertThat(manager.currentTransaction()).isNull();
        }

        @Test
        @DisplayName("rollback() with no active transaction logs a warning and returns")
        void rollbackWithNoActiveTransactionIsANoOp() {
            assertThatCode(() -> manager.rollback()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Depth 2: inner rollback() then outer commit() throws UnexpectedRollbackException "
                + "and discards every staged write")
        void nestedRollbackThenOuterCommitThrows() {
            operatorA.insert(new RowA("pre-a", "PreA", 1));

            manager.begin(); // depth 1
            manager.begin(); // depth 2
            operatorA.insert(new RowA("new-a", "NewA", 10));
            manager.rollback(); // marks rollback-only, depth -> 1

            assertThat(manager.hasActiveTransaction()).isTrue();
            assertThat(manager.getTransactionDepth()).isEqualTo(1);

            assertThatThrownBy(manager::commit).isInstanceOf(UnexpectedRollbackException.class);

            assertThat(operatorA.getById("new-a")).isNull();
            assertThat(operatorA.getById("pre-a")).isNotNull();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("setTimeout records a deadline inside a transaction and, as on JDBC, does nothing outside one")
        void setTimeoutIsNotASilentNoOp() {
            assertThatCode(() -> manager.setTimeout(30)).doesNotThrowAnyException();
            assertThat(manager.getTimeoutDeadlineNanos()).as("no transaction, no deadline").isNull();

            manager.begin();
            try {
                manager.setTimeout(30);
                assertThat(manager.getTimeoutDeadlineNanos()).as("deadline of the active transaction").isNotNull();
            } finally {
                manager.rollback();
            }
        }

        @Test
        @DisplayName("JsonTransactionManager refuses getConnection; it accepts every isolation level the JSON "
                + "engine serves, refuses an unknown one, and accepts read-only")
        void doesNotImplementJdbcMethods() {
            assertThatThrownBy(manager::getConnection).isInstanceOf(UnsupportedOperationException.class);
            for (int level : new int[] {-1, java.sql.Connection.TRANSACTION_READ_UNCOMMITTED,
                    java.sql.Connection.TRANSACTION_READ_COMMITTED, java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                    java.sql.Connection.TRANSACTION_SERIALIZABLE}) {
                assertThatCode(() -> manager.setIsolationLevel(level)).as("isolation level " + level)
                        .doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> manager.setIsolationLevel(java.sql.Connection.TRANSACTION_NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.setIsolationLevel(3)).isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> manager.setReadOnly(true)).doesNotThrowAnyException();
        }
    }

    // ===== suspend()/resume() -- REQUIRES_NEW/NOT_SUPPORTED's suspension mechanism (02-11) =====

    /**
     * Unit-level tests against {@link JsonTransactionManager#suspend()}/{@link
     * JsonTransactionManager#resume(Object)} directly, mirroring
     * {@code DataSourceTransactionManagerTest.SuspendResumeTests} (D-09) one-for-one on the JSON
     * side, plus the staged writes a suspended context carries.
     */
    @Nested
    @DisplayName("suspend()/resume() -- the mechanism REQUIRES_NEW/NOT_SUPPORTED need (02-11, D-09)")
    class SuspendResumeTests {

        private JsonTransactionManager manager;
        private SimpleJsonDataOperator<RowA> rows;

        @BeforeEach
        void setUpManager() throws Exception {
            PluginStore plugin = new PluginStore("suspend-resume");
            manager = plugin.manager();
            rows = plugin.operator(RowA.class);
        }

        @Test
        @DisplayName("A suspended context carries its staged writes: hidden from the independent "
                + "transaction, visible again after resume, applied by the resumed commit")
        void suspendedContextCarriesItsStagedWrites() {
            manager.begin();
            rows.insert(new RowA("outer", "Outer", 1));

            Object suspended = manager.suspend();
            assertThat(rows.getById("outer")).as("outside the suspended transaction").isNull();

            manager.begin(); // independent inner transaction
            assertThat(rows.getById("outer")).as("inside the independent transaction").isNull();
            rows.insert(new RowA("inner", "Inner", 2));
            manager.commit();
            assertThat(rows.getById("inner")).as("the inner commit applied its own write").isNotNull();
            assertThat(rows.getById("outer")).as("the inner commit did not apply the outer write").isNull();

            manager.resume(suspended);
            assertThat(rows.getById("outer")).as("the resumed transaction sees its staged write").isNotNull();
            manager.commit();

            assertThat(rows.getById("outer")).isNotNull();
            assertThat(rows.getById("inner")).isNotNull();
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

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
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

        private SimpleJsonDataOperator<RowA> rows;
        private JsonTransactionManager propagationManager;
        private AopProxyResolver resolver;

        @BeforeEach
        void setUpPropagationFixture() throws Exception {
            PluginStore plugin = new PluginStore("propagation-" + System.nanoTime());
            rows = plugin.operator(RowA.class);
            propagationManager = plugin.manager();

            TransactionInterceptor interceptor = new TransactionInterceptor(propagationManager);
            resolver = new AopProxyResolver();
            resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, interceptor, 100));
        }

        private JsonPropagationBean newProxiedBean() throws ReflectiveOperationException {
            Class<?> proxyClass = resolver.resolve(JsonPropagationBean.class);
            return (JsonPropagationBean) proxyClass
                    .getDeclaredConstructor(SimpleJsonDataOperator.class, JsonTransactionManager.class)
                    .newInstance(rows, propagationManager);
        }

        @Test
        @DisplayName("REQUIRES_NEW (self-invocation): the inner transaction's commit survives the "
                + "outer transaction's rollback")
        void requiresNewSelfInvocationInnerCommitSurvivesOuterRollback() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            assertThatThrownBy(bean::outerRequiredThenSelfInvokesRequiresNewThenFails)
                    .isInstanceOf(RuntimeException.class);

            assertThat(rows.getById("outer")).isNull();
            assertThat(rows.getById("inner")).isNotNull();
            assertThat(propagationManager.hasActiveTransaction()).isFalse();
        }

        @Test
        @DisplayName("REQUIRES_NEW (external call): commits independently of a separately-active "
                + "outer transaction that later rolls back")
        void requiresNewExternalCallInnerCommitSurvivesOuterRollback() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            propagationManager.begin(); // simulates a transaction already active from an external caller
            rows.insert(new RowA("outer-ext", "OuterExt", 1));

            bean.requiresNewExternalSucceeds(); // external call: outside JsonPropagationBean entirely

            // No suspended frame left behind: the outer is exactly where it was before.
            assertThat(propagationManager.getTransactionDepth()).isEqualTo(1);

            propagationManager.rollback();

            assertThat(rows.getById("outer-ext")).isNull();
            assertThat(rows.getById("external-inner")).isNotNull();
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
            assertThat(rows.getById("not-supported")).isNotNull();
            assertThat(rows.getById("outer-nsw")).isNull();
        }

        @Test
        @DisplayName("NOT_SUPPORTED (external call): the body runs with no active transaction, and "
                + "the separately-active outer transaction is intact at its original depth afterwards")
        void notSupportedExternalCallRunsWithNoActiveTransaction() throws Exception {
            JsonPropagationBean bean = newProxiedBean();

            propagationManager.begin(); // depth 1, simulates an external caller's active transaction
            rows.insert(new RowA("outer-ext-nsw", "OuterExtNsw", 1));

            bean.notSupportedExternalWrite(); // external call: outside JsonPropagationBean entirely

            assertThat(bean.hasActiveTxInsideNotSupported).isFalse();
            assertThat(propagationManager.hasActiveTransaction()).isTrue();
            assertThat(propagationManager.getTransactionDepth()).isEqualTo(1);

            propagationManager.rollback();

            assertThat(rows.getById("outer-ext-nsw")).isNull();
            assertThat(rows.getById("external-not-supported")).isNotNull();
        }
    }
}
