package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.dbutils.QueryRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.data.AuditableDataEntity;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.abstracts.data.DataEntityTest;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;
import com.ultikits.ultitools.interfaces.impl.data.AbstractRelationalDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
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
    public static class TestEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        @Column(value = "age", type = "INT")
        private int age;

        @Column(value = "score", type = "DOUBLE")
        private double score;

        @Column(value = "active", type = "BOOLEAN")
        private boolean active;

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
        
        operator = new SQLiteDataOperator<>(dataSource, TestEntity.class);
    }

    // codacy:ignore - Test helper method with controlled input, not exposed to user data
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
    @DisplayName("Comparison 语义测试（GREATER 等运算符跨后端一致，WIRE-04）")
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
        @DisplayName("Test A: getAll(GREATER) 只返回大于阈值的行，而不是等于阈值")
        void getAllGreaterShouldReturnOnlyRowsAboveThreshold() {
            List<TestEntity> result = operator.getAll(
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build());

            assertThat(result).extracting(TestEntity::getAge).containsExactly(9);
        }

        @Test
        @DisplayName("Test B: SQLite 与 SimpleJsonDataOperator 在同一个 GREATER 条件下返回同一行集")
        void greaterShouldAgreeAcrossSqliteAndJson(@TempDir Path jsonDir) {
            SimpleJsonDataOperator<TestEntity> json =
                    new SimpleJsonDataOperator<>(jsonDir.toFile().getAbsolutePath(), TestEntity.class);
            insertNumericInto(json, "json-cmp-1", 1);
            insertNumericInto(json, "json-cmp-5", 5);
            insertNumericInto(json, "json-cmp-9", 9);

            WhereCondition condition =
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build();

            List<Integer> sqliteAges = operator.getAll(condition).stream()
                    .map(TestEntity::getAge).collect(Collectors.toList());
            List<Integer> jsonAges = json.getAll(condition).stream()
                    .map(TestEntity::getAge).collect(Collectors.toList());

            assertThat(sqliteAges)
                    .as("同一个 GREATER 条件，SQLite 与 JSON 后端必须返回同一批年龄值")
                    .containsExactlyInAnyOrderElementsOf(jsonAges)
                    .containsExactly(9);
        }

        private void insertNumericInto(SimpleJsonDataOperator<TestEntity> json, String id, int age) {
            TestEntity entity = new TestEntity();
            entity.setId(id);
            entity.setName("Entity" + age);
            entity.setAge(age);
            json.insert(entity);
        }

        @Test
        @DisplayName("Test C: exist(GREATER) 与 getAll 的 GREATER 语义一致")
        void existGreaterShouldMatchGetAllSemantics() {
            assertThat(operator.exist(
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build()))
                    .as("存在一行 age=9 > 5")
                    .isTrue();

            assertThat(operator.exist(
                    WhereCondition.builder().column("age").value(9).comparison(Comparison.GREATER).build()))
                    .as("没有任何行 age > 9")
                    .isFalse();
        }

        @Test
        @DisplayName("Test C: page(GREATER) 与 getAll 的 GREATER 语义一致")
        void pageGreaterShouldMatchGetAllSemantics() {
            List<TestEntity> result = operator.page(1, 10,
                    WhereCondition.builder().column("age").value(5).comparison(Comparison.GREATER).build());

            assertThat(result).extracting(TestEntity::getAge).containsExactly(9);
        }
    }

    @Nested
    @DisplayName("LIKE 语义测试（INCLUDE/STARTSWITH/ENDSWITH 与 JSON 后端一致，WIRE-04）")
    class LikeComparisonTests {

        @BeforeEach
        void insertNameRows() {
            insertNamed("like-cmp-1", "TestEntity");
            insertNamed("like-cmp-2", "AnotherTest");
        }

        private void insertNamed(String id, String name) {
            TestEntity entity = new TestEntity();
            entity.setId(id);
            entity.setName(name);
            operator.insert(entity);
        }

        @Test
        @DisplayName("Test D: INCLUDE 匹配子串出现在任意位置")
        void includeShouldMatchSubstringAnywhere() {
            List<TestEntity> result = operator.getAll(
                    WhereCondition.builder().column("name").value("Test").comparison(Comparison.INCLUDE).build());

            assertThat(result).extracting(TestEntity::getName)
                    .containsExactlyInAnyOrder("TestEntity", "AnotherTest");
        }

        @Test
        @DisplayName("Test D: STARTSWITH 只匹配以该值开头的行")
        void startswithShouldMatchPrefixOnly() {
            List<TestEntity> result = operator.getAll(WhereCondition.builder().column("name").value("Test")
                    .comparison(Comparison.STARTSWITH).build());

            assertThat(result).extracting(TestEntity::getName).containsExactly("TestEntity");
        }

        @Test
        @DisplayName("Test D: ENDSWITH 只匹配以该值结尾的行")
        void endswithShouldMatchSuffixOnly() {
            List<TestEntity> result = operator.getAll(
                    WhereCondition.builder().column("name").value("Test").comparison(Comparison.ENDSWITH).build());

            assertThat(result).extracting(TestEntity::getName).containsExactly("AnotherTest");
        }
    }

    @Nested
    @DisplayName("del() 零条件防护测试（SILENT-01）")
    class DeleteGuardTests {

        @BeforeEach
        void insertThreeRows() {
            for (int i = 1; i <= 3; i++) {
                TestEntity entity = new TestEntity();
                entity.setId("guard-" + i);
                entity.setName("Guard" + i);
                entity.setAge(i);
                operator.insert(entity);
            }
        }

        private long countRows() throws Exception {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_entity")) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Test
        @DisplayName("Test E: del() 零条件（varargs 空数组）抛出 DataAccessException，行数不变")
        void delWithNoConditionsShouldThrowAndLeaveRowsUnchanged() throws Exception {
            assertThat(countRows()).isEqualTo(3);

            assertThatThrownBy(operator::del).isInstanceOf(DataAccessException.class);

            assertThat(countRows())
                    .as("表未被清空——修复前这里会变成 0")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("Test E: del((WhereCondition[]) null) 与零长度数组行为一致")
        void delWithNullConditionsShouldThrowAndLeaveRowsUnchanged() throws Exception {
            assertThatThrownBy(() -> operator.del((WhereCondition[]) null))
                    .isInstanceOf(DataAccessException.class);

            assertThat(countRows()).isEqualTo(3);
        }

        @Test
        @DisplayName("Test F: 连续两次调用 del() 零条件均抛出，行数不变（守卫不是一次性 latch）")
        void delWithNoConditionsShouldThrowTwiceInARowAndStayIdempotent() throws Exception {
            assertThatThrownBy(operator::del).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(operator::del).isInstanceOf(DataAccessException.class);

            assertThat(countRows()).isEqualTo(3);
        }

        @Test
        @DisplayName("Test G: 守卫在触及 QueryRunner 之前就已生效——update 从未被调用")
        void delWithNoConditionsShouldNeverReachQueryRunner() throws Exception {
            QueryRunner spyRunner = Mockito.spy(new QueryRunner(dataSource));
            Field field = AbstractRelationalDataOperator.class.getDeclaredField("queryRunner");
            field.setAccessible(true);
            field.set(operator, spyRunner);

            assertThatThrownBy(operator::del).isInstanceOf(DataAccessException.class);

            Mockito.verifyNoInteractions(spyRunner);
        }

        @Test
        @DisplayName("Test H（正对照）: del(单条件) 仍然只删除匹配的行")
        void delWithOneConditionShouldStillDeleteOnlyMatchingRow() throws Exception {
            operator.del(WhereCondition.builder().column("name").value("Guard1").build());

            assertThat(countRows()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("列名白名单测试（T-02-SQLI-1）")
    class ColumnValidationTests {

        @BeforeEach
        void insertOneRow() {
            TestEntity entity = new TestEntity();
            entity.setId("colval-1");
            entity.setName("ColVal");
            entity.setAge(20);
            operator.insert(entity);
        }

        @Test
        @DisplayName("未知列名在 getAll 上被拒绝，消息包含列名与实体类型")
        void getAllWithUnknownColumnShouldBeRejected() {
            assertThatThrownBy(() -> operator.getAll(
                    WhereCondition.builder().column("no_such_column").value("x").build()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("no_such_column")
                    .hasMessageContaining(TestEntity.class.getName());
        }

        @Test
        @DisplayName("未知列名在 exist/page/del 上同样被拒绝")
        void unknownColumnShouldBeRejectedOnExistPageAndDel() {
            WhereCondition badCondition = WhereCondition.builder().column("no_such_column").value("x").build();

            assertThatThrownBy(() -> operator.exist(badCondition)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> operator.page(1, 10, badCondition)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> operator.del(badCondition)).isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("正对照：真实 @Column 名不受影响")
        void realColumnNameShouldNotBeAffected() {
            List<TestEntity> result = operator.getAll(
                    WhereCondition.builder().column("name").value("ColVal").build());

            assertThat(result).extracting(TestEntity::getName).containsExactly("ColVal");
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

    /**
     * SILENT-02 (02-08): {@code onCreate}/{@code onUpdate}/{@code onDelete}/{@code onLoad} were
     * declared on {@code BaseDataEntity} but nothing in this class ever called them, and
     * {@code AuditableDataEntity#setCurrentUser} had zero call sites -- so {@code created_at}/
     * {@code created_by}/{@code updated_at}/{@code updated_by} were always {@code NULL} on every
     * persisted row. Both halves are pinned here against the relational backend; the identical
     * set is pinned against {@code SimpleJsonDataOperatorTest} so the two backends cannot diverge.
     */
    @Nested
    @DisplayName("生命周期钩子测试（SILENT-02）")
    class LifecycleHookTests {

        private AbstractRelationalDataOperator<DataEntityTest.CountingAuditableEntity> hookOperator;

        @BeforeEach
        void setUpHookOperator() {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS counting_auditable_entity");
            } catch (Exception ignored) {
            }
            hookOperator = new SQLiteDataOperator<>(dataSource, DataEntityTest.CountingAuditableEntity.class);
            DataEntityTest.CountingAuditableEntity.resetCounters();
            AuditableDataEntity.clearCurrentUser();
        }

        @AfterEach
        void tearDownHookOperator() {
            DataEntityTest.CountingAuditableEntity.resetCounters();
            AuditableDataEntity.clearCurrentUser();
        }

        private DataEntityTest.CountingAuditableEntity newEntity(String label) {
            DataEntityTest.CountingAuditableEntity entity = new DataEntityTest.CountingAuditableEntity();
            entity.setLabel(label);
            return entity;
        }

        private long countRows() throws Exception {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM counting_auditable_entity")) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Test
        @DisplayName("insert 应填充 created_at 与 created_by（此前一直是 NULL）")
        void insertShouldPopulateCreatedAtAndCreatedBy() {
            UUID user = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(user);
            DataEntityTest.CountingAuditableEntity entity = newEntity("insert-1");

            hookOperator.insert(entity);

            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(entity.getCreatedBy()).isEqualTo(user);
        }

        @Test
        @DisplayName("update 应填充 updated_at 与 updated_by（此前一直是 NULL）")
        void updateShouldPopulateUpdatedAtAndUpdatedBy() throws Exception {
            DataEntityTest.CountingAuditableEntity entity = newEntity("update-1");
            hookOperator.insert(entity);

            UUID user = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(user);
            entity.setLabel("update-1-changed");
            hookOperator.update(entity);

            assertThat(entity.getUpdatedAt()).isNotNull();
            assertThat(entity.getUpdatedBy()).isEqualTo(user);
        }

        @Test
        @DisplayName("insert/update/getById/delById 各自恰好触发一次对应钩子")
        void hooksFireExactlyOnceEachForSingleEntityOperations() throws Exception {
            DataEntityTest.CountingAuditableEntity entity = newEntity("count-1");

            hookOperator.insert(entity);
            assertThat(DataEntityTest.CountingAuditableEntity.onCreateCount()).isEqualTo(1);

            entity.setLabel("count-1-updated");
            hookOperator.update(entity);
            assertThat(DataEntityTest.CountingAuditableEntity.onUpdateCount()).isEqualTo(1);

            DataEntityTest.CountingAuditableEntity loaded = hookOperator.getById(entity.getId());
            assertThat(loaded).isNotNull();
            assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount()).isEqualTo(1);

            hookOperator.delById(entity.getId());
            assertThat(DataEntityTest.CountingAuditableEntity.onDeleteCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("getAll/page 对返回的每个实体恰好触发一次 onLoad")
        void getAllAndPageFireOnLoadOncePerReturnedEntity() {
            hookOperator.insert(newEntity("load-1"));
            hookOperator.insert(newEntity("load-2"));
            hookOperator.insert(newEntity("load-3"));
            DataEntityTest.CountingAuditableEntity.resetCounters();

            List<DataEntityTest.CountingAuditableEntity> all = hookOperator.getAll();
            assertThat(all).hasSize(3);
            assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount()).isEqualTo(3);

            DataEntityTest.CountingAuditableEntity.resetCounters();
            List<DataEntityTest.CountingAuditableEntity> page = hookOperator.page(1, 2);
            assertThat(page).hasSize(2);
            assertThat(DataEntityTest.CountingAuditableEntity.onLoadCount())
                    .as("onLoad must fire only for the entities actually returned by the page, not every matched row")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("插入后立即更新：created_at 保持插入时的值，updated_at 非空")
        void adjacentInsertThenUpdatePreservesCreatedAt() throws Exception {
            DataEntityTest.CountingAuditableEntity entity = newEntity("adjacency-1");
            hookOperator.insert(entity);
            LocalDateTime createdAt = entity.getCreatedAt();
            assertThat(createdAt).isNotNull();

            entity.setLabel("adjacency-1-updated");
            hookOperator.update(entity);

            assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
            assertThat(entity.getUpdatedAt()).isNotNull();

            DataEntityTest.CountingAuditableEntity reloaded = hookOperator.getById(entity.getId());
            assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
            assertThat(reloaded.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("insertAll(空列表) 不触发任何钩子，也不写入任何行")
        void insertAllEmptyFiresNoHooksAndWritesNoRows() throws Exception {
            hookOperator.insertAll(Collections.emptyList());

            assertThat(DataEntityTest.CountingAuditableEntity.onCreateCount()).isEqualTo(0);
            assertThat(countRows()).isEqualTo(0);
        }

        @Test
        @DisplayName("未设置当前用户时，created_by/updated_by 写入 SQL NULL 而非占位字符串")
        void nullActorWritesSqlNullNotPlaceholderString() throws Exception {
            DataEntityTest.CountingAuditableEntity entity = newEntity("null-actor-1");
            hookOperator.insert(entity);

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT created_by, updated_by FROM counting_auditable_entity WHERE id = '"
                                 + entity.getId() + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("created_by")).isNull();
                assertThat(rs.getObject("updated_by")).isNull();
                // Java's null-to-string coercion for a missing reference would render as the
                // 4-character literal "null" -- assert the column holds no such string either.
                assertThat(rs.getString("created_by")).isNotEqualTo("null");
                assertThat(rs.getString("updated_by")).isNotEqualTo("null");
            }
        }

        @Test
        @DisplayName("insertAll(3 个实体) 按列表顺序恰好触发 3 次 onCreate")
        void insertAllFiresOnCreateThreeTimesInOrder() {
            DataEntityTest.CountingAuditableEntity e1 = newEntity("batch-1");
            e1.setId("batch-id-1");
            DataEntityTest.CountingAuditableEntity e2 = newEntity("batch-2");
            e2.setId("batch-id-2");
            DataEntityTest.CountingAuditableEntity e3 = newEntity("batch-3");
            e3.setId("batch-id-3");

            hookOperator.insertAll(Arrays.asList(e1, e2, e3));

            assertThat(DataEntityTest.CountingAuditableEntity.onCreateCount()).isEqualTo(3);
            assertThat(DataEntityTest.CountingAuditableEntity.onCreateOrder())
                    .containsExactly("batch-id-1", "batch-id-2", "batch-id-3");
        }

        @Test
        @DisplayName("updateAll(3 个实体) 按列表顺序恰好触发 3 次 onUpdate")
        void updateAllFiresOnUpdateThreeTimesInOrder() throws Exception {
            DataEntityTest.CountingAuditableEntity e1 = newEntity("batch-u-1");
            e1.setId("batch-u-id-1");
            DataEntityTest.CountingAuditableEntity e2 = newEntity("batch-u-2");
            e2.setId("batch-u-id-2");
            DataEntityTest.CountingAuditableEntity e3 = newEntity("batch-u-3");
            e3.setId("batch-u-id-3");
            hookOperator.insertAll(Arrays.asList(e1, e2, e3));
            DataEntityTest.CountingAuditableEntity.resetCounters();

            hookOperator.updateAll(Arrays.asList(e1, e2, e3));

            assertThat(DataEntityTest.CountingAuditableEntity.onUpdateCount()).isEqualTo(3);
            assertThat(DataEntityTest.CountingAuditableEntity.onUpdateOrder())
                    .containsExactly("batch-u-id-1", "batch-u-id-2", "batch-u-id-3");
        }

        @Test
        @DisplayName("del(WhereCondition...) 按条件删除，但不会触发 onDelete（未实体化被删行）")
        void delByConditionDoesNotFireOnDelete() {
            DataEntityTest.CountingAuditableEntity entity = newEntity("del-condition-1");
            hookOperator.insert(entity);
            DataEntityTest.CountingAuditableEntity.resetCounters();

            hookOperator.del(WhereCondition.builder().column("label").value("del-condition-1").build());

            assertThat(DataEntityTest.CountingAuditableEntity.onDeleteCount())
                    .as("del(WhereCondition...) deletes by predicate without materialising rows -- it must not fire onDelete")
                    .isEqualTo(0);
        }
    }
}
