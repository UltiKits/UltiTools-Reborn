package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;
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

    /**
     * 6.3.1 (plan 22-25): the three protected v1 REST calls are refused without a request. A local
     * HTTP server stands in for the cloud, so "no request is made" is observed on the wire.
     */
    @Nested
    @DisplayName("6.3.1: the v1 REST calls are refused without a request")
    @SuppressWarnings("removal") // drives the deprecated v1 members on purpose
    class V1RestCallsRefusedTests {

        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger notices = Logger.getLogger(HttpRequestUtils.class.getName());
        private final Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        private HttpServer cloud;

        @BeforeEach
        void setUp() throws IOException {
            HttpRequestUtils.resetV1NoticeForTesting();
            notices.addHandler(capture);
            cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            cloud.createContext("/", exchange -> {
                try {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                    exchange.sendResponseHeaders(200, -1);
                } finally {
                    exchange.close();
                }
            });
            cloud.start();
            HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + cloud.getAddress().getPort());
        }

        @AfterEach
        void tearDown() {
            notices.removeHandler(capture);
            cloud.stop(0);
            HttpRequestUtils.resetBaseUrl();
            HttpRequestUtils.resetV1NoticeForTesting();
        }

        @Test
        @DisplayName("each call returns a failure response, reaches no server and logs one INFO line per run")
        void eachCallFailsWithoutARequestAndLogsOnce() {
            TokenEntity token = new TokenEntity();
            token.setAccess_token("access");
            token.setUser_id(7L);

            SimpleHttpClient.Response[] responses = {
                HttpRequestUtils.getServerByUUID("uuid", token),
                HttpRequestUtils.registerServer("uuid", "name", 25565, "example.org", false, token),
                HttpRequestUtils.updateServer("uuid", 25565, "example.org", false, token),
                // A second round: the 6.3.0 rate limiter no longer throws, and nothing more is logged.
                HttpRequestUtils.getServerByUUID("uuid", token),
                HttpRequestUtils.registerServer("uuid", "name", 25565, "example.org", false, token),
                HttpRequestUtils.updateServer("uuid", 25565, "example.org", false, token),
            };

            for (SimpleHttpClient.Response response : responses) {
                assertThat(response.isOk()).isFalse();
                assertThat(response.getStatus()).as("no HTTP exchange happened").isEqualTo(-1);
                assertThat(response.getBody()).contains("protocol v1");
            }
            assertThat(requests).as("requests that reached the stand-in cloud").isEmpty();
            assertThat(records).as("one INFO line per member and run").hasSize(3);
            for (LogRecord record : records) {
                assertThat(record.getLevel()).isEqualTo(Level.INFO);
                assertThat(record.getMessage()).contains("protocol v1").contains("6.4.0");
            }
        }
    }
}
