package com.ultikits.ultitools.interfaces.impl.data.mysql;

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

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.EqualsAndHashCode;

/**
 * MysqlDataOperator 完整测试类
 * 使用 H2 内存数据库进行集成测试
 */
@DisplayName("MysqlDataOperator 测试")
class MysqlDataOperatorTest {

    private static DataSource dataSource;
    private MysqlDataOperator<TestEntity> operator;

    @EqualsAndHashCode(callSuper = true)
    @Table("test_entity")
    public static class TestEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        @Column(value = "age", type = "INT")
        private int age;

        @Column(value = "score", type = "DOUBLE")
        private double score;

        @Column(value = "active", type = "BOOLEAN")
        private boolean active;

        // No-arg constructor required by ORM reflection - Java provides default but explicit is clearer
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
        config.setJdbcUrl("jdbc:h2:mem:mysqltest;DB_CLOSE_DELAY=-1;MODE=MySQL");
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
            stmt.execute("DROP TABLE IF EXISTS test_entity");
        } catch (Exception ignored) {
        }
        
        operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {
        @Test
        @DisplayName("应该成功创建表并初始化 MysqlDataOperator")
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
    }

    @Nested
    @DisplayName("Comparison 语义测试（GREATER 与 SQLite/JSON 后端一致，WIRE-04）")
    class ComparisonSemanticsTests {

        @BeforeEach
        void insertNumericRows() {
            insertNumeric("cmp-1", 1);
            insertNumeric("cmp-5", 5);
            insertNumeric("cmp-9", 9);
        }

        private void insertNumeric(String id, int age) {
            TestEntity entity = new TestEntity();
            entity.setId(id);
            entity.setName("Entity" + age);
            entity.setAge(age);
            operator.insert(entity);
        }

        @Test
        @DisplayName("getAll(GREATER) 只返回大于阈值的行，而不是等于阈值")
        void getAllGreaterShouldReturnOnlyRowsAboveThreshold() {
            List<TestEntity> result = operator.getAll(
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build());

            assertThat(result).extracting(TestEntity::getAge).containsExactly(9);
        }

        @Test
        @DisplayName("exist(GREATER) 与 getAll 的 GREATER 语义一致")
        void existGreaterShouldMatchGetAllSemantics() {
            assertThat(operator.exist(
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build()))
                    .isTrue();
            assertThat(operator.exist(
                    WhereCondition.builder().column("age").value(9).comparison(Comparison.GREATER).build()))
                    .isFalse();
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
        }

        @Test
        @DisplayName("应该返回匹配 END 的记录")
        void shouldReturnEndMatches() {
            List<TestEntity> result = operator.getLike("name", "Test", LikeType.END);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该返回匹配 CONTAINS 的记录")
        void shouldReturnContainsMatches() {
            List<TestEntity> result = operator.getLike("name", "Test", LikeType.CONTAINS);
            assertThat(result).hasSize(2);
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
