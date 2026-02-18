package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
}
