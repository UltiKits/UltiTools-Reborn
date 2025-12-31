package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * 测试 AbstractConfigEntity 抽象类的功能
 */
class AbstractConfigEntityTest {

    @TempDir
    Path tempDir;

    private UltiToolsPlugin mockPlugin;
    private File configFile;

    @BeforeEach
    void setUp() throws IOException {
        mockPlugin = Mockito.mock(UltiToolsPlugin.class);
        
        // 创建临时配置文件
        configFile = tempDir.resolve("test-config.yml").toFile();
        Files.createDirectories(configFile.getParentFile().toPath());
        
        lenient().when(mockPlugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(mockPlugin.getConfigFile(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            return new File(tempDir.toFile(), path);
        });
    }

    @AfterEach
    void tearDown() {
        // 清理临时文件
        if (configFile != null && configFile.exists()) {
            configFile.delete();
        }
    }

    @Test
    @DisplayName("Should create config entity with file path")
    void testConfigEntityCreation() {
        TestConfigEntity entity = new TestConfigEntity("config/test.yml");
        assertThat(entity.getConfigFilePath()).isEqualTo("config/test.yml");
    }

    @Test
    @DisplayName("Should initialize config entity with default values")
    void testInitWithDefaults() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        assertThat(entity.getUltiToolsPlugin()).isEqualTo(mockPlugin);
        assertThat(entity.getConfig()).isNotNull();
    }

    @Test
    @DisplayName("Should save config values to file")
    void testSaveConfig() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setTestString("modified");
        entity.setTestInt(999);
        entity.save();
        
        // 验证文件已保存
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        assertThat(savedFile).exists();
        
