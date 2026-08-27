package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.testutil.PoolStateAssertions;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.EqualsAndHashCode;

/**
 * SQLiteDataStore 完整测试类
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SQLiteDataStore 测试")
class SQLiteDataStoreTest {


    @TempDir
    File tempDir;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private UltiToolsPlugin mockPlugin;

    private MockedStatic<UltiTools> ultiToolsStaticMock;

    @EqualsAndHashCode(callSuper = true)
    @Table("test_data")
    public static class TestDataEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @EqualsAndHashCode(callSuper = true)
    @Table("another_data")
    public static class AnotherDataEntity extends BaseDataEntity<String> {
        @Column("value")
        private int value;

        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    @EqualsAndHashCode(callSuper = true)
    @Table("third_data")
    public static class ThirdDataEntity extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public static class NoTableAnnotationEntity extends BaseDataEntity<String> {}

    @BeforeEach
    void setUp() {
        ultiToolsStaticMock = mockStatic(UltiTools.class);
        ultiToolsStaticMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        
        // 清空静态缓存
        try {
            Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
            field.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
            map.clear();
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsStaticMock != null) {
            ultiToolsStaticMock.close();
        }
    }

    private DataSource createH2DataSource() {
        HikariConfig config = new HikariConfig();
        // Use H2 in MySQL compatibility mode for backtick support
        config.setJdbcUrl("jdbc:h2:mem:sqlitestoretest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // 刻意不调用 setPassword：内存库首次连接时就以「无口令」创建，
        // 传一个空字符串反而会被静态分析当成硬编码口令（Codacy/Opengrep 的
        // Semgrep_java_password_rule-HardcodePassword）。这里没有凭据可言。
        return new HikariDataSource(config);
    }

    @Nested
    @DisplayName("getOperator 测试")
    class GetOperatorTests {

        @Test
        @DisplayName("应该成功获取带 @Table 注解的实体的 DataOperator")
        void shouldGetOperatorForAnnotatedEntity() {
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true); // NOPMD
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);
                
                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
                assertThat(operator).isSameAs(h2Operator);
            } catch (Exception e) {
                throw new IllegalStateException(e);
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
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true); // NOPMD
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
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator1 = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                SQLiteDataOperator<AnotherDataEntity> h2Operator2 = new SQLiteDataOperator<>(h2DataSource, AnotherDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true); // NOPMD
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
    }

    @Nested
    @DisplayName("连接池状态测试 (Wave 0 gap 1, SILENT-03)")
    class PoolStateTests {

        /**
         * Pins the pool-per-file defect described in 02-VALIDATION.md's Wave 0 requirements: at HEAD,
         * {@code getOperator(UltiToolsPlugin, Class)} caches by entity class only, so each new entity
         * class for the same plugin (same backing .db file) triggers its own {@code new
         * HikariConfig()}/{@code new HikariDataSource(config)} pair. Both are intercepted with
         * {@code mockConstruction} so the SQLite JDBC driver is never touched -
         * {@code HikariConfig.setDriverClassName("org.sqlite.JDBC")} performs its own
         * {@code Class.forName} eagerly and throws on the real class, and that driver ships with
         * Paper, not with this project's test dependencies.
         * <p>
         * RED at HEAD (before Task 2's fix): 3 entity classes -&gt; 3 pool constructions. GREEN after
         * Task 2 re-keys the pool map by backing-file path with {@code computeIfAbsent}.
         */
        @Test
        @DisplayName("同一 .db 文件的多个实体类应该只创建一个连接池")
        void shouldShareOnePoolAcrossEntitiesInSameFile() {
            when(mockPlugin.getPluginName()).thenReturn("pool-state-plugin");
            SQLiteDataStore store = new SQLiteDataStore();

            // SQLiteDataOperator's constructor eagerly runs a CREATE TABLE against the DataSource it
            // is given; mocking its construction too keeps this test about pool *count*, not about
            // whether a mocked DataSource can serve a real connection.
            try (MockedConstruction<HikariConfig> mockedConfig = mockConstruction(HikariConfig.class);
                 MockedConstruction<HikariDataSource> mockedConstruction = mockConstruction(HikariDataSource.class);
                 MockedConstruction<SQLiteDataOperator> mockedOperator = mockConstruction(SQLiteDataOperator.class)) {
                store.getOperator(mockPlugin, TestDataEntity.class);
                store.getOperator(mockPlugin, AnotherDataEntity.class);
                store.getOperator(mockPlugin, ThirdDataEntity.class);

                assertThat(mockedConstruction.constructed())
                        .as("expected 1 pool for 3 entity classes backed by the same .db file, found %d",
                                mockedConstruction.constructed().size())
                        .hasSize(1);
            }
        }

        /**
         * Pins the empty-body {@code destroyAllOperators()} defect: at HEAD it does nothing, so pools
         * it is handed stay open. Seeds the pool map reflectively with real, H2-backed pools (not
         * SQLite - the driver is unavailable in tests) so this observes real
         * {@link com.zaxxer.hikari.HikariPoolMXBean} state via {@link PoolStateAssertions}, not merely
         * the absence of a thrown exception.
         * <p>
         * RED at HEAD (before Task 2's fix): pools remain open. GREEN after Task 2 makes teardown
         * actually close what it holds.
         */
        @Test
        @DisplayName("destroyAllOperators 应该关闭它持有的每一个连接池")
        void destroyAllOperatorsShouldCloseEveryPool() throws Exception {
            SQLiteDataStore store = new SQLiteDataStore();
            HikariDataSource poolA = (HikariDataSource) createH2DataSource();
            HikariDataSource poolB = (HikariDataSource) createH2DataSource();

            Field poolMapField = SQLiteDataStore.class.getDeclaredField("dataSourceMap");
            poolMapField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<String, DataSource> poolMap = (Map<String, DataSource>) poolMapField.get(store);
            poolMap.put("fileA", poolA);
            poolMap.put("fileB", poolB);

            try {
                store.destroyAllOperators();

                PoolStateAssertions.assertNoOpenConnections(Arrays.asList(poolA, poolB));
            } finally {
                if (!poolA.isClosed()) {
                    poolA.close();
                }
                if (!poolB.isClosed()) {
                    poolB.close();
                }
                poolMap.remove("fileA");
                poolMap.remove("fileB");
            }
        }
    }

    @Nested
    @DisplayName("getStoreType 测试")
    class GetStoreTypeTests {

        @Test
        @DisplayName("应该返回 sqlite")
        void shouldReturnSqlite() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act
            String storeType = store.getStoreType();

            // Assert
            assertThat(storeType).isEqualTo("sqlite");
        }
    }
}
