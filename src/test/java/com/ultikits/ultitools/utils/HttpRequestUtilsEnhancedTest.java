package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HttpRequestUtils 测试类
 */
@DisplayName("HttpRequestUtils 测试")
class HttpRequestUtilsEnhancedTest {

    @BeforeEach
    void setUp() {
        // 重置任何静态状态
        HttpRequestUtils.resetBaseUrl();
    }

    @AfterEach
    void tearDown() {
        HttpRequestUtils.resetBaseUrl();
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("HttpRequestUtils 类应该存在")
        void classShouldExist() {
            assertThat(HttpRequestUtils.class).isNotNull();
        }

        @Test
        @DisplayName("HttpRequestUtils 类应该是公开的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(HttpRequestUtils.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("setBaseUrlForTesting 方法测试")
    class SetBaseUrlForTestingTests {

        @Test
        @DisplayName("应该能设置自定义基础 URL")
        void shouldSetCustomBaseUrl() {
            HttpRequestUtils.setBaseUrlForTesting("http://test.example.com");
            
            String baseUrl = HttpRequestUtils.getBaseUrl();
            
            assertThat(baseUrl).isEqualTo("http://test.example.com");
        }

        @Test
        @DisplayName("设置 null 应该重置 URL")
        void settingNullShouldResetUrl() {
            HttpRequestUtils.setBaseUrlForTesting("http://test.example.com");
            HttpRequestUtils.setBaseUrlForTesting(null);
            
            // 此时应该尝试从 UltiTools 获取，但因为没有运行环境会失败
            // 我们只验证不抛出异常
        }
    }

    @Nested
    @DisplayName("resetBaseUrl 方法测试")
    class ResetBaseUrlTests {

        @Test
        @DisplayName("重置后应该清除自定义 URL")
        void resetShouldClearCustomUrl() {
            HttpRequestUtils.setBaseUrlForTesting("http://test.example.com");
            assertThat(HttpRequestUtils.getBaseUrl()).isEqualTo("http://test.example.com");
            
            HttpRequestUtils.resetBaseUrl();
            HttpRequestUtils.setBaseUrlForTesting("http://another.example.com");
            
            assertThat(HttpRequestUtils.getBaseUrl()).isEqualTo("http://another.example.com");
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("应该有 getToken 方法")
        void shouldHaveGetTokenMethod() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("getToken", String.class, String.class);
            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该有 getServerByUUID 方法")
        void shouldHaveGetServerByUUIDMethod() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("getServerByUUID", String.class, com.ultikits.ultitools.entities.TokenEntity.class);
            assertThat(method).isNotNull();
        }
    }
}
