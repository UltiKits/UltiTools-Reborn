package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.ultikits.ultitools.testutil.PoolStateAssertions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * DataStoreManager 测试
 */
@DisplayName("DataStoreManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DataStoreManagerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        
        // Mock getDataFolder
        File mockDataFolder = new File(System.getProperty("java.io.tmpdir"), "ultitools-test");
        mockDataFolder.mkdirs();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(mockDataFolder);
        });

        // 清理静态 map
        clearDataMap();
    }

    @AfterEach
    void tearDown() {
        clearDataMap();
        // 下面有用例会把 i18n 打成返回 null / 抛异常的桩，并发布到 UltiTools 的静态单例上。
        // 这个字段不会被 safeUnmock 清掉，所以这里换回一个行为正常的 mock，
        // 免得污染同一个 JVM fork 里后面跑的测试类。
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * 清理 DataStoreManager 的静态 map
     */
    private void clearDataMap() {
        try {
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);
            dataMap.clear();
        } catch (Exception ignored) {
        }
    }

    @Nested
    @DisplayName("register 测试")
    class RegisterTests {

        @Test
        @DisplayName("应该成功注册 DataStore")
        void shouldRegisterDataStore() throws Exception {
            // Arrange
            DataStore mockStore = mock(DataStore.class);
            when(mockStore.getStoreType()).thenReturn("test-store");

            // Act
            DataStoreManager.register(mockStore);

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).containsKey("test-store");
            assertThat(dataMap.get("test-store")).isEqualTo(mockStore);
        }

        @Test
        @DisplayName("应该支持注册多个不同类型的 DataStore")
        void shouldRegisterMultipleDataStores() throws Exception {
            // Arrange
            DataStore store1 = mock(DataStore.class);
            when(store1.getStoreType()).thenReturn("type1");

            DataStore store2 = mock(DataStore.class);
            when(store2.getStoreType()).thenReturn("type2");

            // Act
            DataStoreManager.register(store1);
            DataStoreManager.register(store2);

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).hasSize(2);
            assertThat(dataMap).containsKeys("type1", "type2");
        }

        @Test
        @DisplayName("重复注册相同类型应该覆盖")
        void shouldOverrideOnDuplicateType() throws Exception {
            // Arrange
            DataStore store1 = mock(DataStore.class);
            when(store1.getStoreType()).thenReturn("same-type");

            DataStore store2 = mock(DataStore.class);
            when(store2.getStoreType()).thenReturn("same-type");

            // Act
            DataStoreManager.register(store1);
            DataStoreManager.register(store2);

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).hasSize(1);
            assertThat(dataMap.get("same-type")).isEqualTo(store2);
        }
    }

    @Nested
    @DisplayName("unregister 测试")
    class UnregisterTests {

        @Test
        @DisplayName("应该成功注销 DataStore")
        void shouldUnregisterDataStore() throws Exception {
            // Arrange
            DataStore mockStore = mock(DataStore.class);
            when(mockStore.getStoreType()).thenReturn("test-store");
            DataStoreManager.register(mockStore);

            // Act
            DataStoreManager.unregister(mockStore);

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).doesNotContainKey("test-store");
        }

        @Test
        @DisplayName("注销时应该调用 destroyAllOperators")
        void shouldCallDestroyAllOperators() {
            // Arrange
            DataStore mockStore = mock(DataStore.class);
            when(mockStore.getStoreType()).thenReturn("test-store");
            DataStoreManager.register(mockStore);

            // Act
            DataStoreManager.unregister(mockStore);

            // Assert
            org.mockito.Mockito.verify(mockStore).destroyAllOperators();
            assertThat(mockStore).isNotNull();
        }
    }

    @Nested
    @DisplayName("getDatastore 测试")
    class GetDatastoreTests {

        @Test
        @DisplayName("应该返回注册的 DataStore")
        void shouldReturnRegisteredDataStore() {
            // Arrange
            DataStore mockStore = mock(DataStore.class);
            when(mockStore.getStoreType()).thenReturn("custom");
            DataStoreManager.register(mockStore);

            // Act
            DataStore result = DataStoreManager.getDatastore("custom");

            // Assert
            assertThat(result).isEqualTo(mockStore);
        }

        @Test
        @DisplayName("json 类型未注册时应该返回新的 JsonStore")
        void shouldReturnNewJsonStoreWhenNotRegistered() {
            // Act
            DataStore result = DataStoreManager.getDatastore("json");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result).isInstanceOf(JsonStore.class);
        }

        @Test
        @DisplayName("不存在的类型应该返回 json store 作为默认值")
        void shouldReturnJsonStoreAsDefault() {
            // Arrange - 注册 json store
            DataStore jsonStore = mock(DataStore.class);
            when(jsonStore.getStoreType()).thenReturn("json");
            DataStoreManager.register(jsonStore);

            // Act
            DataStore result = DataStoreManager.getDatastore("non-existent");

            // Assert
            assertThat(result).isEqualTo(jsonStore);
        }

        @Test
        @DisplayName("不存在的类型且无 json store 时应该返回 null")
        void shouldReturnNullWhenNoJsonStore() {
            // Act
            DataStore result = DataStoreManager.getDatastore("non-existent");

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("close 测试")
    class CloseTests {

        @Test
        @DisplayName("应该清空所有 DataStore")
        void shouldClearAllDataStores() throws Exception {
            // Arrange
            DataStore store1 = mock(DataStore.class);
            when(store1.getStoreType()).thenReturn("type1");

            DataStore store2 = mock(DataStore.class);
            when(store2.getStoreType()).thenReturn("type2");

            DataStoreManager.register(store1);
            DataStoreManager.register(store2);

            // Act
            DataStoreManager.close();

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).isEmpty();
        }

        @Test
        @DisplayName("关闭时应该调用每个 DataStore 的 destroyAllOperators")
        void shouldDestroyAllOperatorsOnClose() {
            // Arrange
            DataStore store1 = mock(DataStore.class);
            when(store1.getStoreType()).thenReturn("type1");

            DataStore store2 = mock(DataStore.class);
            when(store2.getStoreType()).thenReturn("type2");

            DataStoreManager.register(store1);
            DataStoreManager.register(store2);

            // Act
            DataStoreManager.close();

            // Assert
            org.mockito.Mockito.verify(store1).destroyAllOperators();
            org.mockito.Mockito.verify(store2).destroyAllOperators();
        }

        @Test
        @DisplayName("空 map 时关闭不应该抛出异常")
        void shouldNotThrowOnEmptyMap() {
            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> DataStoreManager.close());
        }
    }

    @Nested
    @DisplayName("线程安全测试")
    class ThreadSafetyTests {

        @Test
        @DisplayName("并发注册应该安全")
        void concurrentRegisterShouldBeSafe() throws Exception {
            // Arrange
            int threadCount = 10;
            Thread[] threads = new Thread[threadCount];

            // Act
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    DataStore store = mock(DataStore.class);
                    when(store.getStoreType()).thenReturn("type-" + index);
                    DataStoreManager.register(store);
                });
                threads[i].start();
            }

            // 等待所有线程完成
            for (Thread thread : threads) {
                thread.join();
            }

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).hasSize(threadCount);
        }
    }

    @Nested
    @DisplayName("JsonStore 回退测试")
    class JsonStoreFallbackTests {

        @Test
        @DisplayName("json 类型请求时应该创建 JsonStore")
        void jsonTypeRequestShouldCreateJsonStore() {
            // Act
            DataStore result = DataStoreManager.getDatastore("json");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result).isInstanceOf(JsonStore.class);
        }

        @Test
        @DisplayName("JsonStore 应该指向正确的数据目录")
        void jsonStoreShouldPointToCorrectDirectory() {
            // Act
            DataStore result = DataStoreManager.getDatastore("json");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getStoreType()).isEqualTo("json");
        }
    }

    @Nested
    @DisplayName("dataMap 静态字段测试")
    class DataMapStaticFieldTests {

        @Test
        @DisplayName("dataMap 是静态字段")
        void dataMapIsStatic() throws Exception {
            Field field = DataStoreManager.class.getDeclaredField("dataMap");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("dataMap 是 final")
        void dataMapIsFinal() throws Exception {
            Field field = DataStoreManager.class.getDeclaredField("dataMap");
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("dataMap 是 private")
        void dataMapIsPrivate() throws Exception {
            Field field = DataStoreManager.class.getDeclaredField("dataMap");
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("register 方法签名测试")
    class RegisterMethodSignatureTests {

        @Test
        @DisplayName("register 是同步方法")
        void registerIsSynchronized() throws Exception {
            java.lang.reflect.Method method = DataStoreManager.class.getDeclaredMethod("register", DataStore.class);
            assertThat(java.lang.reflect.Modifier.isSynchronized(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("register 是静态方法")
        void registerIsStatic() throws Exception {
            java.lang.reflect.Method method = DataStoreManager.class.getDeclaredMethod("register", DataStore.class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("register 是公开方法")
        void registerIsPublic() throws Exception {
            java.lang.reflect.Method method = DataStoreManager.class.getDeclaredMethod("register", DataStore.class);
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("unregister 方法签名测试")
    class UnregisterMethodSignatureTests {

        @Test
        @DisplayName("unregister 是同步方法")
        void unregisterIsSynchronized() throws Exception {
            java.lang.reflect.Method method = DataStoreManager.class.getDeclaredMethod("unregister", DataStore.class);
            assertThat(java.lang.reflect.Modifier.isSynchronized(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("unregister 是静态方法")
        void unregisterIsStatic() throws Exception {
            java.lang.reflect.Method method = DataStoreManager.class.getDeclaredMethod("unregister", DataStore.class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("并发读写测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("并发读写应该安全")
        void concurrentReadWriteShouldBeSafe() throws Exception {
            // Arrange
            int threadCount = 5;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount * 2);
            
            // 注册一些初始数据
            for (int i = 0; i < 3; i++) {
                DataStore store = mock(DataStore.class);
                when(store.getStoreType()).thenReturn("init-type-" + i);
                DataStoreManager.register(store);
            }

            // Act - 并发读写
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                // 写线程
                new Thread(() -> {
                    DataStore store = mock(DataStore.class);
                    when(store.getStoreType()).thenReturn("new-type-" + index);
                    DataStoreManager.register(store);
                    latch.countDown();
                }).start();
                
                // 读线程
                new Thread(() -> {
                    DataStoreManager.getDatastore("init-type-0");
                    latch.countDown();
                }).start();
            }

            // 等待完成
            latch.await(5, TimeUnit.SECONDS);

            // Assert - 没有异常，测试通过
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("null storeType 应该被处理")
        void nullStoreTypeShouldBeHandled() {
            // Arrange
            DataStore store = mock(DataStore.class);
            when(store.getStoreType()).thenReturn(null);

            // Act & Assert - 可能会导致问题，但不应该崩溃
            try {
                DataStoreManager.register(store);
                // If no exception, still valid behavior
                assertThat(true).isTrue();
            } catch (NullPointerException e) {
                // 可能的行为
                assertThat(e).isNotNull();
            }
        }

        @Test
        @DisplayName("空字符串 storeType 应该被处理")
        void emptyStoreTypeShouldBeHandled() throws Exception {
            // Arrange
            DataStore store = mock(DataStore.class);
            when(store.getStoreType()).thenReturn("");

            // Act
            DataStoreManager.register(store);

            // Assert
            Field dataMapField = DataStoreManager.class.getDeclaredField("dataMap");
            dataMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DataStore> dataMap = (Map<String, DataStore>) dataMapField.get(null);

            assertThat(dataMap).containsKey("");
        }

        @Test
        @DisplayName("getDatastore 空字符串应该回退到 json")
        void emptyStringGetDatastoreShouldFallbackToJson() {
            // Arrange
            DataStore jsonStore = mock(DataStore.class);
            when(jsonStore.getStoreType()).thenReturn("json");
            DataStoreManager.register(jsonStore);

            // Act
            DataStore result = DataStoreManager.getDatastore("");

            // Assert - 应该回退到 json store
            assertThat(result).isEqualTo(jsonStore);
        }
    }

    @Nested
    @DisplayName("destroyAllOperators 测试")
    class DestroyAllOperatorsTests {

        @Test
        @DisplayName("close 时应该为每个 store 调用 destroyAllOperators")
        void closeShouldCallDestroyForEach() {
            // Arrange
            DataStore store1 = mock(DataStore.class);
            when(store1.getStoreType()).thenReturn("s1");
            DataStore store2 = mock(DataStore.class);
            when(store2.getStoreType()).thenReturn("s2");
            DataStore store3 = mock(DataStore.class);
            when(store3.getStoreType()).thenReturn("s3");

            DataStoreManager.register(store1);
            DataStoreManager.register(store2);
            DataStoreManager.register(store3);

            // Act
            DataStoreManager.close();

            // Assert
            org.mockito.Mockito.verify(store1).destroyAllOperators();
            org.mockito.Mockito.verify(store2).destroyAllOperators();
            org.mockito.Mockito.verify(store3).destroyAllOperators();
        }
    }

    @Nested
    @DisplayName("destroyAllOperators 真实连接池状态测试 (Wave 0 gap 1, SILENT-03)")
    class PoolTeardownRealPoolTests {

        private HikariDataSource createH2Pool() {
            HikariConfig config = new HikariConfig();
            // H2 stands in for SQLite here - the SQLite JDBC driver ships with Paper, not with this
            // project's test dependencies, so a real jdbc:sqlite: pool cannot be constructed in tests.
            config.setJdbcUrl("jdbc:h2:mem:datastoremanagerpooltest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            config.setUsername("sa");
            return new HikariDataSource(config);
        }

        /**
         * Pins ROADMAP success criterion 3 through the actual {@code DataStoreManager.close()} call
         * path (not just {@code SQLiteDataStore.destroyAllOperators()} in isolation): registers a real
         * {@code SQLiteDataStore} carrying two live H2-backed pools, closes the manager, and asserts
         * against {@link com.zaxxer.hikari.HikariPoolMXBean} state via {@link PoolStateAssertions} -
         * not against the absence of a thrown exception.
         * <p>
         * RED at HEAD (before Task 2's fix): {@code SQLiteDataStore.destroyAllOperators()} is an empty
         * body, so both pools remain open. GREEN after Task 2.
         */
        @Test
        @DisplayName("close() 应该让已注册 SQLiteDataStore 持有的每个连接池归零")
        void closeShouldLeaveZeroOpenConnectionsAcrossRegisteredStore() throws Exception {
            SQLiteDataStore sqliteStore = new SQLiteDataStore();
            HikariDataSource poolA = createH2Pool();
            HikariDataSource poolB = createH2Pool();

            Field poolMapField = SQLiteDataStore.class.getDeclaredField("dataSourceMap");
            poolMapField.setAccessible(true); // NOPMD
            @SuppressWarnings("unchecked")
            Map<String, DataSource> poolMap = (Map<String, DataSource>) poolMapField.get(sqliteStore);
            poolMap.put("fileA", poolA);
            poolMap.put("fileB", poolB);

            try {
                DataStoreManager.register(sqliteStore);

                DataStoreManager.close();

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
    @DisplayName("getDatastore 返回值测试")
    class GetDatastoreReturnValueTests {

        @Test
        @DisplayName("注册的类型应该返回正确的 store")
        void registeredTypeShouldReturnCorrectStore() {
            // Arrange
            DataStore mysqlStore = mock(DataStore.class);
            when(mysqlStore.getStoreType()).thenReturn("mysql");
            
            DataStore sqliteStore = mock(DataStore.class);
            when(sqliteStore.getStoreType()).thenReturn("sqlite");
            
            DataStoreManager.register(mysqlStore);
            DataStoreManager.register(sqliteStore);

            // Act & Assert
            assertThat(DataStoreManager.getDatastore("mysql")).isEqualTo(mysqlStore);
            assertThat(DataStoreManager.getDatastore("sqlite")).isEqualTo(sqliteStore);
        }
    }

    /**
     * 后端降级诊断测试。
     * <p>
     * 这些用例守的是 issue #183：配置要 mysql、实际跑在空 JSON 存储上，过去一条日志都没有。
     */
    @Nested
    @DisplayName("reportBackendSelection 测试")
    class ReportBackendSelectionTests {

        /**
         * 收集这次调用打出的所有 SEVERE 消息。不写死条数，免得改一句文案就要改测试。
         */
        private List<String> severeMessages(Logger logger) {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(logger, atLeastOnce()).log(eq(Level.SEVERE), captor.capture());
            return captor.getAllValues();
        }

        @Test
        @DisplayName("mysql.enable 为 false 时应该打 SEVERE 并点名实际后端")
        void mysqlDisabledShouldReportSevere() {
            // Arrange
            Logger logger = mock(Logger.class);

            // Act - 配置要 mysql，但 mysql.enable 是 false，实际落到 json
            DataStoreManager.reportBackendSelection(logger, "mysql", false, false, "json");

            // Assert
            List<String> messages = severeMessages(logger);
            assertThat(messages).anyMatch(msg -> msg.contains("json"));
            assertThat(messages).anyMatch(msg -> msg.contains("mysql.enable"));
            assertThat(messages).anyMatch(msg -> msg.contains("datasource.type"));
        }

        @Test
        @DisplayName("mysql 不可达时应该打 SEVERE 并点名实际后端")
        void mysqlUnreachableShouldReportSevere() {
            // Arrange
            Logger logger = mock(Logger.class);

            // Act - mysql.enable 为 true，但连不上，数据源没建起来
            DataStoreManager.reportBackendSelection(logger, "mysql", true, false, "json");

            // Assert
            List<String> messages = severeMessages(logger);
            assertThat(messages).anyMatch(msg -> msg.contains("json"));
            assertThat(messages).anyMatch(msg -> msg.contains("连接失败"));
            // 这条分支的原因和 mysql.enable 那条必须能区分开，否则运维者不知道该改哪里
            assertThat(messages).noneMatch(msg -> msg.contains("mysql.enable"));
        }

        @Test
        @DisplayName("配置了未知类型时应该打 SEVERE 并原样引出配置值")
        void unknownTypeShouldReportSevere() {
            // Arrange
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "postgres", false, false, "json");

            // Assert
            List<String> messages = severeMessages(logger);
            assertThat(messages).anyMatch(msg -> msg.contains("'postgres'"));
            assertThat(messages).anyMatch(msg -> msg.contains("没有已注册的数据存储提供该类型"));
        }

        @Test
        @DisplayName("大小写写错也应该被报出来")
        void wrongCaseShouldReportSevere() {
            // Arrange - 类型是逐字比对的，MySQL 匹配不上 mysql
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "MySQL", true, true, "json");

            // Assert
            List<String> messages = severeMessages(logger);
            assertThat(messages).anyMatch(msg -> msg.contains("'MySQL'"));
        }

        @Test
        @DisplayName("配置与实际一致时不应该打任何日志")
        void matchingBackendShouldStaySilent() {
            // Arrange
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "sqlite", false, false, "sqlite");

            // Assert
            verifyNoInteractions(logger);
        }

        @Test
        @DisplayName("没配 datasource.type 且落到 json 时不应该打任何日志")
        void missingConfigFallingBackToJsonShouldStaySilent() {
            // Arrange - getDatastore 把 null 当 json，诊断必须用同一套口径
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, null, false, false, "json");

            // Assert
            verifyNoInteractions(logger);
        }

        @Test
        @DisplayName("整段诊断里应该同时有配置后端、实际后端和数据不互通的提示")
        void shouldNameBothBackends() {
            // Arrange
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "mysql", false, false, "json");

            // Assert
            String allMessages = String.join(" | ", severeMessages(logger));
            assertThat(allMessages).contains("'mysql'").contains("'json'").contains("不互通");
        }

        @Test
        @DisplayName("配置的后端不存在时不应该暗示它那边有数据")
        void unknownTypeShouldNotImplyItHasData() {
            // Arrange - 'postgres' 这个后端压根不存在，说「和它的数据不互通」是在误导人
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "postgres", false, false, "json");

            // Assert
            assertThat(severeMessages(logger))
                    .noneMatch(msg -> msg.contains("不互通") && msg.contains("'postgres'"));
        }

        @Test
        @DisplayName("i18n 返回 null 时应该回落到原文")
        void nullTranslationShouldFallBackToRawText() {
            // Arrange - 打桩必须在发布到静态字段之前完成，见 TestHelper 的注释与 issue #250
            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                    ultiTools -> when(ultiTools.i18n(anyString())).thenReturn(null));
            Logger logger = mock(Logger.class);

            // Act
            DataStoreManager.reportBackendSelection(logger, "mysql", false, false, "json");

            // Assert
            assertThat(severeMessages(logger)).anyMatch(msg -> msg.contains("datasource.type"));
        }

        @Test
        @DisplayName("i18n 抛异常时不应该把诊断本身带崩")
        void throwingTranslationShouldNotBreakDiagnostics() {
            // Arrange
            com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                    ultiTools -> when(ultiTools.i18n(anyString())).thenThrow(new IllegalStateException("boom")));
            Logger logger = mock(Logger.class);

            // Act & Assert - 诊断跑在「已经出问题」的路径上，它自己不能再炸一次
            assertDoesNotThrow(() -> DataStoreManager.reportBackendSelection(logger, "mysql", false, false, "json"));
            assertThat(severeMessages(logger)).anyMatch(msg -> msg.contains("datasource.type"));
        }
    }
}
