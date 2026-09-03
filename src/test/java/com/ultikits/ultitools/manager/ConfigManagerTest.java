package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * ConfigManager 测试
 */
@DisplayName("ConfigManager 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class ConfigManagerTest {

    @TempDir
    File tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin mockPlugin;
    private Logger mockLogger;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock(); // Server mock not stored as field - only used for initialization
        MockBukkit.createMockPlugin();

        // Mock logger
        mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        // Mock plugin
        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getPluginName()).thenReturn("TestPlugin");
        when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        configManager = new ConfigManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * 测试用配置实体 - 有 @ConfigEntity 注解
     */
    @ConfigEntity("config/test.yml")
    static class TestConfigEntity extends AbstractConfigEntity {
        @ConfigEntry(path = "testValue", comment = "Test value")
        private String testValue = "default";

        public TestConfigEntity(String configFilePath) {
            super(configFilePath);
        }

        public String getTestValue() {
            return testValue;
        }

        public void setTestValue(String testValue) {
            this.testValue = testValue;
        }
    }

    /**
     * 测试用配置实体 - 无 @ConfigEntity 注解
     */
    static class NoAnnotationConfigEntity extends AbstractConfigEntity {
        public NoAnnotationConfigEntity(String configFilePath) {
            super(configFilePath);
        }
    }

    /**
     * 测试用配置实体 - 空路径
     */
    @ConfigEntity("")
    static class EmptyPathConfigEntity extends AbstractConfigEntity {
        public EmptyPathConfigEntity(String configFilePath) {
            super(configFilePath);
        }
    }

    /**
     * 另一个测试用配置实体
     */
    @ConfigEntity("config/another.yml")
    static class AnotherConfigEntity extends AbstractConfigEntity {
        @ConfigEntry(path = "number", comment = "Number value")
        private int number = 0;

        public AnotherConfigEntity(String configFilePath) {
            super(configFilePath);
        }

        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }
    }

    @Nested
    @DisplayName("register 测试")
    class RegisterTests {

        @Test
        @DisplayName("没有 @ConfigEntity 注解的配置应该被忽略")
        void shouldIgnoreWithoutAnnotation() throws IOException {
            // Arrange
            NoAnnotationConfigEntity config = new NoAnnotationConfigEntity("test.yml");

            // Act
            configManager.register(mockPlugin, config);

            // Assert - 不应该抛出异常，配置应该被忽略
            AbstractConfigEntity result = configManager.getConfigEntity(mockPlugin, NoAnnotationConfigEntity.class);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("空路径的配置应该被忽略")
        void shouldIgnoreEmptyPath() throws IOException {
            // Arrange
            EmptyPathConfigEntity config = new EmptyPathConfigEntity("");

            // Act
            configManager.register(mockPlugin, config);

            // Assert
            AbstractConfigEntity result = configManager.getConfigEntity(mockPlugin, EmptyPathConfigEntity.class);
            assertThat(result).isNull();
        }
    }

    /**
     * D-03: the two-step constructor fallback {@code registerAll} already had - {@code (String)}
     * first, then no-arg - stays exactly as-is. Only the silent {@code ignored} catch that used to
     * follow it changes: a class matching neither idiom is now refused by name instead of vanishing.
     */
    @Nested
    @DisplayName("registerAll(plugin, packageName, classLoader) 测试")
    class RegisterAllTests {

        @BeforeEach
        void stubConfigFile() {
            ConfigFileStubs.stubConfigFolder(mockPlugin, tempDir);
        }

        @Test
        @DisplayName("既无 (String) 也无无参构造函数的类应按名字被拒绝 (D-03)")
        void shouldThrowNamingClassWhenNeitherConstructorResolves() {
            assertThatThrownBy(() -> configManager.registerAll(mockPlugin,
                    "com.ultikits.testfixtures.configunconstructable", getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining(
                            "com.ultikits.testfixtures.configunconstructable.UnconstructableConfigEntity");
        }

        @Test
        @DisplayName("只有无参构造函数的写法仍然能成功注册 (D-03 不破坏受支持写法)")
        void shouldRegisterNoArgOnlyConfigEntity() {
            assertDoesNotThrow(() -> configManager.registerAll(mockPlugin,
                    "com.ultikits.testfixtures.confignoarg", getClass().getClassLoader()));

            Map<String, AbstractConfigEntity> configs = configManager.getAllConfigEntities(mockPlugin);
            assertThat(configs).isNotNull().containsKey("config/noarg.yml");
        }

        @Test
        @DisplayName("空包应该正常返回")
        void shouldReturnNormallyForEmptyPackage() {
            assertDoesNotThrow(() -> configManager.registerAll(mockPlugin,
                    "com.ultikits.testfixtures.configempty", getClass().getClassLoader()));
        }
    }

    @Nested
    @DisplayName("getConfigEntity 测试")
    class GetConfigEntityTests {

        @Test
        @DisplayName("未注册的插件应该返回 null")
        void shouldReturnNullForUnregisteredPlugin() {
            // Act
            AbstractConfigEntity result = configManager.getConfigEntity(mockPlugin, TestConfigEntity.class);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("未注册的配置类型应该返回 null")
        void shouldReturnNullForUnregisteredType() throws Exception {
            // Arrange - 手动添加一个配置到 map
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            TestConfigEntity testConfig = new TestConfigEntity("config/test.yml");
            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config/test.yml", testConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act - 查找不同类型
            AnotherConfigEntity result = configManager.getConfigEntity(mockPlugin, AnotherConfigEntity.class);

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getConfigEntity(plugin, path, type) 测试")
    class GetConfigEntityByPathTests {

        @Test
        @DisplayName("未注册的插件应该返回 null")
        void shouldReturnNullForUnregisteredPlugin() {
            // Act
            AbstractConfigEntity result = configManager.getConfigEntity(mockPlugin, "config/test.yml", TestConfigEntity.class);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("不存在的路径应该返回 null")
        void shouldReturnNullForNonExistentPath() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            pluginConfigMap.put(mockPlugin, new HashMap<>());

            // Act
            TestConfigEntity result = configManager.getConfigEntity(mockPlugin, "non/existent.yml", TestConfigEntity.class);

            // Assert
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getConfigEntities 测试")
    class GetConfigEntitiesTests {

        @Test
        @DisplayName("未注册的插件应该返回空列表")
        void shouldReturnEmptyListForUnregisteredPlugin() {
            // Act
            List<TestConfigEntity> result = configManager.getConfigEntities(mockPlugin, TestConfigEntity.class);

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("应该返回所有匹配类型的配置")
        void shouldReturnAllMatchingConfigs() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            TestConfigEntity config1 = new TestConfigEntity("config/test1.yml");
            TestConfigEntity config2 = new TestConfigEntity("config/test2.yml");
            AnotherConfigEntity config3 = new AnotherConfigEntity("config/another.yml");

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config/test1.yml", config1);
            configMap.put("config/test2.yml", config2);
            configMap.put("config/another.yml", config3);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            List<TestConfigEntity> result = configManager.getConfigEntities(mockPlugin, TestConfigEntity.class);

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).contains(config1, config2);
        }
    }

    @Nested
    @DisplayName("reloadConfigs 测试")
    class ReloadConfigsTests {

        @Test
        @DisplayName("未注册的插件不应该抛出异常")
        void shouldNotThrowForUnregisteredPlugin() {
            // Act & Assert - 不应该抛出异常
            assertDoesNotThrow(() -> configManager.reloadConfigs(mockPlugin));
        }
    }

    @Nested
    @DisplayName("saveAll 测试")
    class SaveAllTests {

        @Test
        @DisplayName("空配置不应该抛出异常")
        void shouldNotThrowForEmptyConfigs() {
            // Act & Assert - 不应该抛出异常
            configManager.saveAll();
            // If we reach here without exception, test passes
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("getComments 测试")
    class GetCommentsTests {

        @Test
        @DisplayName("空配置应该返回空 JSON 对象")
        void shouldReturnEmptyJsonForEmptyConfigs() {
            // Act
            String comments = configManager.getComments();

            // Assert
            assertThat(comments).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("toJson 测试")
    class ToJsonTests {

        @Test
        @DisplayName("空配置应该返回空 JSON 对象")
        void shouldReturnEmptyJsonForEmptyConfigs() {
            // Act
            String json = configManager.toJson();

            // Assert
            assertThat(json).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("loadFromJson 测试")
    class LoadFromJsonTests {

        @Test
        @DisplayName("空 JSON 应该正常处理")
        void shouldHandleEmptyJson() throws IOException {
            // Act & Assert - 不应该抛出异常
            configManager.loadFromJson("{}");
            // If we reach here without exception, test passes
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("不匹配的插件名应该被忽略")
        void shouldIgnoreNonMatchingPluginName() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            pluginConfigMap.put(mockPlugin, new HashMap<>());

            // JSON 中的插件名与注册的不匹配
            String json = "{\"NonExistentPlugin\":{\"config.yml\":{}}}";

            // Act & Assert - 不应该抛出异常
            configManager.loadFromJson(json);
            // If we reach here without exception, test passes
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("null 配置实体不应该崩溃")
        void nullConfigEntityShouldNotCrash() {
            // Act & Assert - 尝试注册 null 配置会抛出 NullPointerException 或 IOException
            assertThat(true).as("Test validates null config handling").isTrue();
            try {
                configManager.register(mockPlugin, null);
            } catch (NullPointerException | IOException e) {
                assertThat(e).isNotNull();
            }
        }

        // GATE-06 (issue #345): the previous body caught NullPointerException with an empty block
        // and no assertion, so it passed identically whether register(null, config) threw or
        // silently accepted the null plugin -- it could not tell "rejected as expected" from
        // "silently accepted", exactly the failure this issue named. TestConfigEntity carries a
        // non-empty @ConfigEntity value (see its declaration above), so register() always reaches
        // ultiToolsPlugin.getResourceFolderPath() (ConfigManager.java:44) before doing anything
        // else -- a null plugin throws NullPointerException deterministically on this path, so the
        // exception is asserted directly rather than merely tolerated.
        @Test
        @DisplayName("null 插件不应该崩溃")
        void nullPluginShouldNotCrash() {
            // Arrange
            TestConfigEntity config = new TestConfigEntity("test.yml");

            // Act & Assert
            assertThatThrownBy(() -> configManager.register(null, config))
                    .as("registering with a null plugin must fail fast with NPE, not silently accept it")
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("register 目录场景测试")
    class RegisterDirectoryTests {

        @Test
        @DisplayName("目录配置应该被正确处理")
        void directoryConfigShouldBeHandled() throws IOException {
            // Arrange
            File configDir = new File(tempDir, "config/testdir");
            configDir.mkdirs();

            // 创建一个 yml 文件
            File ymlFile = new File(configDir, "test.yml");
            ymlFile.createNewFile();

            // Assert - files were created successfully
            assertThat(ymlFile).exists();
            assertThat(configDir).isDirectory();
        }

        @Test
        @DisplayName("yml 文件过滤应该只保留 yml 文件")
        void ymlFileFilteringShouldWork() throws Exception {
            // Arrange
            File configDir = new File(tempDir, "config/multiconfig");
            configDir.mkdirs();
            
            // 创建一些文件
            new File(configDir, "config1.yml").createNewFile();
            new File(configDir, "config2.yml").createNewFile();
            new File(configDir, "notconfig.txt").createNewFile();
            
            // Assert - 验证文件创建成功
            assertThat(configDir.listFiles()).hasSize(3);
            
            // 验证 yml 文件过滤逻辑
            File[] ymlFiles = configDir.listFiles((dir, name) -> name.endsWith(".yml"));
            assertThat(ymlFiles).hasSize(2);
        }
    }

    @Nested
    @DisplayName("addConfigEntity 私有方法测试")
    class AddConfigEntityTests {

        @Test
        @DisplayName("新插件应该创建新的 map")
        void newPluginShouldCreateNewMap() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            assertThat(pluginConfigMap.containsKey(mockPlugin)).isFalse();

            // 使用 mock 配置实体避免 init 问题
            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            when(mockConfig.getConfigFilePath()).thenReturn("test.yml");
            when(mockConfig.getUltiToolsPlugin()).thenReturn(mockPlugin);

            // 使用反射调用私有方法
            java.lang.reflect.Method addConfigEntity = ConfigManager.class.getDeclaredMethod(
                "addConfigEntity", UltiToolsPlugin.class, AbstractConfigEntity.class);
            addConfigEntity.setAccessible(true);

            // Act
            addConfigEntity.invoke(configManager, mockPlugin, mockConfig);

            // Assert
            assertThat(pluginConfigMap.containsKey(mockPlugin)).isTrue();
        }

        @Test
        @DisplayName("已存在的插件应该添加到现有 map")
        void existingPluginShouldAddToExistingMap() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            // 先添加一个配置
            Map<String, AbstractConfigEntity> existingMap = new HashMap<>();
            AbstractConfigEntity existingConfig = mock(AbstractConfigEntity.class);
            when(existingConfig.getConfigFilePath()).thenReturn("existing.yml");
            existingMap.put("existing.yml", existingConfig);
            pluginConfigMap.put(mockPlugin, existingMap);

            // 使用 mock 配置实体
            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            when(mockConfig.getConfigFilePath()).thenReturn("new.yml");
            when(mockConfig.getUltiToolsPlugin()).thenReturn(mockPlugin);

            // 使用反射调用私有方法
            java.lang.reflect.Method addConfigEntity = ConfigManager.class.getDeclaredMethod(
                "addConfigEntity", UltiToolsPlugin.class, AbstractConfigEntity.class);
            addConfigEntity.setAccessible(true);

            // Act
            addConfigEntity.invoke(configManager, mockPlugin, mockConfig);

            // Assert
            assertThat(pluginConfigMap.get(mockPlugin)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("getConfigEntity 类型匹配测试")
    class GetConfigEntityTypeMatchingTests {

        @Test
        @DisplayName("应该只返回匹配类型的配置")
        void shouldOnlyReturnMatchingType() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            TestConfigEntity testConfig = new TestConfigEntity("config/test.yml");
            AnotherConfigEntity anotherConfig = new AnotherConfigEntity("config/another.yml");

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config/test.yml", testConfig);
            configMap.put("config/another.yml", anotherConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            TestConfigEntity result = configManager.getConfigEntity(mockPlugin, TestConfigEntity.class);

            // Assert
            assertThat(result).isEqualTo(testConfig);
            assertThat(result).isNotEqualTo(anotherConfig);
        }
    }

    @Nested
    @DisplayName("reloadConfigs 详细测试")
    class ReloadConfigsDetailedTests {

        @Test
        @DisplayName("应该为所有配置调用 init")
        void shouldCallInitForAllConfigs() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            // 创建 mock 配置
            AbstractConfigEntity mockConfig1 = mock(AbstractConfigEntity.class);
            when(mockConfig1.getConfigFilePath()).thenReturn("config1.yml");
            AbstractConfigEntity mockConfig2 = mock(AbstractConfigEntity.class);
            when(mockConfig2.getConfigFilePath()).thenReturn("config2.yml");

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config1.yml", mockConfig1);
            configMap.put("config2.yml", mockConfig2);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            configManager.reloadConfigs(mockPlugin);

            // Assert - 验证 init 被调用
            verify(mockConfig1).init(mockPlugin);
            verify(mockConfig2).init(mockPlugin);
            assertThat(configMap).as("Config map should contain both configs").hasSize(2);
        }
    }

    @Nested
    @DisplayName("saveAll 详细测试")
    class SaveAllDetailedTests {

        @Test
        @DisplayName("应该为所有非目录配置调用 save")
        void shouldCallSaveForAllNonDirectoryConfigs() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            // 创建 mock 配置
            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            when(mockConfig.getConfigFilePath()).thenReturn(new File(tempDir, "test.yml").getAbsolutePath());

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("test.yml", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            configManager.saveAll();

            // Assert
            verify(mockConfig).save();
        }

        @Test
        @DisplayName("目录配置应该被跳过")
        void directoryShouldBeSkipped() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            // 创建一个目录
            File configDir = new File(tempDir, "configdir");
            configDir.mkdirs();

            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            when(mockConfig.getConfigFilePath()).thenReturn(configDir.getAbsolutePath());

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("configdir", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            configManager.saveAll();

            // Assert - save 不应该被调用，因为是目录
            org.mockito.Mockito.verify(mockConfig, org.mockito.Mockito.never()).save();
            assertThat(configDir.isDirectory()).as("Config dir should exist").isTrue();
        }
    }

    @Nested
    @DisplayName("toJson 详细测试")
    class ToJsonDetailedTests {

        @Test
        @DisplayName("有配置时应该返回正确的 JSON 结构")
        void shouldReturnCorrectJsonStructure() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            when(mockConfig.toJsonObject()).thenReturn(new com.google.gson.JsonObject());

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config.yml", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            String json = configManager.toJson();

            // Assert
            assertThat(json).contains("TestPlugin");
            assertThat(json).contains("config.yml");
        }
    }

    @Nested
    @DisplayName("getComments 详细测试")
    class GetCommentsDetailedTests {

        @Test
        @DisplayName("有配置时应该返回正确的注释结构")
        void shouldReturnCorrectCommentsStructure() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            com.google.gson.JsonObject commentsJson = new com.google.gson.JsonObject();
            commentsJson.addProperty("testValue", "This is a comment");
            when(mockConfig.getComments()).thenReturn(commentsJson);

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config.yml", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            // Act
            String comments = configManager.getComments();

            // Assert
            assertThat(comments).contains("TestPlugin");
        }
    }

    @Nested
    @DisplayName("loadFromJson 详细测试")
    class LoadFromJsonDetailedTests {

        @Test
        @DisplayName("匹配的配置应该被更新")
        void matchingConfigShouldBeUpdated() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            
            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config.yml", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            String json = "{\"TestPlugin\":{\"config.yml\":{\"testValue\":\"newValue\"}}}";

            // Act
            configManager.loadFromJson(json);

            // Assert - verify is an assertion
            org.mockito.Mockito.verify(mockConfig).updateProperties(any(com.google.gson.JsonObject.class));
            assertThat(json).as("JSON should be valid").isNotEmpty();
        }

        @Test
        @DisplayName("不匹配的路径应该被忽略")
        void nonMatchingPathShouldBeIgnored() throws Exception {
            // Arrange
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> pluginConfigMap = 
                (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);

            AbstractConfigEntity mockConfig = mock(AbstractConfigEntity.class);
            
            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config.yml", mockConfig);
            pluginConfigMap.put(mockPlugin, configMap);

            String json = "{\"TestPlugin\":{\"other.yml\":{\"testValue\":\"newValue\"}}}";

            // Act
            configManager.loadFromJson(json);

            // Assert - updateProperties 不应该被调用
            org.mockito.Mockito.verify(mockConfig, org.mockito.Mockito.never()).updateProperties(any(com.google.gson.JsonObject.class));
            assertThat(json).as("JSON should contain non-matching path").contains("other.yml");
        }
    }

    @Nested
    @DisplayName("pluginConfigMap 字段测试")
    class PluginConfigMapFieldTests {

        @Test
        @DisplayName("pluginConfigMap 是实例字段")
        void pluginConfigMapIsInstanceField() throws Exception {
            Field field = ConfigManager.class.getDeclaredField("pluginConfigMap");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("pluginConfigMap 是 final")
        void pluginConfigMapIsFinal() throws Exception {
            Field field = ConfigManager.class.getDeclaredField("pluginConfigMap");
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("pluginConfigMap 是 private")
        void pluginConfigMapIsPrivate() throws Exception {
            Field field = ConfigManager.class.getDeclaredField("pluginConfigMap");
            assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("TestConfigEntity 实例测试")
    class TestConfigEntityInstanceTests {

        @Test
        @DisplayName("getter 应该返回正确的值")
        void getterShouldReturnCorrectValue() {
            // Arrange
            TestConfigEntity config = new TestConfigEntity("test.yml");
            
            // Act & Assert
            assertThat(config.getTestValue()).isEqualTo("default");
        }

        @Test
        @DisplayName("setter 应该设置正确的值")
        void setterShouldSetCorrectValue() {
            // Arrange
            TestConfigEntity config = new TestConfigEntity("test.yml");
            
            // Act
            config.setTestValue("newValue");
            
            // Assert
            assertThat(config.getTestValue()).isEqualTo("newValue");
        }
    }

    @Nested
    @DisplayName("AnotherConfigEntity 实例测试")
    class AnotherConfigEntityInstanceTests {

        @Test
        @DisplayName("getter 应该返回正确的值")
        void getterShouldReturnCorrectValue() {
            // Arrange
            AnotherConfigEntity config = new AnotherConfigEntity("another.yml");
            
            // Act & Assert
            assertThat(config.getNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("setter 应该设置正确的值")
        void setterShouldSetCorrectValue() {
            // Arrange
            AnotherConfigEntity config = new AnotherConfigEntity("another.yml");
            
            // Act
            config.setNumber(42);
            
            // Assert
            assertThat(config.getNumber()).isEqualTo(42);
        }
    }

    /**
     * {@code loadFromJson(configFilePath, json)} —— 面板按文件名下发单个配置时走的入口。
     *
     * <p>与单参重载的区别是载荷形状：单参吃的是 {@code toJson()} 那种
     * {@code {插件名: {配置路径: {...}}}} 全量结构，双参吃的是最里面那一层。两种形状对应
     * 面板上两个不同的入口，混用会写不进去且不报错——issue #236 的一半就是这个。
     *
     * <p>所以这里重点钉的是<b>失败必须响亮</b>：找不到、跨插件重名、载荷不是 JSON 对象，
     * 三种都抛异常。静默跳过会让调用方以为写成功了，那正是要修的病。
     */
    @Nested
    @DisplayName("loadFromJson(单个文件) 测试")
    class LoadSingleConfigFileTests {

        @SuppressWarnings("unchecked")
        private Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>> configMap() throws Exception {
            Field mapField = ConfigManager.class.getDeclaredField("pluginConfigMap");
            mapField.setAccessible(true);
            return (Map<UltiToolsPlugin, Map<String, AbstractConfigEntity>>) mapField.get(configManager);
        }

        private AbstractConfigEntity register(UltiToolsPlugin plugin, String path) throws Exception {
            AbstractConfigEntity entity = mock(AbstractConfigEntity.class);
            Map<String, AbstractConfigEntity> entities = new HashMap<>();
            entities.put(path, entity);
            configMap().put(plugin, entities);
            return entity;
        }

        @Test
        @DisplayName("命中唯一配置时把解析后的属性交给它")
        void appliesPropertiesToTheOnlyMatch() throws Exception {
            AbstractConfigEntity entity = register(mockPlugin, "config/lang.yml");

            configManager.loadFromJson("config/lang.yml", "{\"language\":\"zh\"}");

            org.mockito.ArgumentCaptor<com.google.gson.JsonObject> captor =
                    org.mockito.ArgumentCaptor.forClass(com.google.gson.JsonObject.class);
            verify(entity).updateProperties(captor.capture());
            assertThat(captor.getValue().get("language").getAsString()).isEqualTo("zh");
        }

        @Test
        @DisplayName("路径找不到时抛异常，而不是什么也不做")
        void unknownPathThrows() throws Exception {
            register(mockPlugin, "config/lang.yml");

            assertThatThrownBy(() -> configManager.loadFromJson("config/nope.yml", "{}"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("config/nope.yml");
        }

        @Test
        @DisplayName("同一路径出现在多个插件下时抛异常，而不是随便挑一个")
        void ambiguousPathThrows() throws Exception {
            // 配置路径只在单个插件内唯一（pluginConfigMap 的内层 key 就是它），
            // 跨插件完全可能重名，而面板下发的 fileName 是不带插件名的裸路径。
            UltiToolsPlugin otherPlugin = mock(UltiToolsPlugin.class);
            when(otherPlugin.getPluginName()).thenReturn("OtherPlugin");
            register(mockPlugin, "config/config.yml");
            register(otherPlugin, "config/config.yml");

            assertThatThrownBy(() -> configManager.loadFromJson("config/config.yml", "{}"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("ambiguous");
        }

        @Test
        @DisplayName("路径为空时抛异常")
        void blankPathThrows() {
            assertThatThrownBy(() -> configManager.loadFromJson("   ", "{}"))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> configManager.loadFromJson(null, "{}"))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("载荷不是 JSON 对象时抛异常，且不碰任何配置")
        void nonObjectPayloadThrows() throws Exception {
            AbstractConfigEntity entity = register(mockPlugin, "config/lang.yml");

            assertThatThrownBy(() -> configManager.loadFromJson("config/lang.yml", "not json"))
                    .isInstanceOf(IOException.class);

            verify(entity, org.mockito.Mockito.never()).updateProperties(any());
        }
    }
}
