package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

/**
 * Configurable 接口测试
 */
@DisplayName("Configurable 接口测试")
class ConfigurableTest {

    /**
     * 测试用配置实体类
     */
    static class TestConfigEntity extends AbstractConfigEntity {
        private String testValue = "default";

        public TestConfigEntity() {
            super("config/test.yml");
        }

        public String getTestValue() {
            return testValue;
        }

        public void setTestValue(String testValue) {
            this.testValue = testValue;
        }
    }

    /**
     * 另一个测试用配置实体类
     */
    static class AnotherConfigEntity extends AbstractConfigEntity {
        private int number = 0;

        public AnotherConfigEntity() {
            super("config/another.yml");
        }

        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }
    }

    /**
     * 使用默认实现的 Configurable
     */
    static class DefaultConfigurable implements Configurable {
        @Override
        public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
            return null;
        }

        @Override
        public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
            return null;
        }

        @Override
        public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
            // 空实现
        }
    }

    /**
     * 自定义实现的 Configurable
     */
    static class CustomConfigurable implements Configurable {
        private final List<AbstractConfigEntity> configs;

        public CustomConfigurable(List<AbstractConfigEntity> configs) {
            this.configs = configs;
        }

        @Override
        public List<AbstractConfigEntity> getAllConfigs() {
            return configs;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
            for (AbstractConfigEntity config : configs) {
                if (configType.isInstance(config)) {
                    return (T) config;
                }
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
            // 简化实现 - 忽略 path
            return getConfig(configType);
        }

        @Override
        public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
            // 测试实现
        }
    }

    /**
     * 抛出异常的 Configurable 实现
     */
    static class ThrowingConfigurable implements Configurable {
        @Override
        public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
            return null;
        }

        @Override
        public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
            return null;
        }

        @Override
        public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
            throw new IOException("Test exception");
        }
    }

    @Nested
    @DisplayName("getAllConfigs 测试")
    class GetAllConfigsTests {

        @Test
        @DisplayName("默认实现应该返回空列表")
        void defaultShouldReturnEmptyList() {
            // Arrange
            Configurable configurable = new DefaultConfigurable();

            // Act
            List<AbstractConfigEntity> configs = configurable.getAllConfigs();

            // Assert
            assertThat(configs).isEmpty();
        }

        @Test
        @DisplayName("自定义实现应该返回配置列表")
        void customShouldReturnConfigList() {
            // Arrange
            TestConfigEntity config1 = new TestConfigEntity();
            AnotherConfigEntity config2 = new AnotherConfigEntity();
            Configurable configurable = new CustomConfigurable(Arrays.asList(config1, config2));

            // Act
            List<AbstractConfigEntity> configs = configurable.getAllConfigs();

            // Assert
            assertThat(configs).hasSize(2);
            assertThat(configs).containsExactly(config1, config2);
        }

        @Test
        @DisplayName("空配置列表应该正常工作")
        void emptyConfigListShouldWork() {
            // Arrange
            Configurable configurable = new CustomConfigurable(Collections.emptyList());

            // Act
            List<AbstractConfigEntity> configs = configurable.getAllConfigs();

            // Assert
            assertThat(configs).isEmpty();
        }
    }

    @Nested
    @DisplayName("getConfig 测试")
    class GetConfigTests {

        @Test
        @DisplayName("应该返回指定类型的配置")
        void shouldReturnConfigByType() {
            // Arrange
            TestConfigEntity testConfig = new TestConfigEntity();
            testConfig.setTestValue("custom value");
            Configurable configurable = new CustomConfigurable(Arrays.asList(testConfig));

            // Act
            TestConfigEntity result = configurable.getConfig(TestConfigEntity.class);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getTestValue()).isEqualTo("custom value");
        }

        @Test
        @DisplayName("不存在的配置类型应该返回 null")
        void shouldReturnNullForNonExistentType() {
            // Arrange
            TestConfigEntity testConfig = new TestConfigEntity();
            Configurable configurable = new CustomConfigurable(Arrays.asList(testConfig));

            // Act
            AnotherConfigEntity result = configurable.getConfig(AnotherConfigEntity.class);

            // Assert
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("通过路径和类型获取配置")
        void shouldGetConfigByPathAndType() {
            // Arrange
            TestConfigEntity testConfig = new TestConfigEntity();
            Configurable configurable = new CustomConfigurable(Arrays.asList(testConfig));

            // Act
            TestConfigEntity result = configurable.getConfig("config/test.yml", TestConfigEntity.class);

            // Assert
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("saveConfig 测试")
    class SaveConfigTests {

        @Test
        @DisplayName("保存配置应该不抛出异常")
        void shouldNotThrowException() throws IOException {
            // Arrange
            Configurable configurable = new DefaultConfigurable();

            // Act & Assert - 不应该抛出异常
            configurable.saveConfig("config/test.yml", TestConfigEntity.class);
        }

        @Test
        @DisplayName("保存配置抛出 IOException")
        void shouldThrowIOException() {
            // Arrange
            Configurable configurable = new ThrowingConfigurable();

            // Act & Assert
            assertThatThrownBy(() -> configurable.saveConfig("config/test.yml", TestConfigEntity.class))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Test exception");
        }
    }
}
