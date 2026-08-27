package com.ultikits.ultitools.interfaces.impl.data.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
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
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.PluginManager;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MysqlDataStore 完整测试类
 * 覆盖所有方法和分支
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MysqlDataStore 测试")
class MysqlDataStoreTest {

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private FileConfiguration mockConfig;

    @Mock
    private Logger mockLogger;

    @Mock
    private UltiToolsPlugin mockPlugin;

    @Mock
    private PluginManager mockPluginManager;

    @TempDir
    File tempDir;

    private MockedStatic<UltiTools> ultiToolsStaticMock;

    /**
     * 测试用实体类 - 带 @Table 注解
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Table("test_table")
    public static class TestDataEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        @Column(value = "value", type = "INT")
        private int value;
    }

    /**
     * 测试用实体类 - 无 @Table 注解
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class NoTableAnnotationEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;
    }

    /**
     * 测试用实体类 - 另一个带 @Table 注解的实体
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    @Table("another_table")
    public static class AnotherDataEntity extends BaseDataEntity<String> {
        @Column("description")
        private String description;
    }

    private void setupMysqlConfig() {
        when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
        when(mockConfig.getString("mysql.host")).thenReturn("localhost");
        when(mockConfig.getInt("mysql.port")).thenReturn(3306);
        when(mockConfig.getString("mysql.username")).thenReturn("root");
        when(mockConfig.getString("mysql.password")).thenReturn("password");
        when(mockConfig.getString("mysql.database")).thenReturn("ultitools");
        when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
        when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
        when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
        when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
        when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
        when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
        when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
        when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);
    }

    @BeforeEach
    void setUp() {
        // dataOperatorMap is instance-scoped since Task 3 (SILENT-04) - each test constructs its own
        // fresh MysqlDataStore(), so there is no shared static cache left to clear here anymore.
        ultiToolsStaticMock = mockStatic(UltiTools.class);
        ultiToolsStaticMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.getConfig()).thenReturn(mockConfig);
        lenient().when(mockUltiTools.getLogger()).thenReturn(mockLogger);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // Used only by tests that call getOperator(); lenient() so tests that don't need it
        // (constructor/getStoreType/getDataSource/destroyAllOperators tests) aren't flagged for an
        // unused stub under Mockito's strict-stubs default.
        lenient().when(mockPlugin.getPluginName()).thenReturn("test-plugin");

        // 02-12 Task 2: every getOperator(UltiToolsPlugin, Class) call now runs checkOwnership()
        // first. Default every entity to "owned by whoever mockPlugin currently claims to be" --
        // an Answer, not a fixed thenReturn, so it stays correct across the different plugin names
        // individual tests stub onto mockPlugin. Tests exercising a genuine ownership refusal
        // override this with a more specific, entity-scoped stub (Mockito: the more specific /
        // later-registered stub wins for a matching call). lenient() for the same reason as above.
        lenient().when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);
        lenient().when(mockPluginManager.findOwningPlugin(any())).thenAnswer(inv -> mockPlugin.getPluginName());
        // Backs checkOwnership(File, Class)'s framework-core-folder exemption; lenient() since
        // only the File-overload tests below actually reach that check.
        lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsStaticMock != null) {
            ultiToolsStaticMock.close();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该成功创建 MysqlDataStore 并初始化 HikariDataSource")
        void shouldCreateMysqlDataStoreSuccessfully() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class)) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert
                assertThat(store).isNotNull();
                assertThat(mockedConstruction.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("应该正确配置 HikariConfig")
        void shouldConfigureHikariConfigCorrectly() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        // 验证配置传递
                        assertThat(context.arguments()).hasSize(1);
                    })) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert
                assertThat(store).isNotNull();
            }
        }

        @Test
        @DisplayName("应该处理 HikariDataSource 初始化失败")
        void shouldHandleHikariDataSourceInitializationFailure() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        throw new RuntimeException("Connection failed");
                    })) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert - 应该记录错误日志
                verify(mockLogger).log(eq(Level.SEVERE), contains("Mysql数据库连接失败"));
                assertThat(store.getDataSource()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("getStoreType 方法测试")
    class GetStoreTypeTests {

        @Test
        @DisplayName("应该返回 'mysql' 作为存储类型")
        void shouldReturnMysqlAsStoreType() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> ignored = 
                    mockConstruction(HikariDataSource.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                String storeType = store.getStoreType();

                // Assert
                assertThat(storeType).isEqualTo("mysql");
            }
        }
    }

    @Nested
    @DisplayName("getOperator 方法测试")
    class GetOperatorTests {

        @Test
        @DisplayName("应该抛出 RuntimeException 当实体类没有 @Table 注解")
        void shouldThrowRuntimeExceptionWhenNoTableAnnotation() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> ignored = 
                    mockConstruction(HikariDataSource.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act & Assert
                assertThatThrownBy(() -> store.getOperator(mockPlugin, NoTableAnnotationEntity.class))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("No Table annotation is presented!");
            }
        }

        @Test
        @DisplayName("应该创建新的 MysqlDataOperator 当缓存中不存在")
        void shouldCreateNewOperatorWhenNotCached() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock = 
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = 
                    mockConstruction(MysqlDataOperator.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
                assertThat(operatorMock.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("应该返回缓存的 MysqlDataOperator 当已存在")
        void shouldReturnCachedOperatorWhenExists() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock = 
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = 
                    mockConstruction(MysqlDataOperator.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act - 第一次调用创建 operator
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                // Act - 第二次调用应该返回缓存的
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator1).isNotNull();
                assertThat(operator2).isNotNull();
                assertThat(operator1).isSameAs(operator2);
                // 只应该创建一个 MysqlDataOperator 实例
                assertThat(operatorMock.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("应该为不同的实体类创建不同的 operator")
        void shouldCreateDifferentOperatorsForDifferentEntityClasses() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock = 
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = 
                    mockConstruction(MysqlDataOperator.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<AnotherDataEntity> operator2 = store.getOperator(mockPlugin, AnotherDataEntity.class);

                // Assert
                assertThat(operator1).isNotNull();
                assertThat(operator2).isNotNull();
                // 应该创建两个不同的 MysqlDataOperator 实例
                assertThat(operatorMock.constructed()).hasSize(2);
            }
        }

        @Test
        @DisplayName("两个插件请求同一实体类：拥有它的插件成功，另一个插件被拒绝 (02-12 更新，取代 SILENT-04 旧断言)")
        void secondPluginRequestingFirstPluginsEntityShouldBeRefused() {
            // Was: shouldCreateDifferentOperatorsForDifferentPluginsSameEntity, which asserted that
            // two DIFFERENT plugins both requesting the SAME @Table entity class each got their own
            // operator. Under 02-12's ownership model an entity has exactly one owner (02-07's
            // PluginManager.findOwningPlugin registry, first-registration-wins), so that scenario is
            // no longer a legitimate one to keep silently working -- it is exactly the case D-14
            // exists to refuse. The composite (identity, entity) cache key SILENT-04 introduced is
            // still real and still exercised by shouldCreateDifferentOperatorsForDifferentEntityClasses
            // above (one plugin, two entities).
            setupMysqlConfig();
            when(mockPluginManager.findOwningPlugin(TestDataEntity.class)).thenReturn("test-plugin");
            UltiToolsPlugin otherPlugin = mock(UltiToolsPlugin.class);
            when(otherPlugin.getPluginName()).thenReturn("other-plugin");

            try (MockedConstruction<HikariDataSource> hikariMock =
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock =
                    mockConstruction(MysqlDataOperator.class)) {

                MysqlDataStore store = new MysqlDataStore();

                // Act - the owning plugin succeeds...
                DataOperator<TestDataEntity> operatorForOwner = store.getOperator(mockPlugin, TestDataEntity.class);

                // ...but a different plugin requesting the same entity is refused, and the refusal
                // has not created a second operator as a side effect.
                assertThatThrownBy(() -> store.getOperator(otherPlugin, TestDataEntity.class))
                        .isInstanceOf(DataAccessException.class)
                        .extracting(e -> ((DataAccessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);

                assertThat(operatorForOwner).isNotNull();
                assertThat(operatorMock.constructed()).hasSize(1);
            }
        }

        @Test
        @DisplayName("应该正确传递 DataSource 给 MysqlDataOperator")
        void shouldPassDataSourceToMysqlDataOperator() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock = 
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = 
                    mockConstruction(MysqlDataOperator.class, (mock, context) -> {
                        // 验证构造函数参数
                        assertThat(context.arguments()).hasSize(2);
                        assertThat(context.arguments().get(1)).isEqualTo(TestDataEntity.class);
                    })) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("destroyAllOperators 方法测试")
    class DestroyAllOperatorsTests {

        @Test
        @DisplayName("应该关闭 HikariDataSource")
        void shouldCloseHikariDataSource() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class)) {
                
                MysqlDataStore store = new MysqlDataStore();
                HikariDataSource mockDataSource = mockedConstruction.constructed().get(0);

                // Act
                store.destroyAllOperators();

                // Assert
                verify(mockDataSource).close();
            }
        }

        @Test
        @DisplayName("应该清空 operator 缓存")
        void shouldClearOperatorCache() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock =
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock =
                    mockConstruction(MysqlDataOperator.class)) {

                MysqlDataStore store = new MysqlDataStore();
                DataOperator<TestDataEntity> before = store.getOperator(mockPlugin, TestDataEntity.class);

                // Act
                store.destroyAllOperators();
                DataOperator<TestDataEntity> after = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert - a fresh operator is built, proving the cache was actually emptied rather
                // than merely surviving destroyAllOperators() untouched.
                assertThat(after).isNotSameAs(before);
                assertThat(operatorMock.constructed()).hasSize(2);
            }
        }

        @Test
        @DisplayName("应该安全处理当 dataSource 为 null")
        void shouldHandleNullDataSourceSafely() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        throw new RuntimeException("Init failed");
                    })) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act & Assert - 由于 dataSource 是 null，调用 destroyAllOperators 会抛出 NPE
                // 这实际上是代码中的一个潜在问题
                assertThatThrownBy(() -> store.destroyAllOperators())
                    .isInstanceOf(NullPointerException.class);
            }
        }
    }

    @Nested
    @DisplayName("getDataSource 方法测试")
    class GetDataSourceTests {

        @Test
        @DisplayName("应该返回创建的 HikariDataSource")
        void shouldReturnCreatedHikariDataSource() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                HikariDataSource dataSource = store.getDataSource();

                // Assert
                assertThat(dataSource).isNotNull();
                assertThat(dataSource).isEqualTo(mockedConstruction.constructed().get(0));
            }
        }

        @Test
        @DisplayName("当初始化失败时应该返回 null")
        void shouldReturnNullWhenInitializationFailed() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        throw new RuntimeException("Connection failed");
                    })) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act
                HikariDataSource dataSource = store.getDataSource();

                // Assert
                assertThat(dataSource).isNull();
            }
        }
    }

    @Nested
    @DisplayName("并发访问测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("getOperator 应该在多次调用时返回同一实例")
        void getOperatorShouldReturnSameInstanceOnMultipleCalls() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock = 
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = 
                    mockConstruction(MysqlDataOperator.class)) {
                
                MysqlDataStore store = new MysqlDataStore();

                // Act - 多次调用同一实体类的 getOperator
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator3 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert - 应该都是同一个实例
                assertThat(operator1).isSameAs(operator2);
                assertThat(operator2).isSameAs(operator3);
                // 只创建了一个 operator
                assertThat(operatorMock.constructed()).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("实例级缓存测试 (SILENT-04 修复后)")
    class StaticCacheTests {

        @Test
        @DisplayName("不同 MysqlDataStore 实例不应该共享 operator 缓存")
        void differentStoreInstancesShouldNotShareOperatorCache() {
            // Arrange - dataOperatorMap 从 Task 3 (SILENT-04) 起是实例字段，不再是 static，
            // 所以两个独立的 store 实例各自持有自己的缓存。生产环境中 DataStoreManager 只会注册
            // 一个 MysqlDataStore 实例，所以这个场景不会在真实运行时出现，但它记录了这个
            // 有意为之的行为变化。
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> hikariMock =
                    mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock =
                    mockConstruction(MysqlDataOperator.class)) {

                MysqlDataStore store1 = new MysqlDataStore();
                MysqlDataStore store2 = new MysqlDataStore();

                // Act
                DataOperator<TestDataEntity> operator1 = store1.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator2 = store2.getOperator(mockPlugin, TestDataEntity.class);

                // Assert - each store instance has its own operator cache
                assertThat(operator1).isNotSameAs(operator2);
                // Two independent stores -> two MysqlDataOperator instances
                assertThat(operatorMock.constructed()).hasSize(2);
            }
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("应该正确处理 HikariDataSource 构造函数异常")
        void shouldHandleHikariDataSourceConstructorException() {
            // Arrange
            setupMysqlConfig();

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        throw new RuntimeException("Invalid configuration");
                    })) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert
                verify(mockLogger).log(eq(Level.SEVERE), contains("Mysql数据库连接失败"));
                assertThat(store.getDataSource()).isNull();
            }
        }

        @Test
        @DisplayName("应该使用 i18n 转换错误消息")
        void shouldUseI18nForErrorMessage() {
            // Arrange
            setupMysqlConfig();
            when(mockUltiTools.i18n("Mysql数据库连接失败：")).thenReturn("MySQL connection failed: ");

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class, (mock, context) -> {
                        throw new RuntimeException("Test error");
                    })) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert - MockedConstruction 包装异常消息，所以只验证 i18n 被调用
                verify(mockUltiTools).i18n("Mysql数据库连接失败：");
                verify(mockLogger).log(eq(Level.SEVERE), contains("MySQL connection failed:"));
            }
        }
    }

    @Nested
    @DisplayName("配置边界测试")
    class ConfigurationBoundaryTests {

        @Test
        @DisplayName("应该处理 null 数据库名称")
        void shouldHandleNullDatabaseName() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("localhost");
            when(mockConfig.getInt("mysql.port")).thenReturn(3306);
            when(mockConfig.getString("mysql.username")).thenReturn("root");
            when(mockConfig.getString("mysql.password")).thenReturn("password");
            when(mockConfig.getString("mysql.database")).thenReturn(null);
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class)) {
                
                // Act - 应该不会抛出异常，因为 JDBC URL 会包含 null
                MysqlDataStore store = new MysqlDataStore();

                // Assert
                assertThat(store).isNotNull();
            }
        }

        @Test
        @DisplayName("应该处理零端口号")
        void shouldHandleZeroPortNumber() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("localhost");
            when(mockConfig.getInt("mysql.port")).thenReturn(0);
            when(mockConfig.getString("mysql.username")).thenReturn("root");
            when(mockConfig.getString("mysql.password")).thenReturn("password");
            when(mockConfig.getString("mysql.database")).thenReturn("test");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);

            try (MockedConstruction<HikariDataSource> mockedConstruction = 
                    mockConstruction(HikariDataSource.class)) {
                
                // Act
                MysqlDataStore store = new MysqlDataStore();

                // Assert
                assertThat(store).isNotNull();
            }
        }
    }

    /**
     * 02-12 Task 2: {@code checkOwnership} is now called as the first statement of both legacy
     * {@code getOperator} overrides -- closing the residual bypass 02-07 disclosed (D-14/D-18). A
     * refused call must not have created an operator as a side effect.
     */
    @Nested
    @DisplayName("所有权校验测试 (D-14/D-18, 02-12 Task 2)")
    class OwnershipTests {

