package com.ultikits.ultitools.interfaces.impl.data.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import cn.hutool.db.Db;
import cn.hutool.db.ds.simple.SimpleDataSource;
import cn.hutool.db.sql.Condition;
import lombok.EqualsAndHashCode;

/**
 * MysqlDataOperator 完整测试类
 * 使用 H2 内存数据库进行集成测试，覆盖所有方法和分支
 */
@DisplayName("MysqlDataOperator 测试")
class MysqlDataOperatorTest {

    private static DataSource dataSource;
    private MysqlDataOperator<TestEntity> operator;

    /**
     * 测试用实体类 - 基础类型字段
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("test_entity")
    public static class TestEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        @Column("name")
        private String name;

        @Column(value = "age", type = "INT")
        private int age;

        @Column(value = "score", type = "DOUBLE")
        private double score;

        @Column(value = "active", type = "BOOLEAN")
        private boolean active;

        public TestEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    /**
     * 测试用实体类 - 有重复列定义
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("duplicate_columns_entity")
    public static class DuplicateColumnsEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        @Column("name")
        private String name;

        // 重复的列名 - 用于测试 createTableSqlFromClazz 中的去重逻辑
        @Column("name")
        private String duplicateName;

        public DuplicateColumnsEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDuplicateName() { return duplicateName; }
        public void setDuplicateName(String duplicateName) { this.duplicateName = duplicateName; }
    }

    /**
     * 测试用实体类 - 无效表名（用于测试构造函数异常）
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("invalid table name with spaces")
    public static class InvalidTableEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        public InvalidTableEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
    }

    @BeforeAll
    static void initDataSource() {
        // 使用 H2 内存数据库，兼容 MySQL 语法
        dataSource = new SimpleDataSource("jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @BeforeEach
    void setUp() throws Exception {
        // 清理测试表
        try {
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            Db.use(dataSource).execute("DROP TABLE IF EXISTS duplicate_columns_entity");
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
            // Assert - operator 已在 setUp 中成功创建
            assertThat(operator).isNotNull();
        }

        @Test
        @DisplayName("应该正确处理包含重复列定义的实体类")
        void shouldHandleDuplicateColumnDefinitions() {
            // Act
            MysqlDataOperator<DuplicateColumnsEntity> dupOperator = 
                new MysqlDataOperator<>(dataSource, DuplicateColumnsEntity.class);

            // Assert
            assertThat(dupOperator).isNotNull();
        }
    }

    @Nested
    @DisplayName("insert 和 exist 方法测试")
    class InsertAndExistTests {

        @Test
        @DisplayName("应该成功插入实体并检查存在")
        void shouldInsertAndCheckExist() {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("test-1");
            entity.setName("TestName");
            entity.setAge(25);
            entity.setScore(90.5);
            entity.setActive(true);

            // Act
            operator.insert(entity);

            // Assert
            assertThat(operator.exist(entity)).isTrue();
            assertThat(operator.exist(WhereCondition.builder().column("id").value("test-1").build())).isTrue();
        }

        @Test
        @DisplayName("exist(WhereCondition...) - 应该返回 false 当记录不存在")
        void existShouldReturnFalseWhenRecordNotExists() {
            // Act
            boolean result = operator.exist(WhereCondition.builder().column("name").value("nonexistent").build());

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("exist(WhereCondition...) - 应该正确处理空条件")
        void existShouldHandleEmptyCondition() {
            // Arrange - 先插入一条数据
            TestEntity entity = new TestEntity();
            entity.setId("test-2");
            entity.setName("Test");
            entity.setAge(20);
            operator.insert(entity);

            // Act - 空条件应该返回所有记录
            boolean result = operator.exist(WhereCondition.empty());

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("getById 方法测试")
    class GetByIdTests {

        @Test
        @DisplayName("应该返回正确的实体当记录存在")
        void shouldReturnEntityWhenRecordExists() {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("getbyid-1");
            entity.setName("TestName");
            entity.setAge(25);
            entity.setScore(95.5);
            entity.setActive(true);
            operator.insert(entity);

            // Act
            TestEntity result = operator.getById("getbyid-1");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("TestName");
            assertThat(result.getAge()).isEqualTo(25);
        }

        @Test
        @DisplayName("应该返回 null 当记录不存在")
        void shouldReturnNullWhenRecordNotExists() {
            // Act
            TestEntity result = operator.getById("nonexistent");

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getAll 方法测试")
    class GetAllTests {

        @BeforeEach
        void insertTestData() {
            TestEntity entity1 = new TestEntity();
            entity1.setId("all-1");
            entity1.setName("Entity1");
            entity1.setAge(20);
            operator.insert(entity1);

            TestEntity entity2 = new TestEntity();
            entity2.setId("all-2");
            entity2.setName("Entity2");
            entity2.setAge(30);
            operator.insert(entity2);
        }

        @Test
        @DisplayName("getAll() - 应该返回所有记录")
        void getAllShouldReturnAllRecords() {
            // Act
            List<TestEntity> result = operator.getAll();

            // Assert
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("getAll(WhereCondition...) - 应该返回匹配条件的记录")
        void getAllWithConditionsShouldReturnMatchingRecords() {
            // Act
            List<TestEntity> result = operator.getAll(
                WhereCondition.builder().column("name").value("Entity1").build()
            );

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("Entity1");
        }

        @Test
        @DisplayName("getAll(WhereCondition...) - 应该返回空列表当无匹配记录")
        void getAllShouldReturnEmptyListWhenNoRecords() {
            // Act
            List<TestEntity> result = operator.getAll(
                WhereCondition.builder().column("name").value("NonExistent").build()
            );

            // Assert
            assertThat(result).isEmpty();
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
            entity1.setAge(25);
            operator.insert(entity1);

            TestEntity entity2 = new TestEntity();
            entity2.setId("like-2");
            entity2.setName("AnotherTest");
            entity2.setAge(30);
            operator.insert(entity2);
        }

        @Test
        @DisplayName("应该返回匹配 StartWith 的记录")
        void shouldReturnStartWithMatches() {
            // Act
            List<TestEntity> result = operator.getLike("name", "Test", Condition.LikeType.StartWith);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("TestEntity");
        }

        @Test
        @DisplayName("应该返回匹配 EndWith 的记录")
        void shouldReturnEndWithMatches() {
            // Act
            List<TestEntity> result = operator.getLike("name", "Test", Condition.LikeType.EndWith);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("AnotherTest");
        }

        @Test
        @DisplayName("应该返回匹配 Contains 的记录")
        void shouldReturnContainsMatches() {
            // Act
            List<TestEntity> result = operator.getLike("name", "Test", Condition.LikeType.Contains);

            // Assert
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("应该返回空列表当无匹配记录")
        void shouldReturnEmptyListWhenNoMatches() {
            // Act
            List<TestEntity> result = operator.getLike("name", "NonExistent", Condition.LikeType.Contains);

            // Assert
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
            // Act
            List<TestEntity> result = operator.page(0, 5);

            // Assert
            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("应该返回带条件的分页记录")
        void shouldReturnPagedRecordsWithConditions() {
            // Act
            List<TestEntity> result = operator.page(0, 10, 
                WhereCondition.builder().column("age").value(25).build()
            );

            // Assert
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("应该返回空列表当页码超出范围")
        void shouldReturnEmptyListWhenPageOutOfRange() {
            // Act
            List<TestEntity> result = operator.page(100, 10);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("del 方法测试")
    class DelTests {

        @Test
        @DisplayName("del(WhereCondition...) - 应该成功删除匹配条件的记录")
        void delByConditionsShouldDeleteMatchingRecords() {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("del-1");
            entity.setName("ToDelete");
            entity.setAge(25);
            operator.insert(entity);
            assertThat(operator.exist(entity)).isTrue();

            // Act
            operator.del(WhereCondition.builder().column("name").value("ToDelete").build());

            // Assert
            assertThat(operator.exist(entity)).isFalse();
        }

        @Test
        @DisplayName("delById - 应该成功通过 ID 删除记录")
        void delByIdShouldDeleteRecord() {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("del-2");
            entity.setName("ToDeleteById");
            entity.setAge(30);
            operator.insert(entity);
            assertThat(operator.exist(entity)).isTrue();

            // Act
            operator.delById("del-2");

            // Assert
            assertThat(operator.exist(entity)).isFalse();
        }
    }

    @Nested
    @DisplayName("update 方法测试")
    class UpdateTests {

        @Test
        @DisplayName("update(String, Object, Object) - 应该成功更新列值")
        void updateColumnShouldWork() {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("update-1");
            entity.setName("OriginalName");
            entity.setAge(25);
            operator.insert(entity);

            // Act - 注意：MysqlDataOperator.update() 会将值转为 JSON，所以字符串会被引号包裹
            operator.update("age", 30, "update-1");

            // Assert
            TestEntity updated = operator.getById("update-1");
            assertThat(updated.getAge()).isEqualTo(30);
        }

        @Test
        @DisplayName("update(T obj) - 应该成功更新整个实体")
        void updateObjectShouldWork() throws Exception {
            // Arrange
            TestEntity entity = new TestEntity();
            entity.setId("update-2");
            entity.setName("OriginalName");
            entity.setAge(25);
            entity.setScore(80.0);
            entity.setActive(true);
            operator.insert(entity);

            // Act
            entity.setName("UpdatedName");
            entity.setAge(30);
            operator.update(entity);

            // Assert
            TestEntity updated = operator.getById("update-2");
            assertThat(updated.getName()).isEqualTo("UpdatedName");
            assertThat(updated.getAge()).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("copyEntity 私有方法测试 (通过 insert 间接测试)")
    class CopyEntityTests {

        @Test
        @DisplayName("应该跳过已设置的重复列值")
        void shouldSkipDuplicateColumnValues() {
            // Arrange
            MysqlDataOperator<DuplicateColumnsEntity> dupOperator = 
                new MysqlDataOperator<>(dataSource, DuplicateColumnsEntity.class);

            DuplicateColumnsEntity entity = new DuplicateColumnsEntity();
            entity.setId("dup-1");
            entity.setName("Original");
            entity.setDuplicateName("Duplicate");

            // Act
            dupOperator.insert(entity);

            // Assert - 应该不抛出异常，name 列只设置一次
            DuplicateColumnsEntity retrieved = dupOperator.getById("dup-1");
            assertThat(retrieved).isNotNull();
        }
    }

    @Nested
    @DisplayName("createQueryEntity 私有方法测试")
    class CreateQueryEntityTests {

        @BeforeEach
        void insertTestData() {
            TestEntity entity = new TestEntity();
            entity.setId("query-1");
            entity.setName("QueryTest");
            entity.setAge(25);
            operator.insert(entity);
        }

        @Test
        @DisplayName("应该正确处理空条件")
        void shouldHandleEmptyCondition() {
            // Act
            List<TestEntity> result = operator.getAll(WhereCondition.empty());

            // Assert
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("应该正确处理多个条件")
        void shouldHandleMultipleConditions() {
            // Arrange
            TestEntity entity2 = new TestEntity();
            entity2.setId("query-2");
            entity2.setName("QueryTest");
            entity2.setAge(30);
            operator.insert(entity2);

            // Act
            List<TestEntity> result = operator.getAll(
                WhereCondition.builder().column("name").value("QueryTest").build(),
                WhereCondition.builder().column("age").value(25).build()
            );

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAge()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("构造函数 - 当创建表失败时应该抛出 RuntimeException")
        void constructorShouldThrowRuntimeExceptionWhenCreateTableFails() {
            // Arrange - 使用无法连接的数据源
            DataSource invalidDataSource = new SimpleDataSource(
                "jdbc:h2:mem:;MODE=MySQL;INIT=CREATE TABLE dummy(id INT);SHUTDOWN IMMEDIATELY", "sa", ""
            );
            
            // Act & Assert - 使用已关闭的数据库连接会导致异常
            assertThatThrownBy(() -> {
                // 首先创建一个会立即关闭的数据库
                try {
                    Db.use(invalidDataSource).execute("SELECT 1");
                } catch (Exception ignored) {}
                // 然后尝试在其上创建表
                new MysqlDataOperator<>(invalidDataSource, TestEntity.class);
            }).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("exist(WhereCondition...) - 当查询失败时应该返回 false")
        void existShouldReturnFalseWhenQueryFails() throws Exception {
            // Arrange - 删除表以模拟查询失败
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            
            // 创建新的 operator（不会重新创建表因为我们直接操作）
            // 需要重新创建 operator 让它创建表，然后删除
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act
            boolean result = operator.exist(WhereCondition.builder().column("id").value("test").build());
            
            // Assert - 当表不存在时查询失败，应该返回 false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getById - 当查询失败时应该抛出 RuntimeException")
        void getByIdShouldThrowRuntimeExceptionWhenQueryFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.getById("test-id"))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("getAll - 当查询失败时应该抛出 RuntimeException")
        void getAllShouldThrowRuntimeExceptionWhenQueryFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.getAll())
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("getLike - 当查询失败时应该抛出 RuntimeException")
        void getLikeShouldThrowRuntimeExceptionWhenQueryFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.getLike("name", "test", Condition.LikeType.Contains))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("page - 当查询失败时应该抛出 RuntimeException")
        void pageShouldThrowRuntimeExceptionWhenQueryFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.page(0, 10))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("insert - 当插入失败时应该抛出 RuntimeException")
        void insertShouldThrowRuntimeExceptionWhenInsertFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            TestEntity entity = new TestEntity();
            entity.setId("test-1");
            entity.setName("Test");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.insert(entity))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("del - 当删除失败时应该抛出 RuntimeException")
        void delShouldThrowRuntimeExceptionWhenDeleteFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.del(WhereCondition.builder().column("id").value("test").build()))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("delById - 当删除失败时应该抛出 RuntimeException")
        void delByIdShouldThrowRuntimeExceptionWhenDeleteFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.delById("test-id"))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("update(column, value, id) - 当更新失败时应该抛出 RuntimeException")
        void updateColumnShouldThrowRuntimeExceptionWhenUpdateFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.update("name", "NewName", "test-id"))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("update(obj) - 当更新失败时应该抛出 RuntimeException")
        void updateObjectShouldThrowRuntimeExceptionWhenUpdateFails() throws Exception {
            // Arrange
            Db.use(dataSource).execute("DROP TABLE IF EXISTS test_entity");
            operator = new MysqlDataOperator<>(dataSource, TestEntity.class);
            Db.use(dataSource).execute("DROP TABLE test_entity");
            
            TestEntity entity = new TestEntity();
            entity.setId("test-1");
            entity.setName("Test");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.update(entity))
                .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("insert - 当插入重复主键时应该抛出 RuntimeException")
        void insertShouldThrowRuntimeExceptionWhenDuplicateKey() {
            // Arrange
            TestEntity entity1 = new TestEntity();
            entity1.setId("duplicate-id");
            entity1.setName("First");
            operator.insert(entity1);
            
            TestEntity entity2 = new TestEntity();
            entity2.setId("duplicate-id");
            entity2.setName("Second");
            
            // Act & Assert
            assertThatThrownBy(() -> operator.insert(entity2))
                .isInstanceOf(RuntimeException.class);
        }
    }
}