        // 读取并验证内容
        YamlConfiguration config = YamlConfiguration.loadConfiguration(savedFile);
        assertThat(config.getString("test.string")).isEqualTo("modified");
        assertThat(config.getInt("test.int")).isEqualTo(999);
    }

    @Test
    @DisplayName("Should load existing config values")
    void testLoadExistingConfig() throws IOException {
        // 预先创建配置文件
        File existingConfig = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("test.string", "loaded");
        yaml.set("test.int", 888);
        yaml.save(existingConfig);
        
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        assertThat(entity.getTestString()).isEqualTo("loaded");
        assertThat(entity.getTestInt()).isEqualTo(888);
    }

    @Test
    @DisplayName("Should update properties from JSONObject")
    void testUpdateProperties() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        JSONObject json = new JSONObject();
        json.put("test.string", "updated");
        json.put("test.int", 777);
        
        entity.updateProperties(json);
        
        assertThat(entity.getTestString()).isEqualTo("updated");
        assertThat(entity.getTestInt()).isEqualTo(777);
    }

    @Test
    @DisplayName("Should convert config to JSONObject")
    void testToJsonObject() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setTestString("json-test");
        entity.setTestInt(666);
        entity.save();
        
        JSONObject json = entity.toJsonObject();
        assertThat(json.getString("test.string")).isEqualTo("json-test");
        assertThat(json.getInteger("test.int")).isEqualTo(666);
    }

    @Test
    @DisplayName("Should get comments from annotations")
    void testGetComments() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        JSONObject comments = entity.getComments();
        assertThat(comments.getString("test.string")).isEqualTo("Test string config");
        assertThat(comments.getString("test.int")).isEqualTo("Test integer config");
    }

    @Test
    @DisplayName("Should handle null config values gracefully")
    void testNullConfigValues() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        // 设置null值
        entity.setTestString(null);
        
        assertThatCode(() -> entity.save()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should use field name when path is empty")
    void testEmptyPathUsesFieldName() throws IOException {
        TestConfigEntityWithEmptyPath entity = new TestConfigEntityWithEmptyPath("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setFieldName("test-value");
        entity.save();
        
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(savedFile);
        assertThat(config.getString("fieldName")).isEqualTo("test-value");
    }

    @Test
    @DisplayName("Should handle boolean config values")
    void testBooleanConfigValues() throws IOException {
        TestConfigEntityWithBoolean entity = new TestConfigEntityWithBoolean("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setEnabled(true);
        entity.save();
        
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(savedFile);
        assertThat(config.getBoolean("enabled")).isTrue();
    }

    @Test
    @DisplayName("Should handle double config values")
    void testDoubleConfigValues() throws IOException {
        TestConfigEntityWithDouble entity = new TestConfigEntityWithDouble("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setMultiplier(1.5);
        entity.save();
        
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(savedFile);
        assertThat(config.getDouble("multiplier")).isEqualTo(1.5);
    }

    @Test
    @DisplayName("Should create missing config file on init")
    void testCreateMissingConfigFile() throws IOException {
        File nonExistentFile = new File(tempDir.toFile(), "new-config.yml");
        assertThat(nonExistentFile).doesNotExist();
        
        TestConfigEntity entity = new TestConfigEntity("new-config.yml");
        entity.init(mockPlugin);
        
        // 配置应该被创建
        assertThat(entity.getConfig()).isNotNull();
    }

    @Test
    @DisplayName("Should skip non-annotated fields")
    void testSkipNonAnnotatedFields() throws IOException {
        TestConfigEntityWithNonAnnotated entity = new TestConfigEntityWithNonAnnotated("test-config.yml");
        entity.init(mockPlugin);
        
        entity.setAnnotatedField("annotated");
        entity.setNonAnnotatedField("non-annotated");
        entity.save();
        
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(savedFile);
        assertThat(config.getString("annotated")).isEqualTo("annotated");
        assertThat(config.getString("nonAnnotated")).isNull();
    }

    // ========== 测试辅助类 ==========

    /**
     * 测试用配置实体
     */
    private static class TestConfigEntity extends AbstractConfigEntity {
        @ConfigEntry(path = "test.string", comment = "Test string config")
        private String testString = "default";

        @ConfigEntry(path = "test.int", comment = "Test integer config")
        private int testInt = 100;

        public TestConfigEntity(String configFilePath) {
            super(configFilePath);
        }

        public String getTestString() {
            return testString;
        }

        public void setTestString(String testString) {
            this.testString = testString;
        }

        public int getTestInt() {
            return testInt;
        }

        public void setTestInt(int testInt) {
            this.testInt = testInt;
        }
    }

    /**
     * 测试空path的配置实体
     */
    private static class TestConfigEntityWithEmptyPath extends AbstractConfigEntity {
        @ConfigEntry(path = "", comment = "Field with empty path")
        private String fieldName = "default";

        public TestConfigEntityWithEmptyPath(String configFilePath) {
            super(configFilePath);
        }

        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }
    }

    /**
     * 测试布尔值的配置实体
     */
    private static class TestConfigEntityWithBoolean extends AbstractConfigEntity {
        @ConfigEntry(path = "enabled", comment = "Enable feature")
        private boolean enabled = false;

        public TestConfigEntityWithBoolean(String configFilePath) {
            super(configFilePath);
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 测试浮点数的配置实体
     */
    private static class TestConfigEntityWithDouble extends AbstractConfigEntity {
        @ConfigEntry(path = "multiplier", comment = "Multiplier value")
        private double multiplier = 1.0;

        public TestConfigEntityWithDouble(String configFilePath) {
            super(configFilePath);
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    /**
     * 测试包含非注解字段的配置实体
     */
    private static class TestConfigEntityWithNonAnnotated extends AbstractConfigEntity {
        @ConfigEntry(path = "annotated", comment = "Annotated field")
        private String annotatedField = "default";

        private String nonAnnotatedField = "should-not-save";

        public TestConfigEntityWithNonAnnotated(String configFilePath) {
            super(configFilePath);
        }

        public void setAnnotatedField(String annotatedField) {
            this.annotatedField = annotatedField;
        }

        public void setNonAnnotatedField(String nonAnnotatedField) {
            this.nonAnnotatedField = nonAnnotatedField;
        }
    }
}
