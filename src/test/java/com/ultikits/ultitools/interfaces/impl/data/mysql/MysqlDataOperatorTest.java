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

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
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
    public static class TestEntity extends AbstractDataEntity {
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
        config.setPassword(""); // nosemgrep: java.lang.security.audit.hardcoded-password - H2 in-memory test database
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
