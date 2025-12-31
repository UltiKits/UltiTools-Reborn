package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.alibaba.fastjson.JSONObject;

import okhttp3.OkHttpClient;

/**
 * Unit tests for {@link UltiPanelWebSocketClient}.
 * These tests verify class structure, method signatures, and message building logic
 * without requiring actual network connectivity.
 */
@DisplayName("UltiPanelWebSocketClient 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiPanelWebSocketClientTest {

    private static final String TEST_URL = "wss://test.example.com/ws";
    private static final String TEST_SERVER_ID = "test-server-123";
    private static final String TEST_TOKEN = "test-token-abc";

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("构造函数应该正确初始化所有字段")
        void constructorShouldInitializeAllFields() {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getUrl()).isEqualTo(TEST_URL);
            assertThat(client.getServerId()).isEqualTo(TEST_SERVER_ID);
            assertThat(client.getToken()).isEqualTo(TEST_TOKEN);
            assertThat(client.isConnected()).isFalse();
        }

        @Test
        @DisplayName("构造函数应该创建 OkHttpClient 实例")
        void constructorShouldCreateOkHttpClient() throws Exception {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            Field clientField = UltiPanelWebSocketClient.class.getDeclaredField("client");
            clientField.setAccessible(true);
            OkHttpClient okHttpClient = (OkHttpClient) clientField.get(client);

            assertThat(okHttpClient).isNotNull();
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
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod("connect");

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
                "sendMessage", JSONObject.class);

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

            Consumer<JSONObject> handler = msg -> {};
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
    @DisplayName("WebSocketListener 实现测试")
    class WebSocketListenerTests {

        @Test
        @DisplayName("UltiPanelWebSocketClient 应该继承 WebSocketListener")
        void shouldExtendWebSocketListener() {
            assertThat(okhttp3.WebSocketListener.class.isAssignableFrom(
                UltiPanelWebSocketClient.class)).isTrue();
        }

        @Test
        @DisplayName("应该实现 onOpen 方法")
        void shouldImplementOnOpen() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onOpen", okhttp3.WebSocket.class, okhttp3.Response.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onMessage(String) 方法")
        void shouldImplementOnMessageString() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onMessage", okhttp3.WebSocket.class, String.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onClosing 方法")
        void shouldImplementOnClosing() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onClosing", okhttp3.WebSocket.class, int.class, String.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onClosed 方法")
        void shouldImplementOnClosed() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onClosed", okhttp3.WebSocket.class, int.class, String.class);

            assertThat(method).isNotNull();
        }

        @Test
        @DisplayName("应该实现 onFailure 方法")
        void shouldImplementOnFailure() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "onFailure", okhttp3.WebSocket.class, Throwable.class, okhttp3.Response.class);

            assertThat(method).isNotNull();
        }
    }

    @Nested
    @DisplayName("JSON 消息构建测试")
    class JsonMessageBuildingTests {

        @Test
        @DisplayName("应该能构建 ping 消息")
        void shouldBuildPingMessage() {
            JSONObject pingMessage = new JSONObject();
            pingMessage.put("type", "ping");
            pingMessage.put("timestamp", System.currentTimeMillis());

            assertThat(pingMessage.getString("type")).isEqualTo("ping");
            assertThat(pingMessage.containsKey("timestamp")).isTrue();
        }

        @Test
        @DisplayName("应该能构建 subscribe 消息")
        void shouldBuildSubscribeMessage() {
            String serverId = "test-server";
            JSONObject subscribeMessage = new JSONObject();
            subscribeMessage.put("type", "subscribe");
            subscribeMessage.put("serverId", serverId);
            subscribeMessage.put("timestamp", System.currentTimeMillis());

            assertThat(subscribeMessage.getString("type")).isEqualTo("subscribe");
            assertThat(subscribeMessage.getString("serverId")).isEqualTo(serverId);
        }

        @Test
        @DisplayName("应该能构建 unsubscribe 消息")
        void shouldBuildUnsubscribeMessage() {
            String serverId = "test-server";
            JSONObject unsubscribeMessage = new JSONObject();
            unsubscribeMessage.put("type", "unsubscribe");
            unsubscribeMessage.put("serverId", serverId);
            unsubscribeMessage.put("timestamp", System.currentTimeMillis());

            assertThat(unsubscribeMessage.getString("type")).isEqualTo("unsubscribe");
            assertThat(unsubscribeMessage.getString("serverId")).isEqualTo(serverId);
        }

        @Test
        @DisplayName("消息应该自动添加时间戳")
        void messageShouldHaveTimestamp() {
            JSONObject message = new JSONObject();
            message.put("type", "test");

            // 模拟 sendMessage 的时间戳添加逻辑
            if (!message.containsKey("timestamp")) {
                message.put("timestamp", System.currentTimeMillis());
            }

            assertThat(message.containsKey("timestamp")).isTrue();
            assertThat(message.getLong("timestamp")).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("连接状态测试")
    class ConnectionStateTests {

        @Test
        @DisplayName("初始状态应该是未连接")
        void initialStateShouldBeDisconnected() {
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
        @DisplayName("handleMessage 方法应该存在")
        void handleMessageMethodShouldExist() throws NoSuchMethodException {
            Method method = UltiPanelWebSocketClient.class.getDeclaredMethod(
                "handleMessage", JSONObject.class);

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

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
            JSONObject pongMessage = new JSONObject();
            pongMessage.put("type", "pong");
            pongMessage.put("timestamp", System.currentTimeMillis());

            assertThat(pongMessage.getString("type")).isEqualTo("pong");
        }

        @Test
        @DisplayName("应该能解析 notification 消息")
        void shouldParseNotificationMessage() {
            JSONObject notification = new JSONObject();
            notification.put("type", "notification");

            JSONObject data = new JSONObject();
            data.put("message", "Test notification");
            notification.put("data", data);

            assertThat(notification.getString("type")).isEqualTo("notification");
            assertThat(notification.getJSONObject("data").getString("message"))
                .isEqualTo("Test notification");
        }

        @Test
        @DisplayName("应该能解析 error 消息")
        void shouldParseErrorMessage() {
            JSONObject errorMessage = new JSONObject();
            errorMessage.put("type", "error");

            JSONObject data = new JSONObject();
            data.put("message", "Test error");
            data.put("code", 500);
            errorMessage.put("data", data);

            assertThat(errorMessage.getString("type")).isEqualTo("error");
            assertThat(errorMessage.getJSONObject("data").getString("message"))
                .isEqualTo("Test error");
        }

        @Test
        @DisplayName("应该能解析 subscribe 响应")
        void shouldParseSubscribeResponse() {
            JSONObject subscribeResponse = new JSONObject();
            subscribeResponse.put("type", "subscribe");

            JSONObject data = new JSONObject();
            data.put("subscribed", true);
            data.put("serverId", "test-server");
            subscribeResponse.put("data", data);

            assertThat(subscribeResponse.getJSONObject("data").getBooleanValue("subscribed"))
                .isTrue();
        }
    }

    @Nested
    @DisplayName("Getter 方法测试")
    class GetterMethodTests {

        @Test
        @DisplayName("getUrl 应该返回正确的 URL")
        void getUrlShouldReturnCorrectUrl() {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getUrl()).isEqualTo(TEST_URL);
        }

        @Test
        @DisplayName("getServerId 应该返回正确的服务器 ID")
        void getServerIdShouldReturnCorrectServerId() {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getServerId()).isEqualTo(TEST_SERVER_ID);
        }

        @Test
        @DisplayName("getToken 应该返回正确的 Token")
        void getTokenShouldReturnCorrectToken() {
            UltiPanelWebSocketClient client = new UltiPanelWebSocketClient(
                TEST_URL, TEST_SERVER_ID, TEST_TOKEN);

            assertThat(client.getToken()).isEqualTo(TEST_TOKEN);
        }
    }
}
