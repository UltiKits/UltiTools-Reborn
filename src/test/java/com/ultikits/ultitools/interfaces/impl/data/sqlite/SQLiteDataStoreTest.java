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
        // dataOperatorMap is instance-scoped since Task 2 (SILENT-03) - each test constructs its own
        // fresh SQLiteDataStore(), so there is no shared static cache left to clear here anymore.
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
            // Arrange - dataOperatorMap is instance-scoped and keyed by a private composite type
            // since Task 2 (SILENT-03), so this drives the real getOperator() path with
            // HikariConfig/HikariDataSource/SQLiteDataOperator construction mocked out (the SQLite
            // JDBC driver is not on the test classpath - see PoolStateTests for why).
            when(mockPlugin.getPluginName()).thenReturn("annotated-entity-plugin");
            SQLiteDataStore store = new SQLiteDataStore();

            try (MockedConstruction<HikariConfig> mockedConfig = mockConstruction(HikariConfig.class);
                 MockedConstruction<HikariDataSource> mockedDataSource = mockConstruction(HikariDataSource.class);
                 MockedConstruction<SQLiteDataOperator> mockedOperator = mockConstruction(SQLiteDataOperator.class)) {
                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
                assertThat(mockedOperator.constructed()).hasSize(1);
                assertThat(operator).isSameAs(mockedOperator.constructed().get(0));
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
            when(mockPlugin.getPluginName()).thenReturn("cached-operator-plugin");
            SQLiteDataStore store = new SQLiteDataStore();

            try (MockedConstruction<HikariConfig> mockedConfig = mockConstruction(HikariConfig.class);
                 MockedConstruction<HikariDataSource> mockedDataSource = mockConstruction(HikariDataSource.class);
                 MockedConstruction<SQLiteDataOperator> mockedOperator = mockConstruction(SQLiteDataOperator.class)) {
                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator1).isSameAs(operator2);
                assertThat(mockedOperator.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("应该为不同的实体类返回不同的 DataOperator")
        void shouldReturnDifferentOperatorsForDifferentEntities() {
            // Arrange
            when(mockPlugin.getPluginName()).thenReturn("different-entities-plugin");
            SQLiteDataStore store = new SQLiteDataStore();

            try (MockedConstruction<HikariConfig> mockedConfig = mockConstruction(HikariConfig.class);
                 MockedConstruction<HikariDataSource> mockedDataSource = mockConstruction(HikariDataSource.class);
                 MockedConstruction<SQLiteDataOperator> mockedOperator = mockConstruction(SQLiteDataOperator.class)) {
                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<AnotherDataEntity> operator2 = store.getOperator(mockPlugin, AnotherDataEntity.class);

                // Assert
                assertThat(operator1).isNotSameAs(operator2);
                assertThat(mockedOperator.constructed()).hasSize(2);
                // Both entity classes are backed by the same .db file (same plugin) -> one pool.
                assertThat(mockedDataSource.constructed()).hasSize(1);
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

        /**
         * Same defect as {@link #shouldShareOnePoolAcrossEntitiesInSameFile()}, but through a real,
         * live pool instead of a construction count -- pre-seeds a real H2-backed pool at the exact
         * canonical path {@code getOperator} will resolve to for this plugin, so {@code poolFor}'s
         * {@code computeIfAbsent} finds it already cached and never attempts a real
         * {@code jdbc:sqlite:} connection. This lets {@link SQLiteDataOperator} run its real {@code
         * CREATE TABLE} against the pool, and lets {@link PoolStateAssertions#assertTotalConnections}
         * assert against genuine {@link com.zaxxer.hikari.HikariPoolMXBean} state, per this plan's
         * acceptance criteria.
         */
        @Test
        @DisplayName("同一 .db 文件的多个实体类应该共享同一个连接池的真实连接")
        void shouldReportRealPoolConnectionsSharedAcrossEntities() throws Exception {
            when(mockPlugin.getPluginName()).thenReturn("real-pool-plugin");
            File dataFolder = new File(tempDir, "sqliteDB");
            dataFolder.mkdirs();
            String dbPath = new File(dataFolder, "real-pool-plugin.db").getCanonicalPath();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:realpooltest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            config.setUsername("sa");
            config.setMinimumIdle(0);
            config.setMaximumPoolSize(10);
            HikariDataSource realPool = new HikariDataSource(config);

            SQLiteDataStore store = new SQLiteDataStore();
            Field poolMapField = SQLiteDataStore.class.getDeclaredField("dataSourceMap");
            poolMapField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<String, DataSource> poolMap = (Map<String, DataSource>) poolMapField.get(store);
            poolMap.put(dbPath, realPool);

            try {
                // Each getOperator() call runs a CREATE TABLE that borrows a connection from the pool
                // and returns it; sequential (non-concurrent) borrow/return cycles against the same
                // pool settle on one physical connection, so this observes real
                // HikariPoolMXBean.getTotalConnections() state - not a construction count and not the
                // absence of a thrown exception.
                DataOperator<TestDataEntity> op1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<AnotherDataEntity> op2 = store.getOperator(mockPlugin, AnotherDataEntity.class);
                DataOperator<ThirdDataEntity> op3 = store.getOperator(mockPlugin, ThirdDataEntity.class);

                assertThat(op1).isNotNull();
                assertThat(op2).isNotNull();
                assertThat(op3).isNotNull();
                assertThat(poolMap).hasSize(1);
                PoolStateAssertions.assertTotalConnections(realPool, 1);
            } finally {
                poolMap.remove(dbPath);
                realPool.close();
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

    /**
     * getOperator(DataScope, Class) 所有权测试（D-14/D-17）。{@code SQLiteDataStore} 没有覆写
     * 这个新方法（不在本计划的 files_modified 范围内），所以这里验证的是它从 {@code DataStore}
     * 接口继承来的 default 方法本身在真实存储实现上依然生效——不是只在 stub 上生效。
     * <p>
     * {@link com.ultikits.ultitools.manager.DataScope#forExternal} 对 {@code manager} 包之外
     * 是包私有的（D-17 的不可伪造凭证设计），因此这里通过反射构造 scope，与
     * {@code PluginManagerClassScanningTest} 里访问私有方法的既有做法一致。
     */
    @Nested
    @DisplayName("getOperator(DataScope, Class) 所有权测试 (D-14, 继承自 DataStore 的 default 方法)")
    class GetOperatorDataScopeTests {

        private com.ultikits.ultitools.manager.DataScope buildScope(
                String pluginName, java.util.Set<Class<?>> ownedEntities
        ) throws ReflectiveOperationException {
            Class<?> dataScopeClass = com.ultikits.ultitools.manager.DataScope.class;
            java.lang.reflect.Method factory = dataScopeClass.getDeclaredMethod(
                    "forExternal", String.class, File.class, java.util.Set.class);
            factory.setAccessible(true);
            return (com.ultikits.ultitools.manager.DataScope) factory.invoke(null, pluginName, tempDir, ownedEntities);
        }

        @Test
        @DisplayName("继承的 default 方法应该拒绝未拥有的实体")
        void shouldRefuseUnownedEntity() throws ReflectiveOperationException {
            SQLiteDataStore store = new SQLiteDataStore();
            com.ultikits.ultitools.manager.DataScope scope =
                    buildScope("Requester", java.util.Collections.emptySet());

            assertThatThrownBy(() -> store.getOperator(scope, TestDataEntity.class))
                    .isInstanceOf(com.ultikits.ultitools.exceptions.DataAccessException.class)
                    .extracting(e -> ((com.ultikits.ultitools.exceptions.DataAccessException) e).getErrorCode())
                    .isEqualTo(com.ultikits.ultitools.exceptions.ErrorCode.ENTITY_NOT_OWNED);
        }

        @Test
        @DisplayName("继承的 default 方法应该为拥有的实体返回一个真实可用的操作器")
        void shouldReturnWorkingOperatorForOwnedEntity() throws ReflectiveOperationException {
            SQLiteDataStore store = new SQLiteDataStore();
            com.ultikits.ultitools.manager.DataScope scope =
                    buildScope("Owner", java.util.Collections.singleton(TestDataEntity.class));

            try (MockedConstruction<HikariConfig> mockedConfig = mockConstruction(HikariConfig.class);
                 MockedConstruction<HikariDataSource> mockedDataSource = mockConstruction(HikariDataSource.class);
                 MockedConstruction<SQLiteDataOperator> mockedOperator = mockConstruction(SQLiteDataOperator.class)) {
                DataOperator<TestDataEntity> operator = store.getOperator(scope, TestDataEntity.class);

                assertThat(operator).isNotNull();
                assertThat(mockedOperator.constructed()).hasSize(1);
                assertThat(operator).isSameAs(mockedOperator.constructed().get(0));
            }
        }
    }
}
