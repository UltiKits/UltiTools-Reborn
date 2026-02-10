package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.entities.TokenEntity;

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
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getServerByUUID方法应该存在且返回SimpleHttpClient.Response")
        void getServerByUUIDMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "getServerByUUID", String.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
            assertThat(method.getParameterCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("registerServer方法应该存在且返回SimpleHttpClient.Response")
        void registerServerMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "registerServer", String.class, String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
            assertThat(method.getParameterCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("updateServer方法应该存在且返回SimpleHttpClient.Response")
        void updateServerMethodShouldExist() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "updateServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
            assertThat(method.getParameterCount()).isEqualTo(5);
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

    @Nested
    @DisplayName("方法访问修饰符测试")
    class MethodAccessModifierTests {

        @Test
        @DisplayName("getServerByUUID方法应该是protected的")
        void getServerByUUIDShouldBeProtected() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod("getServerByUUID", String.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("registerServer方法应该是protected的")
        void registerServerShouldBeProtected() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "registerServer", String.class, String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("updateServer方法应该是protected的")
        void updateServerShouldBeProtected() throws Exception {
            Method method = HttpRequestUtils.class.getDeclaredMethod(
                "updateServer", String.class, int.class, String.class, boolean.class, TokenEntity.class);
            assertThat(Modifier.isProtected(method.getModifiers())).isTrue();
        }
    }
}
