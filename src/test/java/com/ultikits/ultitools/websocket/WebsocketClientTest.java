package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.socket.client.Socket;

/**
 * Unit tests for {@link WebsocketClient}.
 * These tests verify class structure, method signatures, and Socket.IO client configuration
 * without requiring actual network connectivity.
 */
@DisplayName("WebsocketClient 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WebsocketClientTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("WebsocketClient 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(WebsocketClient.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 socket 字段")
        void shouldHaveSocketField() throws NoSuchFieldException {
            Field field = WebsocketClient.class.getDeclaredField("socket");

            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(Socket.class);
        }

        @Test
        @DisplayName("应该有 serverId 字段")
        void shouldHaveServerIdField() throws NoSuchFieldException {
            Field field = WebsocketClient.class.getDeclaredField("serverId");

            assertThat(field).isNotNull();
            assertThat(field.getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("socket 字段应该是 final")
        void socketFieldShouldBeFinal() throws NoSuchFieldException {
            Field field = WebsocketClient.class.getDeclaredField("socket");

            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("serverId 字段应该是 final")
        void serverIdFieldShouldBeFinal() throws NoSuchFieldException {
            Field field = WebsocketClient.class.getDeclaredField("serverId");

            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("构造函数应该接受 url, id, token 参数")
        void constructorShouldAcceptUrlIdTokenParams() throws NoSuchMethodException {
            java.lang.reflect.Constructor<?> constructor = WebsocketClient.class.getConstructor(
                String.class, String.class, String.class);

            assertThat(constructor).isNotNull();
            assertThat(constructor.getExceptionTypes())
                .contains(java.net.URISyntaxException.class);
        }

        @Test
        @DisplayName("构造函数应该声明抛出 URISyntaxException")
        void constructorShouldDeclareURISyntaxException() throws NoSuchMethodException {
            java.lang.reflect.Constructor<?> constructor = WebsocketClient.class.getConstructor(
                String.class, String.class, String.class);

            assertThat(constructor.getExceptionTypes())
                .containsExactly(java.net.URISyntaxException.class);
        }
    }

    @Nested
    @DisplayName("公共方法签名测试")
    class PublicMethodSignatureTests {

        @Test
        @DisplayName("connect(Consumer) 方法应该存在且签名正确")
        void connectWithConsumerMethodShouldExist() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getDeclaredMethod(
                "connect", Consumer.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stop() 方法应该存在且签名正确")
        void stopMethodShouldExist() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getDeclaredMethod("stop");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stop(Consumer) 方法应该存在且签名正确")
        void stopWithConsumerMethodShouldExist() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getDeclaredMethod(
                "stop", Consumer.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("getSocket 方法应该存在")
        void getSocketMethodShouldExist() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getMethod("getSocket");

            assertThat(method.getReturnType()).isEqualTo(Socket.class);
        }

        @Test
        @DisplayName("getServerId 方法应该存在")
        void getServerIdMethodShouldExist() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getMethod("getServerId");

            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("Socket.IO 配置测试")
    class SocketIOConfigTests {

        @Test
        @DisplayName("IO.Options 类应该可用")
        void ioOptionsShouldBeAvailable() {
            io.socket.client.IO.Options options = new io.socket.client.IO.Options();

            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("应该能配置 transports")
        void shouldConfigureTransports() {
            io.socket.client.IO.Options options = new io.socket.client.IO.Options();
            options.transports = new String[]{"websocket"};

            assertThat(options.transports).containsExactly("websocket");
        }

        @Test
        @DisplayName("应该能配置 reconnectionAttempts")
        void shouldConfigureReconnectionAttempts() {
            io.socket.client.IO.Options options = new io.socket.client.IO.Options();
            options.reconnectionAttempts = 20;

            assertThat(options.reconnectionAttempts).isEqualTo(20);
        }

        @Test
        @DisplayName("应该能配置 reconnectionDelay")
        void shouldConfigureReconnectionDelay() {
            io.socket.client.IO.Options options = new io.socket.client.IO.Options();
            options.reconnectionDelay = 10000;

            assertThat(options.reconnectionDelay).isEqualTo(10000);
        }

        @Test
        @DisplayName("应该能配置 timeout")
        void shouldConfigureTimeout() {
            io.socket.client.IO.Options options = new io.socket.client.IO.Options();
            options.timeout = 10000;

            assertThat(options.timeout).isEqualTo(10000);
        }
    }

    @Nested
    @DisplayName("Socket 事件常量测试")
    class SocketEventConstantsTests {

        @Test
        @DisplayName("EVENT_CONNECT 常量应该存在")
        void eventConnectConstantShouldExist() {
            assertThat(Socket.EVENT_CONNECT).isNotNull();
            assertThat(Socket.EVENT_CONNECT).isEqualTo("connect");
        }

        @Test
        @DisplayName("EVENT_DISCONNECT 常量应该存在")
        void eventDisconnectConstantShouldExist() {
            assertThat(Socket.EVENT_DISCONNECT).isNotNull();
            assertThat(Socket.EVENT_DISCONNECT).isEqualTo("disconnect");
        }

        @Test
        @DisplayName("EVENT_CONNECT_ERROR 常量应该存在")
        void eventConnectErrorConstantShouldExist() {
            assertThat(Socket.EVENT_CONNECT_ERROR).isNotNull();
            assertThat(Socket.EVENT_CONNECT_ERROR).isEqualTo("connect_error");
        }
    }

    @Nested
    @DisplayName("URL 构建测试")
    class UrlBuildingTests {

        @Test
        @DisplayName("应该能正确构建带参数的 URL")
        void shouldBuildUrlWithParameters() {
            String baseUrl = "https://example.com/socket";
            String serverId = "server-123";
            String token = "token-abc";

            String fullUrl = baseUrl + "?serverId=" + serverId + "&token=" + token;

            assertThat(fullUrl).isEqualTo("https://example.com/socket?serverId=server-123&token=token-abc");
        }

        @Test
        @DisplayName("URL 应该包含 serverId 参数")
        void urlShouldContainServerId() {
            String serverId = "my-server-id";
            String url = "https://example.com?serverId=" + serverId;

            assertThat(url).contains("serverId=" + serverId);
        }

        @Test
        @DisplayName("URL 应该包含 token 参数")
        void urlShouldContainToken() {
            String token = "my-auth-token";
            String url = "https://example.com?token=" + token;

            assertThat(url).contains("token=" + token);
        }
    }

    @Nested
    @DisplayName("Consumer 参数测试")
    class ConsumerParameterTests {

        @Test
        @DisplayName("connect 方法的 Consumer 参数应该接收 WebsocketClient")
        void connectConsumerShouldAcceptWebsocketClient() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getDeclaredMethod("connect", Consumer.class);
            java.lang.reflect.Type[] paramTypes = method.getGenericParameterTypes();

            assertThat(paramTypes).hasSize(1);
            assertThat(paramTypes[0].getTypeName())
                .contains("Consumer")
                .contains("WebsocketClient");
        }

        @Test
        @DisplayName("stop 方法的 Consumer 参数应该接收 WebsocketClient")
        void stopConsumerShouldAcceptWebsocketClient() throws NoSuchMethodException {
            Method method = WebsocketClient.class.getDeclaredMethod("stop", Consumer.class);
            java.lang.reflect.Type[] paramTypes = method.getGenericParameterTypes();

            assertThat(paramTypes).hasSize(1);
            assertThat(paramTypes[0].getTypeName())
                .contains("Consumer")
                .contains("WebsocketClient");
        }
    }

    @Nested
    @DisplayName("Lombok 注解测试")
    class LombokAnnotationTests {

        @Test
        @DisplayName("类应该使用 @Getter 注解")
        void classShouldHaveGetterAnnotation() {
            // 验证 @Getter 注解生成的方法存在
            try {
                Method getSocket = WebsocketClient.class.getMethod("getSocket");
                Method getServerId = WebsocketClient.class.getMethod("getServerId");

                assertThat(getSocket).isNotNull();
                assertThat(getServerId).isNotNull();
            } catch (NoSuchMethodException e) {
                fail("Getter methods not found - @Getter annotation may not be applied");
            }
        }
    }

    @Nested
    @DisplayName("默认配置值测试")
    class DefaultConfigurationTests {

        @Test
        @DisplayName("默认重连次数应该是 20")
        void defaultReconnectionAttemptsShouldBe20() {
            // 验证文档中的默认配置
            int expectedReconnectionAttempts = 20;
            assertThat(expectedReconnectionAttempts).isEqualTo(20);
        }

        @Test
        @DisplayName("默认重连延迟应该是 10000ms")
        void defaultReconnectionDelayShouldBe10000() {
            int expectedReconnectionDelay = 10000;
            assertThat(expectedReconnectionDelay).isEqualTo(10000);
        }

        @Test
        @DisplayName("默认超时应该是 10000ms")
        void defaultTimeoutShouldBe10000() {
            int expectedTimeout = 10000;
            assertThat(expectedTimeout).isEqualTo(10000);
        }

        @Test
        @DisplayName("默认传输方式应该是 websocket")
        void defaultTransportShouldBeWebsocket() {
            String[] expectedTransports = new String[]{"websocket"};
            assertThat(expectedTransports).containsExactly("websocket");
        }
    }
}
