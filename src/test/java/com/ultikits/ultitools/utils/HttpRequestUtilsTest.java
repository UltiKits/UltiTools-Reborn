package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * HttpRequestUtils 测试类
 * 测试 HTTP 请求工具类的方法签名和结构
 */
@DisplayName("HttpRequestUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HttpRequestUtilsTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(HttpRequestUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("类应该不是抽象的")
        void classShouldNotBeAbstract() {
            assertThat(Modifier.isAbstract(HttpRequestUtils.class.getModifiers())).isFalse();
        }
    }

    @Nested
    @DisplayName("Base URL 配置测试")
    class BaseUrlConfigurationTests {

        @Test
        @DisplayName("setBaseUrlForTesting方法应该存在")
        void setBaseUrlForTestingMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("setBaseUrlForTesting", String.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("resetBaseUrl方法应该存在")
        void resetBaseUrlMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("resetBaseUrl");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getBaseUrl方法应该存在")
        void getBaseUrlMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("getBaseUrl");
            assertThat(method).isNotNull();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }
}
