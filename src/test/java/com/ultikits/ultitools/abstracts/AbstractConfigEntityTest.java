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

import com.google.gson.JsonObject;
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
    void testInitWithDefaults() throws Exception {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);

        assertThat(entity.getUltiToolsPlugin()).isEqualTo(mockPlugin);
        assertThat(entity.getConfig()).isNotNull();

        // The file didn't exist before init() - every @ConfigEntry key on it is newly added, so
        // each one arrives with its default value AND its comment (D-07/D-09).
        File savedFile = new File(tempDir.toFile(), "test-config.yml");
        YamlConfiguration withComments = new YamlConfiguration();
        withComments.options().parseComments(true);
        withComments.load(savedFile);
        assertThat(withComments.getString("test.string")).isEqualTo("default");
        assertThat(withComments.getComments("test.string")).containsExactly("Test string config");
        assertThat(withComments.getInt("test.int")).isEqualTo(100);
        assertThat(withComments.getComments("test.int")).containsExactly("Test integer config");
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
        
        JsonObject json = new JsonObject();
        json.addProperty("test.string", "updated");
        json.addProperty("test.int", 777);
        
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
        
        JsonObject json = entity.toJsonObject();
        assertThat(json.get("test.string").getAsString()).isEqualTo("json-test");
        assertThat(json.get("test.int").getAsInt()).isEqualTo(666);
    }

    @Test
    @DisplayName("Should get comments from annotations")
    void testGetComments() throws IOException {
        TestConfigEntity entity = new TestConfigEntity("test-config.yml");
        entity.init(mockPlugin);
        
        JsonObject comments = entity.getComments();
        assertThat(comments.get("test.string").getAsString()).isEqualTo("Test string config");
        assertThat(comments.get("test.int").getAsString()).isEqualTo("Test integer config");
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
    void testCreateMissingConfigFile() throws Exception {
        File nonExistentFile = new File(tempDir.toFile(), "new-config.yml");
        assertThat(nonExistentFile).doesNotExist();

        TestConfigEntity entity = new TestConfigEntity("new-config.yml");
        entity.init(mockPlugin);

        // 配置应该被创建
        assertThat(entity.getConfig()).isNotNull();
        assertThat(nonExistentFile).exists();

        // Every @ConfigEntry key on a freshly-created file is newly added, so each one carries
        // both its default value and its comment (D-07/D-09) - not just an empty file.
        YamlConfiguration withComments = new YamlConfiguration();
        withComments.options().parseComments(true);
        withComments.load(nonExistentFile);
        assertThat(withComments.getString("test.string")).isEqualTo("default");
        assertThat(withComments.getComments("test.string")).containsExactly("Test string config");
        assertThat(withComments.getInt("test.int")).isEqualTo(100);
        assertThat(withComments.getComments("test.int")).containsExactly("Test integer config");
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

    // ==================== 四个遍历方法必须对得上 ====================
    //
    // init() / save() / reload() / updateProperties() 各自遍历 @ConfigEntry 字段。
    // 只要它们对「遍历哪些字段」或「path 怎么推导」的答案不一致，就会出现
    // 「更新成功但值没变」——updateProperties 跳过字段之后 config.save() 照常执行，
    // 调用方拿不到任何失败信号。面板下发配置正是走这条路（issue #236）。

    /** 不写 path 的 @ConfigEntry —— 受支持的写法，路径归一到字段名。 */
    private static class DefaultPathConfigEntity extends AbstractConfigEntity {
        @ConfigEntry(comment = "No explicit path")
        private String implicitPath = "default";

        public DefaultPathConfigEntity(String configFilePath) {
            super(configFilePath);
        }

        public String getImplicitPath() {
            return implicitPath;
        }
    }

    /** @ConfigEntry 字段声明在父类上。 */
    private static class InheritedFieldConfigEntity extends DefaultPathConfigEntity {
        @ConfigEntry(path = "child.value", comment = "Declared on the subclass")
        private String childValue = "child-default";

        public InheritedFieldConfigEntity(String configFilePath) {
            super(configFilePath);
        }

        public String getChildValue() {
            return childValue;
        }
    }

    @Test
    @DisplayName("没写 path 的字段，updateProperties 也要按字段名认得出来")
    void updatePropertiesHonoursTheDefaultPath() throws IOException {
        DefaultPathConfigEntity entity = new DefaultPathConfigEntity("default-path.yml");
        entity.init(mockPlugin);

        // init/save 按字段名写盘，toJsonObject 也按字段名发给面板，所以面板回来的
        // 就是这个键。updateProperties 过去找的是空字符串键，永远找不到。
        JsonObject json = new JsonObject();
        json.addProperty("implicitPath", "updated");

        entity.updateProperties(json);

        assertThat(entity.getImplicitPath()).isEqualTo("updated");
        assertThat(YamlConfiguration.loadConfiguration(new File(tempDir.toFile(), "default-path.yml"))
                .getString("implicitPath")).isEqualTo("updated");
    }

    @Test
    @DisplayName("toJsonObject 发出去的键，updateProperties 必须原样收得回来")
    void theJsonRoundTripIsClosed() throws IOException {
        DefaultPathConfigEntity entity = new DefaultPathConfigEntity("round-trip.yml");
        entity.init(mockPlugin);

        // 这条断言的是「发出去什么、就能收回什么」，而不是某个具体键名——
        // #236 那一族缺陷全都长在两条路径对同一个名字的理解不一致上。
        JsonObject emitted = entity.toJsonObject();
        assertThat(emitted.keySet()).contains("implicitPath");

        emitted.addProperty("implicitPath", "round-tripped");
        entity.updateProperties(emitted);

        assertThat(entity.getImplicitPath()).isEqualTo("round-tripped");
    }

    @Test
    @DisplayName("父类上声明的 @ConfigEntry 字段同样可更新")
    void updatePropertiesCoversInheritedFields() throws IOException {
        InheritedFieldConfigEntity entity = new InheritedFieldConfigEntity("inherited.yml");
        entity.init(mockPlugin);

        JsonObject json = new JsonObject();
        json.addProperty("implicitPath", "from-parent");
        json.addProperty("child.value", "from-child");

        entity.updateProperties(json);

        // init/save/reload 走的是完整字段树，updateProperties 过去只走
        // getDeclaredFields()，于是父类字段读得出、写不进。
        assertThat(entity.getImplicitPath()).isEqualTo("from-parent");
        assertThat(entity.getChildValue()).isEqualTo("from-child");
    }

    @Test
    @DisplayName("getComments 的键要和 toJsonObject 的键对得上")
    void commentKeysMatchValueKeys() throws IOException {
        InheritedFieldConfigEntity entity = new InheritedFieldConfigEntity("comments.yml");
        entity.init(mockPlugin);

        JsonObject comments = entity.getComments();

        assertThat(comments.keySet())
                .as("没写 path 的字段，注释不能被塞在空字符串键下")
                .doesNotContain("")
                .contains("implicitPath", "child.value");
        assertThat(entity.toJsonObject().keySet()).containsAll(comments.keySet());
    }
}
