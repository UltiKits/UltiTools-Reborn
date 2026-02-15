package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BaseService 接口测试
 */
@DisplayName("BaseService 接口测试")
class BaseServiceTest {

    /**
     * 测试用的 BaseService 实现
     */
    static class TestBaseService implements BaseService {
        private final String name;
        private final String author;
        private final int version;
        private final String resourceFolderName;

        public TestBaseService(String name, String author, int version) {
            this.name = name;
            this.author = author;
            this.version = version;
            this.resourceFolderName = null; // 使用默认实现
        }

        public TestBaseService(String name, String author, int version, String resourceFolderName) {
            this.name = name;
            this.author = author;
            this.version = version;
            this.resourceFolderName = resourceFolderName;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getAuthor() {
            return author;
        }

        @Override
        public int getVersion() {
            return version;
        }

        @Override
        public String getResourceFolderName() {
            if (resourceFolderName != null) {
                return resourceFolderName;
            }
            return BaseService.super.getResourceFolderName();
        }
    }

    @Nested
    @DisplayName("getName 测试")
    class GetNameTests {

        @Test
        @DisplayName("应该返回正确的服务名称")
        void shouldReturnCorrectName() {
            // Arrange
            BaseService service = new TestBaseService("TestService", "TestAuthor", 1);

            // Act
            String name = service.getName();

            // Assert
            assertThat(name).isEqualTo("TestService");
        }

        @Test
        @DisplayName("应该支持中文服务名称")
        void shouldSupportChineseName() {
            // Arrange
            BaseService service = new TestBaseService("测试服务", "测试作者", 1);

            // Act
            String name = service.getName();

            // Assert
            assertThat(name).isEqualTo("测试服务");
        }

        @Test
        @DisplayName("应该支持空字符串名称")
        void shouldSupportEmptyName() {
            // Arrange
            BaseService service = new TestBaseService("", "Author", 1);

            // Act
            String name = service.getName();

            // Assert
            assertThat(name).isEmpty();
        }
    }

    @Nested
    @DisplayName("getResourceFolderName 测试")
    class GetResourceFolderNameTests {

        @Test
        @DisplayName("默认实现应该返回服务名称")
        void defaultShouldReturnName() {
            // Arrange
            BaseService service = new TestBaseService("MyService", "Author", 1);

            // Act
            String folderName = service.getResourceFolderName();

            // Assert
            assertThat(folderName).isEqualTo("MyService");
        }

        @Test
        @DisplayName("覆盖实现应该返回自定义值")
        void overriddenShouldReturnCustomValue() {
            // Arrange
            BaseService service = new TestBaseService("ServiceName", "Author", 1, "custom-folder");

            // Act
            String folderName = service.getResourceFolderName();

            // Assert
            assertThat(folderName).isEqualTo("custom-folder");
        }
    }

    @Nested
    @DisplayName("getAuthor 测试")
    class GetAuthorTests {

        @Test
        @DisplayName("应该返回正确的作者名称")
        void shouldReturnCorrectAuthor() {
            // Arrange
            BaseService service = new TestBaseService("Service", "John Doe", 1);

            // Act
            String author = service.getAuthor();

            // Assert
            assertThat(author).isEqualTo("John Doe");
        }
    }

    @Nested
    @DisplayName("getVersion 测试")
    class GetVersionTests {

        @Test
        @DisplayName("应该返回正确的版本号")
        void shouldReturnCorrectVersion() {
            // Arrange
            BaseService service = new TestBaseService("Service", "Author", 42);

            // Act
            int version = service.getVersion();

            // Assert
            assertThat(version).isEqualTo(42);
        }

        @Test
        @DisplayName("应该支持版本号为0")
        void shouldSupportVersionZero() {
            // Arrange
            BaseService service = new TestBaseService("Service", "Author", 0);

            // Act
            int version = service.getVersion();

            // Assert
            assertThat(version).isZero();
        }

        @Test
        @DisplayName("应该支持负版本号")
        void shouldSupportNegativeVersion() {
            // Arrange
            BaseService service = new TestBaseService("Service", "Author", -1);

            // Act
            int version = service.getVersion();

            // Assert
            assertThat(version).isEqualTo(-1);
        }
    }
}
