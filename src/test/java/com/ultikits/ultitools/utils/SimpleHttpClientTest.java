package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SimpleHttpClient 测试类
 */
@DisplayName("SimpleHttpClient 测试")
class SimpleHttpClientTest {

    @Nested
    @DisplayName("Response 内部类测试")
    class ResponseTests {

        @Test
        @DisplayName("应该正确创建 Response 对象")
        void shouldCreateResponseCorrectly() {
            SimpleHttpClient.Response response = new SimpleHttpClient.Response(200, "OK");
            
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("OK");
            assertThat(response.body()).isEqualTo("OK");
        }

        @Test
        @DisplayName("isOk 应该对 2xx 状态码返回 true")
        void isOkShouldReturnTrueFor2xxStatus() {
            assertThat(new SimpleHttpClient.Response(200, "").isOk()).isTrue();
            assertThat(new SimpleHttpClient.Response(201, "").isOk()).isTrue();
            assertThat(new SimpleHttpClient.Response(204, "").isOk()).isTrue();
            assertThat(new SimpleHttpClient.Response(299, "").isOk()).isTrue();
        }

        @Test
        @DisplayName("isOk 应该对非 2xx 状态码返回 false")
        void isOkShouldReturnFalseForNon2xxStatus() {
            assertThat(new SimpleHttpClient.Response(100, "").isOk()).isFalse();
            assertThat(new SimpleHttpClient.Response(199, "").isOk()).isFalse();
            assertThat(new SimpleHttpClient.Response(300, "").isOk()).isFalse();
            assertThat(new SimpleHttpClient.Response(400, "").isOk()).isFalse();
            assertThat(new SimpleHttpClient.Response(500, "").isOk()).isFalse();
        }

        @Test
        @DisplayName("close 方法不应该抛出异常")
        void closeShouldNotThrowException() {
            // GATE-06 (issue #345): the previous body called close() with no assertion at all.
            // Response.close() is currently a true no-op (nothing to release, per its javadoc),
            // so this cannot check any state change -- but wrapping the call is still a real check:
            // it names the exact behaviour being verified and fails if AutoCloseable#close is ever
            // given a body that can throw, matching the idiom this file's own sibling assertions
            // (isOkShould...) and ExternalPluginIntegrationTest's doesNotThrowAnyException() cases
            // already use for "must not throw" tests.
            SimpleHttpClient.Response response = new SimpleHttpClient.Response(200, "OK");

            assertThatCode(response::close)
                    .as("Response.close() must not throw")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Response 应该实现 AutoCloseable")
        void responseShouldImplementAutoCloseable() {
            assertThat(AutoCloseable.class.isAssignableFrom(SimpleHttpClient.Response.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("SimpleHttpClient 应该有 get 方法")
        void shouldHaveGetMethod() throws Exception {
            Method getMethod = SimpleHttpClient.class.getMethod("get", String.class);
            assertThat(getMethod).isNotNull();
            assertThat(getMethod.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
        }

        @Test
        @DisplayName("SimpleHttpClient 应该有带 headers 的 get 方法")
        void shouldHaveGetMethodWithHeaders() throws Exception {
            Method getMethod = SimpleHttpClient.class.getMethod("get", String.class, Map.class);
            assertThat(getMethod).isNotNull();
            assertThat(getMethod.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
        }

        @Test
        @DisplayName("SimpleHttpClient 应该有 post 方法（表单数据）")
        void shouldHavePostMethodWithFormData() throws Exception {
            Method postMethod = SimpleHttpClient.class.getMethod("post", String.class, Map.class, Map.class);
            assertThat(postMethod).isNotNull();
            assertThat(postMethod.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
        }

        @Test
        @DisplayName("SimpleHttpClient 应该有 post 方法（JSON body）")
        void shouldHavePostMethodWithJsonBody() throws Exception {
            Method postMethod = SimpleHttpClient.class.getMethod("post", String.class, Map.class, String.class);
            assertThat(postMethod).isNotNull();
            assertThat(postMethod.getReturnType()).isEqualTo(SimpleHttpClient.Response.class);
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("Response 应该处理空 body")
        void responseShouldHandleEmptyBody() {
            SimpleHttpClient.Response response = new SimpleHttpClient.Response(204, "");
            
            assertThat(response.getBody()).isEmpty();
            assertThat(response.body()).isEmpty();
        }

        @Test
        @DisplayName("Response 应该处理 null body")
        void responseShouldHandleNullBody() {
            SimpleHttpClient.Response response = new SimpleHttpClient.Response(204, null);
            
            assertThat(response.getBody()).isNull();
            assertThat(response.body()).isNull();
        }
    }
}
