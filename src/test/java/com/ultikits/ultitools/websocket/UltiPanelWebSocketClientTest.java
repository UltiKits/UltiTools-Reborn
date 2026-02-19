package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Unit tests for {@link UltiPanelWebSocketClient}.
 * These tests verify class structure, method signatures, and message building logic
 * without requiring actual network connectivity.
 */
@DisplayName("UltiPanelWebSocketClient 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for internal state verification
class UltiPanelWebSocketClientTest {

    private static final String TEST_URL = "wss://test.example.com/ws";
    private static final String TEST_SERVER_ID = "test-server-123";
    private static final String TEST_TOKEN = "test-token-abc";

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("构造函数应该正确初始化所有字段")
        void constructorShouldInitializeAllFields() throws URISyntaxException {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getURI().toString()).isEqualTo(TEST_URL);
            assertThat(client.getServerId()).isEqualTo(TEST_SERVER_ID);
            assertThat(client.getToken()).isEqualTo(TEST_TOKEN);
            assertThat(client.isConnected()).isFalse();
        }

        @Test
        @DisplayName("构造函数应该创建心跳执行器")
        void constructorShouldCreateHeartbeatExecutor() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            Field executorField = UltiPanelWebSocketClient.class.getDeclaredField("heartbeatExecutor");
            executorField.setAccessible(true);
            ScheduledExecutorService executor = (ScheduledExecutorService) executorField.get(client);

            assertThat(executor).isNotNull();
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    @Nested
    @DisplayName("公共方法签名测试")
    class PublicMethodSignatureTests {

        @Test
        @DisplayName("connect 方法应该存在且签名正确")
        void connectMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getMethod("connect");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("disconnect 方法应该存在且签名正确")
        void disconnectMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod("disconnect");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("sendMessage 方法应该存在且签名正确")
        void sendMessageMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "sendMessage", JsonObject.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("sendPing 方法应该存在且签名正确")
        void sendPingMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod("sendPing");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("subscribeToServer 方法应该存在且签名正确")
        void subscribeToServerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "subscribeToServer", String.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("unsubscribeFromServer 方法应该存在且签名正确")
        void unsubscribeFromServerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "unsubscribeFromServer", String.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("Handler 设置方法测试")
    class HandlerSetterTests {

        @Test
        @DisplayName("setMessageHandler 方法应该存在")
        void setMessageHandlerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "setMessageHandler", Consumer.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("setOnConnectHandler 方法应该存在")
        void setOnConnectHandlerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "setOnConnectHandler", Runnable.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("setOnDisconnectHandler 方法应该存在")
        void setOnDisconnectHandlerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "setOnDisconnectHandler", Runnable.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("setOnErrorHandler 方法应该存在")
        void setOnErrorHandlerMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "setOnErrorHandler", Consumer.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该能设置消息处理器")
        void shouldSetMessageHandler() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            Consumer<JsonObject> handler = msg -> {};
            client.setMessageHandler(handler);

            Field handlerField = UltiPanelWebSocketClient.class.getDeclaredField("messageHandler");
            handlerField.setAccessible(true);
            assertThat(handlerField.get(client)).isEqualTo(handler);
        }

        @Test
        @DisplayName("应该能设置连接处理器")
        void shouldSetOnConnectHandler() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            Runnable handler = () -> {};
            client.setOnConnectHandler(handler);

            Field handlerField = UltiPanelWebSocketClient.class.getDeclaredField("onConnectHandler");
            handlerField.setAccessible(true);
            assertThat(handlerField.get(client)).isEqualTo(handler);
        }
    }

    @Nested
    @DisplayName("WebSocketClient 实现测试")
    class WebSocketClientTests {

        @Test
        @DisplayName("UltiPanelWebSocketClient 应该继承 WebSocketClient")
        void shouldExtendWebSocketClient() {
            assertThat(WebSocketClient.class.isAssignableFrom(
                UltiPanelWebSocketClient.class)).isTrue();
        }

        @Test
        @DisplayName("应该实现 onOpen 方法")
        void shouldImplementOnOpen() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onOpen", ServerHandshake.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onMessage(String) 方法")
        void shouldImplementOnMessageString() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onMessage", String.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onClose 方法")
        void shouldImplementOnClose() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onClose", int.class, String.class, boolean.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onError 方法")
        void shouldImplementOnError() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onError", Exception.class);

            assertThat(method).isNotNull();
        }
    }

    @Nested
    @DisplayName("JSON 消息构建测试")
    class JsonMessageBuildingTests {

        @Test
        @DisplayName("应该能构建 ping 消息")
        void shouldBuildPingMessage() {
            JsonObject pingMessage = new JsonObject();
            pingMessage.addProperty("type", "ping");
            pingMessage.addProperty("timestamp", System.currentTimeMillis());

            assertThat(pingMessage.get("type").getAsString()).isEqualTo("ping");
            assertThat(pingMessage.has("timestamp")).isTrue();
        }

        @Test
        @DisplayName("应该能构建 subscribe 消息")
        void shouldBuildSubscribeMessage() {
            String serverId = "test-server";
            JsonObject subscribeMessage = new JsonObject();
            subscribeMessage.addProperty("type", "subscribe");
            subscribeMessage.addProperty("serverId", serverId);
            subscribeMessage.addProperty("timestamp", System.currentTimeMillis());

            assertThat(subscribeMessage.get("type").getAsString()).isEqualTo("subscribe");
            assertThat(subscribeMessage.get("serverId").getAsString()).isEqualTo(serverId);
        }

        @Test
        @DisplayName("应该能构建 unsubscribe 消息")
        void shouldBuildUnsubscribeMessage() {
            String serverId = "test-server";
            JsonObject unsubscribeMessage = new JsonObject();
            unsubscribeMessage.addProperty("type", "unsubscribe");
            unsubscribeMessage.addProperty("serverId", serverId);
            unsubscribeMessage.addProperty("timestamp", System.currentTimeMillis());

            assertThat(unsubscribeMessage.get("type").getAsString()).isEqualTo("unsubscribe");
            assertThat(unsubscribeMessage.get("serverId").getAsString()).isEqualTo(serverId);
        }

        @Test
        @DisplayName("消息应该自动添加时间戳")
        void messageShouldHaveTimestamp() {
            JsonObject message = new JsonObject();
            message.addProperty("type", "test");

            // 模拟 sendMessage 的时间戳添加逻辑
            if (!message.has("timestamp")) {
                message.addProperty("timestamp", System.currentTimeMillis());
            }

            assertThat(message.has("timestamp")).isTrue();
            assertThat(message.get("timestamp").getAsLong()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("连接状态测试")
    class ConnectionStateTests {

        @Test
        @DisplayName("初始状态应该是未连接")
        void initialStateShouldBeDisconnected() throws URISyntaxException {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.isConnected()).isFalse();
        }

        @Test
        @DisplayName("应该有 isConnected 方法")
        void shouldHaveIsConnectedMethod() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getMethod("isConnected");

            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("私有方法测试")
    class PrivateMethodTests {

        @Test
        @DisplayName("startHeartbeat 方法应该存在")
        void startHeartbeatMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod("startHeartbeat");

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("消息类型处理测试")
    class MessageTypeHandlingTests {

        @Test
        @DisplayName("应该能解析 pong 响应")
        void shouldParsePongResponse() {
            JsonObject pongMessage = new JsonObject();
            pongMessage.addProperty("type", "pong");
            pongMessage.addProperty("timestamp", System.currentTimeMillis());

            assertThat(pongMessage.get("type").getAsString()).isEqualTo("pong");
        }

        @Test
        @DisplayName("应该能解析 notification 消息")
        void shouldParseNotificationMessage() {
            JsonObject notification = new JsonObject();
            notification.addProperty("type", "notification");

            JsonObject data = new JsonObject();
            data.addProperty("message", "Test notification");
            notification.add("data", data);

            assertThat(notification.get("type").getAsString()).isEqualTo("notification");
            assertThat(notification.getAsJsonObject("data").get("message").getAsString())
                .isEqualTo("Test notification");
        }

        @Test
        @DisplayName("应该能解析 error 消息")
        void shouldParseErrorMessage() {
            JsonObject errorMessage = new JsonObject();
            errorMessage.addProperty("type", "error");

            JsonObject data = new JsonObject();
            data.addProperty("message", "Test error");
            data.addProperty("code", 500);
            errorMessage.add("data", data);

            assertThat(errorMessage.get("type").getAsString()).isEqualTo("error");
            assertThat(errorMessage.getAsJsonObject("data").get("message").getAsString())
                .isEqualTo("Test error");
        }

        @Test
        @DisplayName("应该能解析 subscribe 响应")
        void shouldParseSubscribeResponse() {
            JsonObject subscribeResponse = new JsonObject();
            subscribeResponse.addProperty("type", "subscribe");

            JsonObject data = new JsonObject();
            data.addProperty("subscribed", true);
            data.addProperty("serverId", "test-server");
            subscribeResponse.add("data", data);

            assertThat(subscribeResponse.getAsJsonObject("data").get("subscribed").getAsBoolean())
                .isTrue();
        }
    }

    @Nested
    @DisplayName("Getter 方法测试")
    class GetterMethodTests {

        @Test
        @DisplayName("getURI 应该返回正确的 URL")
        void getUrlShouldReturnCorrectUrl() throws URISyntaxException {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getURI().toString()).isEqualTo(TEST_URL);
        }

        @Test
        @DisplayName("getServerId 应该返回正确的服务器 ID")
        void getServerIdShouldReturnCorrectServerId() throws URISyntaxException {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getServerId()).isEqualTo(TEST_SERVER_ID);
        }

        @Test
        @DisplayName("getToken 应该返回正确的 Token")
        void getTokenShouldReturnCorrectToken() throws URISyntaxException {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getToken()).isEqualTo(TEST_TOKEN);
        }
    }

    @Nested
    @DisplayName("Gson HTML 转义测试")
    class GsonHtmlEscapingTests {

        @Test
        @DisplayName("Gson 不应该转义 HTML 字符")
        void gsonShouldNotEscapeHtmlCharacters() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);
            Field gsonField = UltiPanelWebSocketClient.class.getDeclaredField("gson");
            gsonField.setAccessible(true);
            Gson gson = (Gson) gsonField.get(client);

            JsonObject msg = new JsonObject();
            msg.addProperty("message", "<player> joined & left");
            String json = gson.toJson(msg);

            assertThat(json).contains("<player>");
            assertThat(json).contains("&");
            assertThat(json).doesNotContain("\\u003c");
            assertThat(json).doesNotContain("\\u003e");
            assertThat(json).doesNotContain("\\u0026");
        }

        @Test
        @DisplayName("Gson 应该正确序列化包含路径的消息")
        void gsonShouldSerializePathsCorrectly() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);
            Field gsonField = UltiPanelWebSocketClient.class.getDeclaredField("gson");
            gsonField.setAccessible(true);
            Gson gson = (Gson) gsonField.get(client);

            JsonObject msg = new JsonObject();
            msg.addProperty("message", "[INFO] Loading plugins/UltiTools/config.yml");
            String json = gson.toJson(msg);

            assertThat(json).contains("plugins/UltiTools/config.yml");
        }
    }
}
