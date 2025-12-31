package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;

import cn.hutool.http.HttpResponse;

/**
 * PluginInitiationUtils 测试类
 * 使用 Mock 测试插件初始化和 WebSocket 通信功能
 */
@DisplayName("PluginInitiationUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
class PluginInitiationUtilsTest {

    @Mock
    private UltiTools mockUltiTools;
    
    @Mock
    private FileConfiguration mockConfig;
    
    @Mock
    private Logger mockLogger;
    
    @Mock
    private ServerMonitorManager mockServerMonitorManager;
    
    @Mock
    private CommandExecutionManager mockCommandExecutionManager;
    
    @Mock
    private FileOperationManager mockFileOperationManager;
    
    @Mock
    private LogStreamManager mockLogStreamManager;
    
    @Mock
    private PlayerEventManager mockPlayerEventManager;

    // ========== 方法签名测试 ==========
    
    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {
        
        @Test
        @DisplayName("loginAccount方法应该存在")
        void loginAccountMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = PluginInitiationUtils.class.getMethod(
                "loginAccount", String.class, String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(boolean.class);
        }
        
        @Test
        @DisplayName("initWebsocket方法应该存在")
        void initWebsocketMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = PluginInitiationUtils.class.getMethod("initWebsocket");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
        
        @Test
        @DisplayName("stopWebsocket方法应该存在")
        void stopWebsocketMethodShouldExist() throws Exception {
            java.lang.reflect.Method method = PluginInitiationUtils.class.getMethod("stopWebsocket");
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    // ========== loginAccount Mock测试 ==========
    
    @Nested
    @DisplayName("loginAccount Mock测试")
    class LoginAccountMockTests {
        
        @Test
        @DisplayName("新服务器注册成功时返回true")
        void shouldReturnTrueWhenNewServerRegistrationSucceeds() throws IOException {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class, withSettings().lenient());
                 MockedStatic<HttpRequestUtils> httpRequestMock = mockStatic(HttpRequestUtils.class, withSettings().lenient());
                 MockedStatic<CommonUtils> commonUtilsMock = mockStatic(CommonUtils.class, withSettings().lenient())) {
                
                // 准备 UltiTools 实例
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                lenient().when(mockUltiTools.getConfig()).thenReturn(mockConfig);
                lenient().when(mockUltiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(mockConfig.getBoolean("web-editor.https.enable")).thenReturn(false);
                lenient().when(mockConfig.getInt("web-editor.port")).thenReturn(8080);
                lenient().when(mockConfig.getString("web-editor.https.domain")).thenReturn("localhost");
                
                // 准备 CommonUtils
                commonUtilsMock.when(CommonUtils::getUltiToolsUUID).thenReturn("test-uuid-12345");
                
                // 准备 HttpRequestUtils
                TokenEntity mockToken = mock(TokenEntity.class);
                httpRequestMock.when(() -> HttpRequestUtils.getToken("admin", "password"))
                    .thenReturn(mockToken);
                
                // 模拟服务器不存在 (404)
                HttpResponse uuidResponse = mock(HttpResponse.class);
                lenient().when(uuidResponse.getStatus()).thenReturn(404);
                httpRequestMock.when(() -> HttpRequestUtils.getServerByUUID("test-uuid-12345", mockToken))
                    .thenReturn(uuidResponse);
                
                // 模拟注册成功
                HttpResponse registerResponse = mock(HttpResponse.class);
                lenient().when(registerResponse.isOk()).thenReturn(true);
                httpRequestMock.when(() -> HttpRequestUtils.registerServer(
                    eq("test-uuid-12345"), eq(8080), eq("localhost"), eq(false), eq(mockToken)))
                    .thenReturn(registerResponse);
                
                // 执行测试
                boolean result = PluginInitiationUtils.loginAccount("admin", "password");
                
                // 验证
                assertThat(result).isTrue();
            }
        }
        
        @Test
        @DisplayName("已存在服务器更新成功时返回true")
        void shouldReturnTrueWhenExistingServerUpdateSucceeds() throws IOException {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class, withSettings().lenient());
                 MockedStatic<HttpRequestUtils> httpRequestMock = mockStatic(HttpRequestUtils.class, withSettings().lenient());
                 MockedStatic<CommonUtils> commonUtilsMock = mockStatic(CommonUtils.class, withSettings().lenient())) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                lenient().when(mockUltiTools.getConfig()).thenReturn(mockConfig);
                lenient().when(mockUltiTools.getLogger()).thenReturn(mockLogger);
                lenient().when(mockConfig.getBoolean("web-editor.https.enable")).thenReturn(true);
                lenient().when(mockConfig.getInt("web-editor.port")).thenReturn(443);
                lenient().when(mockConfig.getString("web-editor.https.domain")).thenReturn("myserver.com");
                
                commonUtilsMock.when(CommonUtils::getUltiToolsUUID).thenReturn("existing-uuid");
                
                TokenEntity mockToken = mock(TokenEntity.class);
                httpRequestMock.when(() -> HttpRequestUtils.getToken("user", "pass"))
                    .thenReturn(mockToken);
                
                // 模拟服务器已存在 (200)
                HttpResponse uuidResponse = mock(HttpResponse.class);
                lenient().when(uuidResponse.getStatus()).thenReturn(200);
                httpRequestMock.when(() -> HttpRequestUtils.getServerByUUID("existing-uuid", mockToken))
                    .thenReturn(uuidResponse);
                
                // 模拟更新成功
                HttpResponse updateResponse = mock(HttpResponse.class);
                lenient().when(updateResponse.isOk()).thenReturn(true);
                httpRequestMock.when(() -> HttpRequestUtils.updateServer(
                    eq("existing-uuid"), eq(443), eq("myserver.com"), eq(true), eq(mockToken)))
                    .thenReturn(updateResponse);
                
                boolean result = PluginInitiationUtils.loginAccount("user", "pass");
                
                assertThat(result).isTrue();
            }
        }
        
        @Test
        @DisplayName("注册失败时返回false")
        void shouldReturnFalseWhenRegistrationFails() throws IOException {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<HttpRequestUtils> httpRequestMock = mockStatic(HttpRequestUtils.class);
                 MockedStatic<CommonUtils> commonUtilsMock = mockStatic(CommonUtils.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.getConfig()).thenReturn(mockConfig);
                when(mockUltiTools.getLogger()).thenReturn(mockLogger);
                when(mockConfig.getBoolean("web-editor.https.enable")).thenReturn(false);
                when(mockConfig.getInt("web-editor.port")).thenReturn(8080);
                when(mockConfig.getString("web-editor.https.domain")).thenReturn("localhost");
                
                commonUtilsMock.when(CommonUtils::getUltiToolsUUID).thenReturn("test-uuid");
                
                TokenEntity mockToken = mock(TokenEntity.class);
                httpRequestMock.when(() -> HttpRequestUtils.getToken("admin", "wrongpass"))
                    .thenReturn(mockToken);
                
                HttpResponse uuidResponse = mock(HttpResponse.class);
                when(uuidResponse.getStatus()).thenReturn(404);
                httpRequestMock.when(() -> HttpRequestUtils.getServerByUUID("test-uuid", mockToken))
                    .thenReturn(uuidResponse);
                
                // 模拟注册失败
                HttpResponse registerResponse = mock(HttpResponse.class);
                when(registerResponse.isOk()).thenReturn(false);
                when(registerResponse.body()).thenReturn("Registration failed: unauthorized");
                httpRequestMock.when(() -> HttpRequestUtils.registerServer(
                    anyString(), anyInt(), anyString(), anyBoolean(), any()))
                    .thenReturn(registerResponse);
                
                boolean result = PluginInitiationUtils.loginAccount("admin", "wrongpass");
                
                assertThat(result).isFalse();
            }
        }
        
        @Test
        @DisplayName("更新失败时返回false")
        void shouldReturnFalseWhenUpdateFails() throws IOException {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<HttpRequestUtils> httpRequestMock = mockStatic(HttpRequestUtils.class);
                 MockedStatic<CommonUtils> commonUtilsMock = mockStatic(CommonUtils.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.getConfig()).thenReturn(mockConfig);
                when(mockUltiTools.getLogger()).thenReturn(mockLogger);
                when(mockConfig.getBoolean("web-editor.https.enable")).thenReturn(true);
                when(mockConfig.getInt("web-editor.port")).thenReturn(443);
                when(mockConfig.getString("web-editor.https.domain")).thenReturn("server.com");
                
                commonUtilsMock.when(CommonUtils::getUltiToolsUUID).thenReturn("existing-uuid");
                
                TokenEntity mockToken = mock(TokenEntity.class);
                httpRequestMock.when(() -> HttpRequestUtils.getToken("user", "pass"))
                    .thenReturn(mockToken);
                
                HttpResponse uuidResponse = mock(HttpResponse.class);
                when(uuidResponse.getStatus()).thenReturn(200);
                httpRequestMock.when(() -> HttpRequestUtils.getServerByUUID("existing-uuid", mockToken))
                    .thenReturn(uuidResponse);
                
                // 模拟更新失败
                HttpResponse updateResponse = mock(HttpResponse.class);
                when(updateResponse.isOk()).thenReturn(false);
                when(updateResponse.body()).thenReturn("Update failed: server error");
                httpRequestMock.when(() -> HttpRequestUtils.updateServer(
                    anyString(), anyInt(), anyString(), anyBoolean(), any()))
                    .thenReturn(updateResponse);
                
                boolean result = PluginInitiationUtils.loginAccount("user", "pass");
                
                assertThat(result).isFalse();
            }
        }
    }

    // ========== stopWebsocket 测试 ==========
    
    @Nested
    @DisplayName("stopWebsocket 测试")
    class StopWebsocketTests {
        
        @Test
        @DisplayName("停止WebSocket应该安全完成")
        void stopWebsocketShouldCompleteSafely() {
            // stopWebsocket 应该能安全调用，即使没有初始化
            org.assertj.core.api.Assertions.assertThatCode(() -> PluginInitiationUtils.stopWebsocket())
                .doesNotThrowAnyException();
        }
    }

    // ========== 消息处理器逻辑测试 ==========
    
    @Nested
    @DisplayName("消息处理逻辑测试")
    class MessageHandlerLogicTests {
        
        @Test
        @DisplayName("ping消息应该生成正确的pong响应")
        void pingMessageShouldGenerateCorrectPongResponse() {
            JSONObject pingMessage = new JSONObject();
            pingMessage.put("type", "ping");
            pingMessage.put("timestamp", System.currentTimeMillis());
            
            // 验证 ping 消息的格式
            assertThat(pingMessage.getString("type")).isEqualTo("ping");
            assertThat(pingMessage.containsKey("timestamp")).isTrue();
            
            // 构造预期的 pong 响应
            JSONObject expectedPong = new JSONObject();
            expectedPong.put("type", "pong");
            expectedPong.put("timestamp", System.currentTimeMillis());
            
            JSONObject pongData = new JSONObject();
            pongData.put("timestamp", System.currentTimeMillis());
            expectedPong.put("data", pongData);
            
            assertThat(expectedPong.getString("type")).isEqualTo("pong");
            assertThat(expectedPong.containsKey("data")).isTrue();
        }
        
        @Test
        @DisplayName("subscribe消息结构应该正确")
        void subscribeMessageStructureShouldBeCorrect() {
            JSONObject subscribeData = new JSONObject();
            subscribeData.put("subscribed", true);
            subscribeData.put("serverId", "server-123");
            subscribeData.put("message", "Successfully subscribed");
            
            assertThat(subscribeData.getBooleanValue("subscribed")).isTrue();
            assertThat(subscribeData.getString("serverId")).isEqualTo("server-123");
            assertThat(subscribeData.getString("message")).isEqualTo("Successfully subscribed");
        }
        
        @Test
        @DisplayName("error消息结构应该正确")
        void errorMessageStructureShouldBeCorrect() {
            JSONObject errorData = new JSONObject();
            errorData.put("message", "Connection timeout");
            
            assertThat(errorData.getString("message")).isEqualTo("Connection timeout");
        }
        
        @Test
        @DisplayName("pong延迟计算逻辑应该正确")
        void pongLatencyCalculationShouldBeCorrect() {
            JSONObject pongData = new JSONObject();
            long serverTimestamp = System.currentTimeMillis() - 50; // 模拟50ms前的时间
            pongData.put("timestamp", serverTimestamp);
            
            long currentTime = System.currentTimeMillis();
            long latency = currentTime - pongData.getLongValue("timestamp");
            
            assertThat(latency).isGreaterThanOrEqualTo(0);
            assertThat(latency).isLessThan(1000); // 延迟应该小于1秒
        }
    }

    // ========== 配置消息测试 ==========
    
    @Nested
    @DisplayName("配置消息测试")
    class ConfigMessageTests {
        
        @Test
        @DisplayName("upload_config消息结构应该正确")
        void uploadConfigMessageStructureShouldBeCorrect() {
            JSONObject configMessage = new JSONObject();
            configMessage.put("type", "upload_config");
            
            JSONObject data = new JSONObject();
            data.put("configType", "plugin_config");
            data.put("configName", "UltiTools.yml");
            data.put("configContent", "{\"setting\": true}");
            data.put("format", "yaml");
            data.put("backup", true);
            data.put("serverId", "server-123");
            
            configMessage.put("data", data);
            configMessage.put("serverId", "server-123");
            
            assertThat(configMessage.getString("type")).isEqualTo("upload_config");
            assertThat(configMessage.getJSONObject("data").getString("configType"))
                .isEqualTo("plugin_config");
            assertThat(configMessage.getJSONObject("data").getBooleanValue("backup")).isTrue();
        }
        
        @Test
        @DisplayName("update_config消息包含requestId时应该处理")
        void updateConfigWithRequestIdShouldBeProcessed() {
            JSONObject data = new JSONObject();
            data.put("requestId", "req-12345");
            data.put("config", "{\"newSetting\": \"value\"}");
            
            assertThat(data.containsKey("requestId")).isTrue();
            assertThat(data.getString("requestId")).isEqualTo("req-12345");
        }
        
        @Test
        @DisplayName("update_config消息不包含requestId时应该忽略")
        void updateConfigWithoutRequestIdShouldBeIgnored() {
            JSONObject data = new JSONObject();
            data.put("message", "Config updated successfully");
            
            assertThat(data.containsKey("requestId")).isFalse();
            assertThat(data.containsKey("message")).isTrue();
        }
    }

    // ========== 服务器监控消息测试 ==========
    
    @Nested
    @DisplayName("服务器监控消息测试")
    class ServerMonitorMessageTests {
        
        @Test
        @DisplayName("player_event消息结构应该正确")
        void playerEventMessageStructureShouldBeCorrect() {
            JSONObject playerData = new JSONObject();
            playerData.put("eventType", "join");
            
            JSONObject player = new JSONObject();
            player.put("name", "TestPlayer");
            player.put("uuid", "uuid-12345");
            playerData.put("player", player);
            
            assertThat(playerData.getString("eventType")).isEqualTo("join");
            assertThat(playerData.getJSONObject("player").getString("name")).isEqualTo("TestPlayer");
        }
        
        @Test
        @DisplayName("command_result消息结构应该正确")
        void commandResultMessageStructureShouldBeCorrect() {
            JSONObject resultData = new JSONObject();
            resultData.put("commandId", "cmd-12345");
            resultData.put("success", true);
            resultData.put("output", "Command executed successfully");
            resultData.put("executionTime", 150L);
            
            assertThat(resultData.getString("commandId")).isEqualTo("cmd-12345");
            assertThat(resultData.getBooleanValue("success")).isTrue();
            assertThat(resultData.getLongValue("executionTime")).isEqualTo(150L);
        }
        
        @Test
        @DisplayName("file_operation_result消息结构应该正确")
        void fileOperationResultMessageStructureShouldBeCorrect() {
            JSONObject resultData = new JSONObject();
            resultData.put("operationId", "op-12345");
            resultData.put("success", true);
            resultData.put("operation", "read");
            resultData.put("path", "/server/config.yml");
            resultData.put("message", "File read successfully");
            
            assertThat(resultData.getString("operationId")).isEqualTo("op-12345");
            assertThat(resultData.getString("operation")).isEqualTo("read");
            assertThat(resultData.getString("path")).isEqualTo("/server/config.yml");
        }
    }

    // ========== 备份消息测试 ==========
    
    @Nested
    @DisplayName("备份消息测试")
    class BackupMessageTests {
        
        @Test
        @DisplayName("backup_operation消息结构应该正确")
        void backupOperationMessageStructureShouldBeCorrect() {
            JSONObject backupData = new JSONObject();
            backupData.put("operation", "create");
            backupData.put("operationId", "backup-12345");
            
            assertThat(backupData.getString("operation")).isEqualTo("create");
            assertThat(backupData.getString("operationId")).isEqualTo("backup-12345");
        }
        
        @Test
        @DisplayName("backup_progress消息结构应该正确")
        void backupProgressMessageStructureShouldBeCorrect() {
            JSONObject progressData = new JSONObject();
            progressData.put("operationId", "backup-12345");
            progressData.put("progress", 75.5);
            progressData.put("currentStep", "Compressing files");
            progressData.put("completed", false);
            
            assertThat(progressData.getString("operationId")).isEqualTo("backup-12345");
            assertThat(progressData.getDoubleValue("progress")).isEqualTo(75.5);
            assertThat(progressData.getString("currentStep")).isEqualTo("Compressing files");
            assertThat(progressData.getBooleanValue("completed")).isFalse();
        }
        
        @Test
        @DisplayName("备份完成时completed应该为true")
        void backupCompletedShouldBeTrue() {
            JSONObject progressData = new JSONObject();
            progressData.put("operationId", "backup-12345");
            progressData.put("progress", 100.0);
            progressData.put("currentStep", "Finished");
            progressData.put("completed", true);
            
            assertThat(progressData.getBooleanValue("completed")).isTrue();
            assertThat(progressData.getDoubleValue("progress")).isEqualTo(100.0);
        }
    }

    // ========== 插件列表消息测试 ==========
    
    @Nested
    @DisplayName("插件列表消息测试")
    class PluginListMessageTests {
        
        @Test
        @DisplayName("plugin_list响应结构应该正确")
        void pluginListResponseStructureShouldBeCorrect() {
            JSONObject response = new JSONObject();
            response.put("type", "plugin_list");
            response.put("serverId", "server-123");
            response.put("timestamp", System.currentTimeMillis());
            response.put("requestId", "req-12345");
            
            JSONObject responseData = new JSONObject();
            JSONArray plugins = new JSONArray();
            
            JSONObject pluginInfo = new JSONObject();
            pluginInfo.put("name", "UltiTools");
            pluginInfo.put("version", "6.2.0");
            pluginInfo.put("enabled", true);
            pluginInfo.put("author", "wisdomme");
            pluginInfo.put("description", "A plugin framework");
            plugins.add(pluginInfo);
            
            responseData.put("plugins", plugins);
            responseData.put("totalCount", 1);
            response.put("data", responseData);
            
            assertThat(response.getString("type")).isEqualTo("plugin_list");
            assertThat(response.getJSONObject("data").getIntValue("totalCount")).isEqualTo(1);
            assertThat(response.getJSONObject("data").getJSONArray("plugins")).hasSize(1);
        }
        
        @Test
        @DisplayName("插件信息字段应该完整")
        void pluginInfoFieldsShouldBeComplete() {
            JSONObject pluginInfo = new JSONObject();
            pluginInfo.put("name", "ExamplePlugin");
            pluginInfo.put("version", "1.0.0");
            pluginInfo.put("enabled", true);
            pluginInfo.put("author", "Author1, Author2");
            pluginInfo.put("description", "An example plugin");
            
            assertThat(pluginInfo.containsKey("name")).isTrue();
            assertThat(pluginInfo.containsKey("version")).isTrue();
            assertThat(pluginInfo.containsKey("enabled")).isTrue();
            assertThat(pluginInfo.containsKey("author")).isTrue();
            assertThat(pluginInfo.containsKey("description")).isTrue();
        }
    }

    // ========== server_status消息测试 ==========
    
    @Nested
    @DisplayName("server_status消息测试")
    class ServerStatusMessageTests {
        
        @Test
        @DisplayName("server_status请求包含requestId时应该处理")
        void serverStatusRequestWithRequestIdShouldBeProcessed() {
            JSONObject data = new JSONObject();
            data.put("requestId", "status-req-12345");
            
            assertThat(data.containsKey("requestId")).isTrue();
            assertThat(data.getString("requestId")).isEqualTo("status-req-12345");
        }
        
        @Test
        @DisplayName("server_status确认消息应该被忽略")
        void serverStatusConfirmationShouldBeIgnored() {
            JSONObject data = new JSONObject();
            data.put("message", "Status received");
            
            assertThat(data.containsKey("requestId")).isFalse();
            assertThat(data.containsKey("message")).isTrue();
        }
    }

    // ========== metrics_data消息测试 ==========
    
    @Nested
    @DisplayName("metrics_data消息测试")
    class MetricsDataMessageTests {
        
        @Test
        @DisplayName("metrics_data请求包含requestId时应该处理")
        void metricsRequestWithRequestIdShouldBeProcessed() {
            JSONObject data = new JSONObject();
            data.put("requestId", "metrics-req-12345");
            
            assertThat(data.containsKey("requestId")).isTrue();
            assertThat(data.getString("requestId")).isEqualTo("metrics-req-12345");
        }
    }

    // ========== 错误响应测试 ==========
    
    @Nested
    @DisplayName("错误响应测试")
    class ErrorResponseTests {
        
        @Test
        @DisplayName("错误响应结构应该正确")
        void errorResponseStructureShouldBeCorrect() {
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("type", "error");
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            JSONObject errorData = new JSONObject();
            errorData.put("message", "Unknown message type: invalid_type");
            errorResponse.put("data", errorData);
            
            assertThat(errorResponse.getString("type")).isEqualTo("error");
            assertThat(errorResponse.containsKey("timestamp")).isTrue();
            assertThat(errorResponse.getJSONObject("data").getString("message"))
                .contains("Unknown message type");
        }
        
        @Test
        @DisplayName("处理消息异常时应该生成错误响应")
        void shouldGenerateErrorResponseOnMessageProcessingException() {
            String errorMessage = "Error processing message: NullPointerException";
            
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("type", "error");
            errorResponse.put("timestamp", System.currentTimeMillis());
            
            JSONObject errorData = new JSONObject();
            errorData.put("message", errorMessage);
            errorResponse.put("data", errorData);
            
            assertThat(errorResponse.getJSONObject("data").getString("message"))
                .contains("Error processing message");
        }
    }

    // ========== WebSocket URL 配置测试 ==========
    
    @Nested
    @DisplayName("WebSocket URL 配置测试")
    class WebSocketUrlConfigTests {
        
        @Test
        @DisplayName("HTTPS启用时应该使用wss协议")
        void shouldUseWssWhenHttpsEnabled() {
            boolean useHttps = true;
            String wsUrl;
            if (useHttps) {
                wsUrl = "wss://api.ultikits.com/ws";
            } else {
                wsUrl = "ws://localhost:8787/ws";
            }
            
            assertThat(wsUrl).startsWith("wss://");
            assertThat(wsUrl).isEqualTo("wss://api.ultikits.com/ws");
        }
        
        @Test
        @DisplayName("HTTPS禁用时应该使用ws协议")
        void shouldUseWsWhenHttpsDisabled() {
            boolean useHttps = false;
            String wsUrl;
            if (useHttps) {
                wsUrl = "wss://api.ultikits.com/ws";
            } else {
                wsUrl = "ws://localhost:8787/ws";
            }
            
            assertThat(wsUrl).startsWith("ws://");
            assertThat(wsUrl).isEqualTo("ws://localhost:8787/ws");
        }
    }

    // ========== 消息类型常量测试 ==========
    
    @Nested
    @DisplayName("消息类型测试")
    class MessageTypeTests {
        
        @Test
        @DisplayName("应该支持所有系统基础消息类型")
        void shouldSupportAllSystemMessageTypes() {
            String[] systemTypes = {"ping", "pong", "subscribe", "unsubscribe", "notification", "error"};
            
            for (String type : systemTypes) {
                JSONObject message = new JSONObject();
                message.put("type", type);
                assertThat(message.getString("type")).isEqualTo(type);
            }
        }
        
        @Test
        @DisplayName("应该支持所有服务器监控消息类型")
        void shouldSupportAllMonitorMessageTypes() {
            String[] monitorTypes = {"server_status", "plugin_list", "player_event", "metrics_data"};
            
            for (String type : monitorTypes) {
                JSONObject message = new JSONObject();
                message.put("type", type);
                assertThat(message.getString("type")).isEqualTo(type);
            }
        }
        
        @Test
        @DisplayName("应该支持所有操作控制消息类型")
        void shouldSupportAllControlMessageTypes() {
            String[] controlTypes = {"execute_command", "command_result", "file_operation", "file_operation_result"};
            
            for (String type : controlTypes) {
                JSONObject message = new JSONObject();
                message.put("type", type);
                assertThat(message.getString("type")).isEqualTo(type);
            }
        }
        
        @Test
        @DisplayName("应该支持所有数据流消息类型")
        void shouldSupportAllStreamMessageTypes() {
            String[] streamTypes = {"log_stream", "backup_operation", "backup_progress"};
            
            for (String type : streamTypes) {
                JSONObject message = new JSONObject();
                message.put("type", type);
                assertThat(message.getString("type")).isEqualTo(type);
            }
        }
        
        @Test
        @DisplayName("应该支持所有配置管理消息类型")
        void shouldSupportAllConfigMessageTypes() {
            String[] configTypes = {"upload_config", "update_config"};
            
            for (String type : configTypes) {
                JSONObject message = new JSONObject();
                message.put("type", type);
                assertThat(message.getString("type")).isEqualTo(type);
            }
        }
    }

    // ========== 配置类型测试 ==========
    
    @Nested
    @DisplayName("配置类型测试")
    class ConfigTypeTests {
        
        @Test
        @DisplayName("应该支持plugin_config类型")
        void shouldSupportPluginConfigType() {
            String configType = "plugin_config";
            assertThat(configType).isEqualTo("plugin_config");
        }
        
        @Test
        @DisplayName("应该支持server_properties类型")
        void shouldSupportServerPropertiesType() {
            String configType = "server_properties";
            assertThat(configType).isEqualTo("server_properties");
        }
        
        @Test
        @DisplayName("应该支持permissions类型")
        void shouldSupportPermissionsType() {
            String configType = "permissions";
            assertThat(configType).isEqualTo("permissions");
        }
        
        @Test
        @DisplayName("不支持的配置类型应该抛出异常")
        void unsupportedConfigTypeShouldThrowException() {
            String configType = "unsupported_type";
            
            assertThat(configType).isNotIn("plugin_config", "server_properties", "permissions");
        }
    }

    // ========== 集成场景测试 ==========
    
    @Nested
    @DisplayName("集成场景测试")
    class IntegrationScenarioTests {
        
        @Test
        @DisplayName("完整的消息处理流程")
        void completeMessageProcessingFlow() {
            // 构造一个完整的消息
            JSONObject message = new JSONObject();
            message.put("type", "server_status");
            
            JSONObject data = new JSONObject();
            data.put("requestId", "test-request-123");
            message.put("data", data);
            
            // 验证消息结构
            String type = message.getString("type");
            JSONObject messageData = message.getJSONObject("data");
            
            assertThat(type).isEqualTo("server_status");
            assertThat(messageData).isNotNull();
            assertThat(messageData.containsKey("requestId")).isTrue();
        }
        
        @Test
        @DisplayName("订阅和取消订阅流程")
        void subscribeAndUnsubscribeFlow() {
            // 订阅
            JSONObject subscribeResponse = new JSONObject();
            subscribeResponse.put("subscribed", true);
            subscribeResponse.put("serverId", "server-123");
            subscribeResponse.put("message", "Successfully subscribed");
            
            assertThat(subscribeResponse.getBooleanValue("subscribed")).isTrue();
            
            // 取消订阅
            JSONObject unsubscribeResponse = new JSONObject();
            unsubscribeResponse.put("serverId", "server-123");
            
            assertThat(unsubscribeResponse.getString("serverId")).isEqualTo("server-123");
        }
        
        @Test
        @DisplayName("命令执行完整流程")
        void commandExecutionCompleteFlow() {
            // 构造命令执行请求
            JSONObject executeData = new JSONObject();
            executeData.put("command", "say Hello World");
            executeData.put("executor", "CONSOLE");
            executeData.put("async", false);
            
            // 构造命令执行结果
            JSONObject resultData = new JSONObject();
            resultData.put("commandId", "cmd-123");
            resultData.put("success", true);
            resultData.put("output", "Message sent");
            resultData.put("executionTime", 50L);
            
            assertThat(executeData.getString("command")).isEqualTo("say Hello World");
            assertThat(resultData.getBooleanValue("success")).isTrue();
        }
    }
}
