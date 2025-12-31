package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;

import cn.hutool.db.ds.simple.SimpleDataSource;
import lombok.EqualsAndHashCode;

/**
 * SQLiteDataStore 完整测试类
 * 覆盖所有方法和分支
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SQLiteDataStore 测试")
class SQLiteDataStoreTest {

    @TempDir
    File tempDir;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private UltiToolsPlugin mockPlugin;

    private MockedStatic<UltiTools> ultiToolsStaticMock;

    /**
     * 测试用实体类 - 带 @Table 注解
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("test_table")
    public static class TestDataEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        @Column("name")
        private String name;

        @Column(value = "score", type = "INT")
        private int score;

        public TestDataEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
    }

    /**
     * 测试用实体类 - 无 @Table 注解
     */
    @EqualsAndHashCode(callSuper = true)
    public static class NoTableAnnotationEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        @Column("name")
        private String name;

        public NoTableAnnotationEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    /**
     * 测试用实体类 - 另一个带 @Table 注解的实体
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("another_table")
    public static class AnotherDataEntity extends AbstractDataEntity {
        @Column("id")
        private Object id;

        @Column("description")
        private String description;

        public AnotherDataEntity() {}

        @Override
        public Object getId() { return id; }
        @Override
        public void setId(Object id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * 清理 SQLiteDataStore 中的静态缓存
     */
    private void clearStaticCache() {
        try {
            Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
            field.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) field.get(null);
            map.clear();
        } catch (Exception e) {
            // 忽略反射异常
        }
    }

    @BeforeEach
    void setUp() {
        clearStaticCache();
        ultiToolsStaticMock = mockStatic(UltiTools.class);
        ultiToolsStaticMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(mockPlugin.getPluginName()).thenReturn("TestPlugin");
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsStaticMock != null) {
            ultiToolsStaticMock.close();
        }
        clearStaticCache();
    }

    @Nested
    @DisplayName("getStoreType 测试")
    class GetStoreTypeTests {

        @Test
        @DisplayName("应该返回 'sqlite'")
        void shouldReturnSqlite() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act
            String storeType = store.getStoreType();

            // Assert
            assertThat(storeType).isEqualTo("sqlite");
        }
    }

    @Nested
    @DisplayName("getOperator 测试")
    class GetOperatorTests {

        @Test
        @DisplayName("应该成功获取带 @Table 注解的实体的 DataOperator (使用 H2)")
        void shouldGetOperatorForAnnotatedEntity() {
            // Arrange - 使用 H2 内存数据库代替 SQLite
            DataSource h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqlitestoretest;DB_CLOSE_DELAY=-1", "sa", "");
            SQLiteDataStore store = new SQLiteDataStore();
            
            // 通过反射直接测试缓存机制
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);
                
                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
                assertThat(operator).isSameAs(h2Operator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("应该抛出 RuntimeException 当实体没有 @Table 注解")
        void shouldThrowExceptionForEntityWithoutTableAnnotation() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act & Assert
            assertThatThrownBy(() -> store.getOperator(mockPlugin, NoTableAnnotationEntity.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No Table annotation is presented!");
        }

        @Test
        @DisplayName("应该返回缓存的 DataOperator 实例")
        void shouldReturnCachedOperator() {
            // Arrange - 使用 H2 内存数据库
            DataSource h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqlitecachetest;DB_CLOSE_DELAY=-1", "sa", "");
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);

                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator1).isSameAs(operator2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("应该为不同的实体类返回不同的 DataOperator")
        void shouldReturnDifferentOperatorsForDifferentEntities() {
            // Arrange - 使用 H2 内存数据库
            DataSource h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqlitedifftest;DB_CLOSE_DELAY=-1", "sa", "");
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator1 = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                SQLiteDataOperator<AnotherDataEntity> h2Operator2 = new SQLiteDataOperator<>(h2DataSource, AnotherDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator1);
                map.put(AnotherDataEntity.class, h2Operator2);

                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<AnotherDataEntity> operator2 = store.getOperator(mockPlugin, AnotherDataEntity.class);

                // Assert
                assertThat(operator1).isNotSameAs(operator2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("应该检查 @Table 注解")
        void shouldCheckTableAnnotation() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act & Assert - 没有 @Table 注解应该抛出异常
            assertThatThrownBy(() -> store.getOperator(mockPlugin, NoTableAnnotationEntity.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No Table annotation");
        }

        @Test
        @DisplayName("当缓存未命中时应该创建新的 DataOperator 并缓存")
        void shouldCreateNewOperatorWhenCacheMiss() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();
            
            // 使用 MockedConstruction 来 mock SimpleDataSource 和 SQLiteDataOperator
            DataSource mockDataSource = mock(DataSource.class);
            
            try (MockedConstruction<SimpleDataSource> simpleDataSourceMock = mockConstruction(SimpleDataSource.class,
                    (mock, context) -> {
                        // 验证构造函数参数
                        String jdbcUrl = (String) context.arguments().get(0);
                        assertThat(jdbcUrl).startsWith("jdbc:sqlite://");
                        assertThat(jdbcUrl).contains("TestPlugin.db");
                    });
                 MockedConstruction<SQLiteDataOperator> operatorMock = mockConstruction(SQLiteDataOperator.class)) {
                
                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Assert
                assertThat(operator).isNotNull();
                assertThat(simpleDataSourceMock.constructed()).hasSize(1);
                assertThat(operatorMock.constructed()).hasSize(1);
                
                // 验证缓存中已存储
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                assertThat(map).containsKey(TestDataEntity.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("当数据文件夹不存在时应该创建文件夹")
        void shouldCreateDataFolderWhenNotExists() {
            // Arrange
            File newTempDir = new File(tempDir, "newDataFolder");
            assertThat(newTempDir.exists()).isFalse(); // 确保文件夹不存在
            
            when(mockUltiTools.getDataFolder()).thenReturn(newTempDir);
            SQLiteDataStore store = new SQLiteDataStore();
            
            try (MockedConstruction<SimpleDataSource> simpleDataSourceMock = mockConstruction(SimpleDataSource.class);
                 MockedConstruction<SQLiteDataOperator> operatorMock = mockConstruction(SQLiteDataOperator.class)) {
                
                // Act
                store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Assert - 文件夹应该被创建
                File sqliteDir = new File(newTempDir, "sqliteDB");
                assertThat(sqliteDir).exists();
                assertThat(sqliteDir.isDirectory()).isTrue();
            }
        }

        @Test
        @DisplayName("当数据文件夹已存在时不应该重复创建")
        void shouldNotCreateFolderWhenAlreadyExists() {
            // Arrange - 预先创建文件夹
            File sqliteDir = new File(tempDir, "sqliteDB");
            sqliteDir.mkdirs();
            assertThat(sqliteDir.exists()).isTrue();
            
            when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
            SQLiteDataStore store = new SQLiteDataStore();
            
            try (MockedConstruction<SimpleDataSource> simpleDataSourceMock = mockConstruction(SimpleDataSource.class);
                 MockedConstruction<SQLiteDataOperator> operatorMock = mockConstruction(SQLiteDataOperator.class)) {
                
                // Act
                store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Assert - 文件夹仍然存在
                assertThat(sqliteDir).exists();
            }
        }

        @Test
        @DisplayName("应该使用正确的数据库路径")
        void shouldUseCorrectDatabasePath() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();
            
            try (MockedConstruction<SimpleDataSource> simpleDataSourceMock = mockConstruction(SimpleDataSource.class,
                    (mock, context) -> {
                        String jdbcUrl = (String) context.arguments().get(0);
                        // 验证路径包含 sqliteDB 目录和插件名
                        assertThat(jdbcUrl).contains("sqliteDB");
                        assertThat(jdbcUrl).contains("TestPlugin.db");
                    });
                 MockedConstruction<SQLiteDataOperator> operatorMock = mockConstruction(SQLiteDataOperator.class)) {
                
                // Act
                store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Assert
                assertThat(simpleDataSourceMock.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("第二次调用应该返回缓存的 operator 而不是创建新的")
        void shouldReturnCachedOperatorOnSecondCall() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();
            
            try (MockedConstruction<SimpleDataSource> simpleDataSourceMock = mockConstruction(SimpleDataSource.class);
                 MockedConstruction<SQLiteDataOperator> operatorMock = mockConstruction(SQLiteDataOperator.class)) {
                
                // Act - 第一次调用
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Act - 第二次调用
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);
                
                // Assert - 只应该创建一次
                assertThat(simpleDataSourceMock.constructed()).hasSize(1);
                assertThat(operatorMock.constructed()).hasSize(1);
                assertThat(operator1).isSameAs(operator2);
            }
        }
    }

    @Nested
    @DisplayName("destroyAllOperators 测试")
    class DestroyAllOperatorsTests {

        @Test
        @DisplayName("destroyAllOperators 方法应该可以正常调用")
        void destroyAllOperatorsShouldWork() {
            // Arrange
            DataSource h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqlitedestroytest;DB_CLOSE_DELAY=-1", "sa", "");
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Act & Assert - 不应该抛出异常
            store.destroyAllOperators();
        }
    }

    @Nested
    @DisplayName("DataOperator 功能集成测试 (使用 H2)")
    class DataOperatorIntegrationTests {

        private DataSource h2DataSource;
        private SQLiteDataOperator<TestDataEntity> operator;

        @BeforeEach
        void setUpIntegration() {
            h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqliteintegration" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
            operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
        }

        @Test
        @DisplayName("应该能够通过 DataOperator 执行 CRUD 操作")
        void shouldPerformCrudOperations() {
            // Create
            TestDataEntity entity = new TestDataEntity();
            entity.setId("integration-test-1");
            entity.setName("IntegrationTest");
            entity.setScore(100);
            operator.insert(entity);

            // Read
            TestDataEntity retrieved = operator.getById("integration-test-1");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getName()).isEqualTo("IntegrationTest");
            assertThat(retrieved.getScore()).isEqualTo(100);

            // Update
            operator.update("score", 200, "integration-test-1");
            TestDataEntity updated = operator.getById("integration-test-1");
            assertThat(updated.getScore()).isEqualTo(200);

            // Delete
            operator.delById("integration-test-1");
            TestDataEntity deleted = operator.getById("integration-test-1");
            assertThat(deleted).isNull();
        }

        @Test
        @DisplayName("应该能够通过 DataOperator 执行批量查询")
        void shouldPerformBulkQueries() {
            // Insert multiple entities
            for (int i = 1; i <= 5; i++) {
                TestDataEntity entity = new TestDataEntity();
                entity.setId("bulk-" + i);
                entity.setName("Entity" + i);
                entity.setScore(i * 10);
                operator.insert(entity);
            }

            // Act & Assert - getAll
            assertThat(operator.getAll()).hasSize(5);

            // Act & Assert - page
            assertThat(operator.page(0, 3)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("并发访问测试 (使用 H2)")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("多次调用应该返回相同的缓存实例")
        void multipleCallsShouldReturnSameCachedInstance() {
            // Arrange - 使用 H2 内存数据库
            DataSource h2DataSource = new SimpleDataSource("jdbc:h2:mem:sqliteconcurrenttest;DB_CLOSE_DELAY=-1", "sa", "");
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);

                // Act
                DataOperator<TestDataEntity> op1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> op2 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> op3 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(op1).isSameAs(op2).isSameAs(op3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
