package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.EqualsAndHashCode;

/**
 * SQLiteDataOperator 完整测试类
 * 使用 H2 内存数据库进行集成测试
 */
@DisplayName("SQLiteDataOperator 测试")
class SQLiteDataOperatorTest {

    private static DataSource dataSource;
    private SQLiteDataOperator<TestEntity> operator;

    /**
     * 测试用实体类 - 基础类型字段
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("test_entity")
    public static class TestEntity extends AbstractDataEntity {
        @Column("name")
        private String name;

        @Column(value = "age", type = "INT")
        private int age;

        @Column(value = "score", type = "DOUBLE")
        private double score;

        @Column(value = "active", type = "BOOLEAN")
        private boolean active;

        public TestEntity() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @BeforeAll
    static void initDataSource() {
        HikariConfig config = new HikariConfig();
        // Use H2 in MySQL compatibility mode for backtick support
        config.setJdbcUrl("jdbc:h2:mem:sqlitetest;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        config.setPassword("");
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_entity");
        } catch (Exception ignored) {
        }
        
        operator = new SQLiteDataOperator<>(dataSource, TestEntity.class);
    }

    private void executeSql(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该成功创建表并初始化 SQLiteDataOperator")
        void shouldCreateTableAndInitializeSuccessfully() {
            assertThat(operator).isNotNull();
        }
    }

    @Nested
    @DisplayName("insert 和 exist 方法测试")
    class InsertAndExistTests {

        @Test
        @DisplayName("应该成功插入实体并检查存在")
        void shouldInsertAndCheckExist() {
            TestEntity entity = new TestEntity();
            entity.setId("test-1");
            entity.setName("TestName");
            entity.setAge(25);
            entity.setScore(90.5);
            entity.setActive(true);

            operator.insert(entity);

            assertThat(operator.exist(entity)).isTrue();
            assertThat(operator.exist(WhereCondition.builder().column("id").value("test-1").build())).isTrue();
        }

        @Test
        @DisplayName("exist(WhereCondition...) - 应该返回 false 当记录不存在")
        void existShouldReturnFalseWhenRecordNotExists() {
            boolean result = operator.exist(WhereCondition.builder().column("name").value("nonexistent").build());
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("getAll 和 getById 方法测试")
    class GetAllAndGetByIdTests {

        @BeforeEach
        void insertTestData() {
            TestEntity entity1 = new TestEntity();
            entity1.setId("get-1");
            entity1.setName("Entity1");
            entity1.setAge(20);
            operator.insert(entity1);

            TestEntity entity2 = new TestEntity();
            entity2.setId("get-2");
            entity2.setName("Entity2");
            entity2.setAge(30);
            operator.insert(entity2);
        }

        @Test
        @DisplayName("getAll() - 应该返回所有记录")
        void getAllShouldReturnAllRecords() {
            List<TestEntity> result = operator.getAll();
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getById - 应该返回正确的记录")
        void getByIdShouldReturnCorrectRecord() {
            TestEntity result = operator.getById("get-1");
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("Entity1");
        }
    }

    @Nested
    @DisplayName("getLike 方法测试")
    class GetLikeTests {

        @BeforeEach
        void insertTestData() {
            TestEntity entity1 = new TestEntity();
            entity1.setId("like-1");
            entity1.setName("TestEntity");
            entity1.setAge(20);
            operator.insert(entity1);

            TestEntity entity2 = new TestEntity();
            entity2.setId("like-2");
            entity2.setName("AnotherTest");
            entity2.setAge(30);
            operator.insert(entity2);
        }

        @Test
        @DisplayName("应该返回匹配 START 的记录")
        void shouldReturnStartMatches() {
            List<TestEntity> result = operator.getLike("name", "Test", LikeType.START);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("TestEntity");
        }

        @Test
        @DisplayName("应该返回匹配 END 的记录")
        void shouldReturnEndMatches() {
            List<TestEntity> result = operator.getLike("name", "Test", LikeType.END);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("AnotherTest");
        }

        @Test
        @DisplayName("应该返回匹配 CONTAINS 的记录")
        void shouldReturnContainsMatches() {
            List<TestEntity> result = operator.getLike("name", "Test", LikeType.CONTAINS);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应该返回空列表当无匹配记录")
        void shouldReturnEmptyListWhenNoMatches() {
            List<TestEntity> result = operator.getLike("name", "NonExistent", LikeType.CONTAINS);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("page 方法测试")
    class PageTests {

        @BeforeEach
        void insertTestData() {
            for (int i = 1; i <= 15; i++) {
                TestEntity entity = new TestEntity();
                entity.setId("page-" + i);
                entity.setName("Entity" + i);
                entity.setAge(20 + i);
                operator.insert(entity);
            }
        }

        @Test
        @DisplayName("应该返回指定页的记录")
        void shouldReturnPagedRecords() {
            List<TestEntity> result = operator.page(1, 5);
            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("应该返回带条件的分页记录")
        void shouldReturnPagedRecordsWithConditions() {
            List<TestEntity> result = operator.page(1, 10, 
                WhereCondition.builder().column("age").value(25).build()
            );
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该返回空列表当页码超出范围")
        void shouldReturnEmptyListWhenPageOutOfRange() {
            List<TestEntity> result = operator.page(100, 10);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("del 方法测试")
    class DelTests {

        @Test
        @DisplayName("del(WhereCondition...) - 应该成功删除匹配条件的记录")
        void delByConditionsShouldDeleteMatchingRecords() {
            TestEntity entity = new TestEntity();
            entity.setId("del-1");
            entity.setName("ToDelete");
            entity.setAge(25);
            operator.insert(entity);
            assertThat(operator.exist(entity)).isTrue();

            operator.del(WhereCondition.builder().column("name").value("ToDelete").build());

            assertThat(operator.exist(entity)).isFalse();
        }

        @Test
        @DisplayName("delById - 应该成功通过 ID 删除记录")
        void delByIdShouldDeleteRecord() {
            TestEntity entity = new TestEntity();
            entity.setId("del-2");
            entity.setName("ToDeleteById");
            entity.setAge(30);
            operator.insert(entity);
            assertThat(operator.exist(entity)).isTrue();

            operator.delById("del-2");

            assertThat(operator.exist(entity)).isFalse();
        }
    }

    @Nested
    @DisplayName("update 方法测试")
    class UpdateTests {

        @Test
        @DisplayName("update(String, Object, Object) - 应该成功更新列值")
        void updateColumnShouldWork() {
            TestEntity entity = new TestEntity();
            entity.setId("update-1");
            entity.setName("OriginalName");
            entity.setAge(25);
            operator.insert(entity);

            operator.update("age", 30, "update-1");

            TestEntity updated = operator.getById("update-1");
            assertThat(updated.getAge()).isEqualTo(30);
        }

        @Test
        @DisplayName("update(T obj) - 应该成功更新整个实体")
        void updateObjectShouldWork() throws Exception {
            TestEntity entity = new TestEntity();
            entity.setId("update-2");
            entity.setName("OriginalName");
            entity.setAge(25);
            entity.setScore(80.0);
            entity.setActive(true);
            operator.insert(entity);

            entity.setName("UpdatedName");
            entity.setAge(30);
            operator.update(entity);

            TestEntity updated = operator.getById("update-2");
            assertThat(updated.getName()).isEqualTo("UpdatedName");
            assertThat(updated.getAge()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("insert - 当插入重复主键时应该抛出 RuntimeException")
        void insertShouldThrowRuntimeExceptionWhenDuplicateKey() {
            TestEntity entity1 = new TestEntity();
            entity1.setId("duplicate-id");
            entity1.setName("First");
            operator.insert(entity1);
            
            TestEntity entity2 = new TestEntity();
            entity2.setId("duplicate-id");
            entity2.setName("Second");
            
            assertThatThrownBy(() -> operator.insert(entity2))
                .isInstanceOf(RuntimeException.class);
        }
    }
}