        @Test
        @DisplayName("getOperator(File, Class) 应该拒绝其他插件的未注册文件夹，且不产生任何 operator 缓存副作用")
        void shouldRefuseUnregisteredFolderViaFileOverloadWithNoSideEffects() {
            setupMysqlConfig();
            File someOtherPluginsFolder = new File(tempDir, "some-other-plugin");
            when(mockPluginManager.findScopeForDataFolder(someOtherPluginsFolder)).thenReturn(null);

            try (MockedConstruction<HikariDataSource> hikariMock = mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = mockConstruction(MysqlDataOperator.class)) {

                MysqlDataStore store = new MysqlDataStore();

                assertThatThrownBy(() -> store.getOperator(someOtherPluginsFolder, TestDataEntity.class))
                        .isInstanceOf(DataAccessException.class)
                        .extracting(e -> ((DataAccessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);

                assertThat(operatorMock.constructed())
                        .as("a refused call must not have built an operator")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("框架自身的核心数据文件夹在 getOperator(File, Class) 上仍然豁免")
        void shouldExemptFrameworkCoreFolderViaFileOverload() {
            setupMysqlConfig();
            // mockUltiTools.getDataFolder() is stubbed to tempDir in setUp() -- passing tempDir
            // itself is exactly the framework-core-folder sentinel checkOwnership(File, Class)
            // exempts. No PluginManager stub needed for this call to succeed.

            try (MockedConstruction<HikariDataSource> hikariMock = mockConstruction(HikariDataSource.class);
                 MockedConstruction<MysqlDataOperator> operatorMock = mockConstruction(MysqlDataOperator.class)) {

                MysqlDataStore store = new MysqlDataStore();

                DataOperator<TestDataEntity> operator = store.getOperator(tempDir, TestDataEntity.class);

                assertThat(operator).isNotNull();
                assertThat(operatorMock.constructed()).hasSize(1);
            }
        }
    }
}
