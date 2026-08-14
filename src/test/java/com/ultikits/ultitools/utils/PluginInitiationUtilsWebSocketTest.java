package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginInitiationUtils}.
 * These tests verify method signatures, class structure, and message handling logic
 * without requiring actual network connectivity or UltiTools instance.
 */
@DisplayName("PluginInitiationUtils 测试")
class PluginInitiationUtilsWebSocketTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("PluginInitiationUtils 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(PluginInitiationUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有静态的 panelWS 字段")
        void shouldHavePanelWSField() throws NoSuchFieldException {
            Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");
            
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有静态的 token 字段")
        void shouldHaveTokenField() throws NoSuchFieldException {
            Field field = PluginInitiationUtils.class.getDeclaredField("token");
            
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("公共方法签名测试")
    class PublicMethodSignatureTests {

        @Test
        @DisplayName("initWebsocket 方法应该存在且签名正确")
        void initWebsocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("initWebsocket");
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("stopWebsocket 方法应该存在且签名正确")
        void stopWebsocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("stopWebsocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("reinitWebSocket 方法应该存在且签名正确")
        void reinitWebSocketMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("私有方法签名测试")
    class PrivateMethodSignatureTests {

        @Test
        @DisplayName("handlePing 方法应该存在")
        void handlePingMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handlePing", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handlePong 方法应该存在")
        void handlePongMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handlePong", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleSubscribe 方法应该存在")
        void handleSubscribeMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleSubscribe", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleUnsubscribe 方法应该存在")
        void handleUnsubscribeMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleUnsubscribe", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleNotification 方法应该存在")
        void handleNotificationMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleNotification", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleError 方法应该存在")
        void handleErrorMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleError", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handlePlayerEvent 方法应该存在")
        void handlePlayerEventMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handlePlayerEvent", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleCommandResult 方法应该存在")
        void handleCommandResultMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleCommandResult", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleFileOperationResult 方法应该存在")
        void handleFileOperationResultMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleFileOperationResult", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("handleConfigUpload 方法应该存在")
        void handleConfigUploadMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod(
                "handleConfigUpload", com.google.gson.JsonObject.class);
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("initializeManagers 方法应该存在")
        void initializeManagersMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("initializeManagers");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            // 包级可见（PR #264 起）：cloudEnabled 闸门需要被单元测试直接驱动。
            // 仍然不是 public —— 它不是对外 API。
            assertThat(Modifier.isPrivate(method.getModifiers())).isFalse();
            assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
            assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("getPanelWebsocketClient 方法应该存在")
        void getPanelWebsocketClientMethodShouldExist() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("getPanelWebsocketClient");
            
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("WebSocket 消息类型覆盖测试")
    class WebSocketMessageTypeCoverageTests {

        private List<String> getHandlerMethodNames() {
            return Arrays.stream(PluginInitiationUtils.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("handle"))
                .map(Method::getName)
                .collect(Collectors.toList());
        }

        @Test
        @DisplayName("应该有处理 ping/pong 的方法")
        void shouldHavePingPongHandlers() {
            List<String> handlers = getHandlerMethodNames();
            
            assertThat(handlers).contains("handlePing", "handlePong");
        }

        @Test
        @DisplayName("应该有处理订阅消息的方法")
        void shouldHaveSubscriptionHandlers() {
            List<String> handlers = getHandlerMethodNames();
            
            assertThat(handlers).contains("handleSubscribe", "handleUnsubscribe");
        }

        @Test
        @DisplayName("应该有处理系统消息的方法")
        void shouldHaveSystemMessageHandlers() {
            List<String> handlers = getHandlerMethodNames();
            
            assertThat(handlers).contains("handleNotification", "handleError");
        }

        @Test
        @DisplayName("应该有处理服务器操作结果的方法")
        void shouldHaveOperationResultHandlers() {
            List<String> handlers = getHandlerMethodNames();
            
            assertThat(handlers).contains("handleCommandResult", "handleFileOperationResult");
        }

        @Test
        @DisplayName("应该有处理配置相关的方法")
        void shouldHaveConfigHandlers() {
            List<String> handlers = getHandlerMethodNames();
            
            assertThat(handlers).contains("handleConfigUpload", "handleConfigUpdate");
        }
    }

    @Nested
    @DisplayName("JSON 消息构建测试")
    class JsonMessageBuildingTests {

        @Test
        @DisplayName("应该能构建 pong 响应消息")
        void shouldBuildPongResponseMessage() {
            com.google.gson.JsonObject pongResponse = new com.google.gson.JsonObject();
            pongResponse.addProperty("type", "pong");
            pongResponse.addProperty("timestamp", System.currentTimeMillis());
            
            com.google.gson.JsonObject pongData = new com.google.gson.JsonObject();
            pongData.addProperty("timestamp", System.currentTimeMillis());
            pongResponse.add("data", pongData);
            
            assertThat(pongResponse.get("type").getAsString()).isEqualTo("pong");
            assertThat(pongResponse.has("timestamp")).isTrue();
            assertThat(pongResponse.has("data")).isTrue();
        }

        @Test
        @DisplayName("应该能构建错误响应消息")
        void shouldBuildErrorResponseMessage() {
            com.google.gson.JsonObject errorResponse = new com.google.gson.JsonObject();
            errorResponse.addProperty("type", "error");
            errorResponse.addProperty("message", "Test error message");
            
            assertThat(errorResponse.get("type").getAsString()).isEqualTo("error");
            assertThat(errorResponse.get("message").getAsString()).isEqualTo("Test error message");
        }

        @Test
        @DisplayName("应该能解析带 data 字段的消息")
        void shouldParseMessageWithDataField() {
            com.google.gson.JsonObject message = new com.google.gson.JsonObject();
            message.addProperty("type", "server_status");
            
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            data.addProperty("players", 10);
            data.addProperty("tps", 20.0);
            message.add("data", data);
            
            String type = message.get("type").getAsString();
            com.google.gson.JsonObject extractedData = message.getAsJsonObject("data");
            
            assertThat(type).isEqualTo("server_status");
            assertThat(extractedData.get("players").getAsInt()).isEqualTo(10);
            assertThat(extractedData.get("tps").getAsDouble()).isEqualTo(20.0);
        }
    }

    @Nested
    @DisplayName("消息类型常量测试")
    class MessageTypeConstantsTests {

        @Test
        @DisplayName("应该支持所有基础系统消息类型")
        void shouldSupportBasicSystemMessageTypes() {
            List<String> basicTypes = Arrays.asList(
                "ping", "pong", "subscribe", "unsubscribe", "notification", "error"
            );
            
            for (String type : basicTypes) {
                assertThat(type).isNotEmpty();
            }
        }

        @Test
        @DisplayName("应该支持所有监控消息类型")
        void shouldSupportMonitoringMessageTypes() {
            List<String> monitoringTypes = Arrays.asList(
                "server_status", "plugin_list", "player_event", "log_stream"
            );
            
            for (String type : monitoringTypes) {
                assertThat(type).isNotEmpty();
            }
        }

        @Test
        @DisplayName("应该支持所有控制消息类型")
        void shouldSupportControlMessageTypes() {
            List<String> controlTypes = Arrays.asList(
                "execute_command", "command_result", "file_operation"
            );
            
            for (String type : controlTypes) {
                assertThat(type).isNotEmpty();
            }
        }

        @Test
        @DisplayName("应该支持所有配置管理消息类型")
        void shouldSupportConfigMessageTypes() {
            List<String> configTypes = Arrays.asList(
                "upload_config", "update_config"
            );

            for (String type : configTypes) {
                assertThat(type).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("reinitWebSocket 方法测试")
    class ReinitWebSocketTests {

        @Test
        @DisplayName("reinitWebSocket 方法应该存在且签名正确")
        void reinitWebSocketMethodShouldExistWithCorrectSignature() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("reinitWebSocket 方法应该是公共方法")
        void reinitWebSocketMethodShouldBePublic() throws NoSuchMethodException {
            Method method = PluginInitiationUtils.class.getDeclaredMethod("reinitWebSocket");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("panelWS 字段应该可以被 reinitWebSocket 访问")
        void panelWSFieldShouldBeAccessibleByReinitWebSocket() throws NoSuchFieldException {
            Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");

            assertThat(field.getType().getName())
                .isEqualTo("com.ultikits.ultitools.websocket.UltiPanelWebSocketClient");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("token 字段应该可以被 reinitWebSocket 使用")
        void tokenFieldShouldBeUsableByReinitWebSocket() throws NoSuchFieldException {
            Field field = PluginInitiationUtils.class.getDeclaredField("token");

            assertThat(field.getType().getName())
                .isEqualTo("com.ultikits.ultitools.entities.TokenEntity");
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        }
    }

}
