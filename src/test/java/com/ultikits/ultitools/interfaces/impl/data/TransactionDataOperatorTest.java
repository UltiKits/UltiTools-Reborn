package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.ultikits.ultitools.manager.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.EqualsAndHashCode;

@DisplayName("Transaction & Batch Operations - SQL Integration Tests")
class TransactionDataOperatorTest {

    private static DataSource dataSource;
    private SQLiteDataOperator<TestEntity> operator;

    @EqualsAndHashCode(callSuper = true)
    @Table("tx_test")
    public static class TestEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        @Column(value = "score", type = "INT")
        private int score;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }

        static TestEntity of(String id, String name, int score) {
            TestEntity e = new TestEntity();
            e.setId(id);
            e.setName(name);
            e.setScore(score);
            return e;
        }
    }

    @BeforeAll
    static void initDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:txtest;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // 刻意不调用 setPassword：内存库首次连接时就以「无口令」创建，
        // 传一个空字符串反而会被静态分析当成硬编码口令（Codacy/Opengrep 的
        // Semgrep_java_password_rule-HardcodePassword）。这里没有凭据可言。
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS tx_test");
        }
        operator = new SQLiteDataOperator<>(dataSource, TestEntity.class);
    }

    private int countRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM tx_test")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ===== Transaction Tests (no TransactionManager) =====

    @Nested
    @DisplayName("Transaction without TransactionManager")
    class NoTransactionManagerTests {

        @Test
        @DisplayName("transaction(Runnable) should execute action directly")
        void transactionRunsWithoutManager() {
            operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
            });

            assertThat(operator.getAll()).hasSize(1);
        }

        @Test
        @DisplayName("transaction(Callable) should return result without manager")
        void transactionCallableWithoutManager() throws Exception {
            String result = operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
                return "done";
            });

            assertThat(result).isEqualTo("done");
            assertThat(operator.getAll()).hasSize(1);
        }
    }

    // ===== Transaction Tests (with TransactionManager) =====

    @Nested
    @DisplayName("Transaction with TransactionManager")
    class WithTransactionManagerTests {

        @BeforeEach
        void setUpTxManager() {
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
            operator.setTransactionManager(txManager);
        }

        @Test
        @DisplayName("transaction(Runnable) should commit on success")
        void transactionCommitsOnSuccess() throws Exception {
            operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
                operator.insert(TestEntity.of("2", "Bob", 200));
            });

            assertThat(countRows()).isEqualTo(2);
            assertThat(operator.getAll()).hasSize(2);
        }

        @Test
        @DisplayName("transaction(Callable) should commit and return result")
        void transactionCallableCommits() throws Exception {
            int result = operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
                return operator.getAll().size();
            });

            assertThat(result).isEqualTo(1);
            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("transaction should rollback on RuntimeException")
        void transactionRollsBackOnRuntimeException() throws Exception {
            // Insert one row outside transaction
            operator.insert(TestEntity.of("0", "Pre-existing", 0));
            assertThat(countRows()).isEqualTo(1);

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(TestEntity.of("1", "Alice", 100));
                    throw new IllegalStateException("Simulated failure");
                });
            }).isInstanceOf(RuntimeException.class)
              .hasMessage("Simulated failure");

            // Only pre-existing row should remain
            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("transaction(Callable) should rollback on checked exception")
        void transactionRollsBackOnCheckedException() throws Exception {
            operator.insert(TestEntity.of("0", "Pre-existing", 0));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(TestEntity.of("1", "Alice", 100));
                    throw new java.io.IOException("Checked failure");
                });
            }).isInstanceOf(java.io.IOException.class)
              .hasMessage("Checked failure");

            assertThat(countRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple independent transactions should each commit")
        void multipleTransactionsCommitIndependently() throws Exception {
            operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
            });

            operator.transaction(() -> {
                operator.insert(TestEntity.of("2", "Bob", 200));
            });

            assertThat(countRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("Operations inside transaction should be visible to each other")
        void operationsVisibleWithinTransaction() throws Exception { // NOPMD - asserts inside transaction lambda
            operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
                // Should be able to read what we just inserted
                TestEntity found = operator.getById("1");
                assertThat(found).isNotNull();
                assertThat(found.getName()).isEqualTo("Alice");
            });
        }
    }

    // ===== Batch Insert Tests =====

    @Nested
    @DisplayName("insertAll() - JDBC Batch Insert")
    class InsertAllTests {

        @BeforeEach
        void setUpTxManager() {
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
            operator.setTransactionManager(txManager);
        }

        @Test
        @DisplayName("insertAll should insert all entities")
        void insertAllInsertsAll() throws Exception {
            List<TestEntity> entities = Arrays.asList(
                    TestEntity.of("1", "Alice", 100),
                    TestEntity.of("2", "Bob", 200),
                    TestEntity.of("3", "Charlie", 300)
            );

            operator.insertAll(entities);

            assertThat(countRows()).isEqualTo(3);
            assertThat(operator.getById("1").getName()).isEqualTo("Alice");
            assertThat(operator.getById("2").getName()).isEqualTo("Bob");
            assertThat(operator.getById("3").getName()).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("insertAll with empty list should be no-op")
        void insertAllEmptyList() throws Exception {
            operator.insertAll(new ArrayList<>());
            assertThat(countRows()).isEqualTo(0);
        }

        @Test
        @DisplayName("insertAll with null should be no-op")
        void insertAllNull() throws Exception {
            operator.insertAll(null);
            assertThat(countRows()).isEqualTo(0);
        }

        @Test
        @DisplayName("insertAll should be atomic - all or nothing on duplicate key")
        void insertAllAtomicOnFailure() throws Exception {
            // Insert first entity normally
            operator.insert(TestEntity.of("1", "Existing", 50));
            assertThat(countRows()).isEqualTo(1);

            // Try to batch insert including a duplicate ID
            List<TestEntity> entities = Arrays.asList(
                    TestEntity.of("2", "New", 100),
                    TestEntity.of("1", "Duplicate", 200) // duplicate ID
            );

            assertThatThrownBy(() -> operator.insertAll(entities))
                    .isInstanceOf(DataAccessException.class);

            // Original row should remain, new row should be rolled back
            assertThat(countRows()).isEqualTo(1);
            assertThat(operator.getById("1").getName()).isEqualTo("Existing");
        }

        @Test
        @DisplayName("insertAll without TransactionManager should still work")
        void insertAllWithoutTxManager() throws Exception {
            // Create operator without transaction manager
            SQLiteDataOperator<TestEntity> noTxOperator;
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS tx_test");
            }
            noTxOperator = new SQLiteDataOperator<>(dataSource, TestEntity.class);

            List<TestEntity> entities = Arrays.asList(
                    TestEntity.of("1", "Alice", 100),
                    TestEntity.of("2", "Bob", 200)
            );

            noTxOperator.insertAll(entities);
            assertThat(noTxOperator.getAll()).hasSize(2);
        }

        @Test
        @DisplayName("insertAll with large batch should work")
        void insertAllLargeBatch() throws Exception {
            List<TestEntity> entities = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                entities.add(TestEntity.of(String.valueOf(i), "Entity" + i, i * 10));
            }

            operator.insertAll(entities);

            assertThat(countRows()).isEqualTo(100);
            assertThat(operator.getById("50").getName()).isEqualTo("Entity50");
            assertThat(operator.getById("50").getScore()).isEqualTo(500);
        }
    }

    // ===== Batch Update Tests =====

    @Nested
    @DisplayName("updateAll() - JDBC Batch Update")
    class UpdateAllTests {

        @BeforeEach
        void setUpTxManager() throws Exception {
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
            operator.setTransactionManager(txManager);

            // Insert test data
            operator.insert(TestEntity.of("1", "Alice", 100));
            operator.insert(TestEntity.of("2", "Bob", 200));
            operator.insert(TestEntity.of("3", "Charlie", 300));
        }

        @Test
        @DisplayName("updateAll should update all entities")
        void updateAllUpdatesAll() throws Exception {
            List<TestEntity> updates = Arrays.asList(
                    TestEntity.of("1", "Alice-Updated", 150),
                    TestEntity.of("2", "Bob-Updated", 250),
                    TestEntity.of("3", "Charlie-Updated", 350)
            );

            operator.updateAll(updates);

            assertThat(operator.getById("1").getName()).isEqualTo("Alice-Updated");
            assertThat(operator.getById("1").getScore()).isEqualTo(150);
            assertThat(operator.getById("2").getName()).isEqualTo("Bob-Updated");
            assertThat(operator.getById("3").getName()).isEqualTo("Charlie-Updated");
        }

        @Test
        @DisplayName("updateAll with empty list should be no-op")
        void updateAllEmptyList() throws Exception {
            operator.updateAll(new ArrayList<>());
            assertThat(operator.getAll()).hasSize(3);
        }

        @Test
        @DisplayName("updateAll with null should be no-op")
        void updateAllNull() throws Exception {
            operator.updateAll(null);
            assertThat(operator.getAll()).hasSize(3);
        }

        @Test
        @DisplayName("Partial updates should be committed together")
        void partialUpdatesAreAtomic() throws Exception {
            List<TestEntity> updates = Arrays.asList(
                    TestEntity.of("1", "Updated1", 999),
                    TestEntity.of("3", "Updated3", 888)
            );

            operator.updateAll(updates);

            assertThat(operator.getById("1").getScore()).isEqualTo(999);
            assertThat(operator.getById("2").getScore()).isEqualTo(200); // unchanged
            assertThat(operator.getById("3").getScore()).isEqualTo(888);
        }
    }

    // ===== Mixed Transaction Tests =====

    @Nested
    @DisplayName("Mixed Operations in Transaction")
    class MixedOperationsTests {

        @BeforeEach
        void setUpTxManager() {
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
            operator.setTransactionManager(txManager);
        }

        @Test
        @DisplayName("Insert + Update + Delete in single transaction should commit together")
        void mixedOperationsCommitTogether() throws Exception {
            operator.transaction(() -> {
                operator.insert(TestEntity.of("1", "Alice", 100));
                operator.insert(TestEntity.of("2", "Bob", 200));
                operator.insert(TestEntity.of("3", "Charlie", 300));
                try {
                    operator.update(TestEntity.of("1", "Alice-Updated", 150));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                operator.delById("3");
            });

            assertThat(countRows()).isEqualTo(2);
            assertThat(operator.getById("1").getName()).isEqualTo("Alice-Updated");
            assertThat(operator.getById("2").getName()).isEqualTo("Bob");
            assertThat(operator.getById("3")).isNull();
        }

        @Test
        @DisplayName("Mixed operations should rollback together on failure")
        void mixedOperationsRollbackTogether() throws Exception {
            operator.insert(TestEntity.of("1", "Original", 100));

            assertThatThrownBy(() -> {
                operator.transaction(() -> {
                    operator.insert(TestEntity.of("2", "New", 200));
                    try {
                        operator.update(TestEntity.of("1", "Modified", 999));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                    throw new IllegalStateException("Abort!");
                });
            }).isInstanceOf(IllegalStateException.class);

            // Everything should be rolled back
            assertThat(countRows()).isEqualTo(1);
            assertThat(operator.getById("1").getName()).isEqualTo("Original");
            assertThat(operator.getById("1").getScore()).isEqualTo(100);
        }
    }
}
