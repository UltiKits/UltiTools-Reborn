package com.ultikits.ultitools.utils;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * {@link PluginInitiationUtils#handleConfigUpdate(com.google.gson.JsonObject)} 的载荷契约。
 *
 * <p>issue #236：面板下发的配置更新被静默丢弃。三处字段与面板对不上——内容在
 * {@code data.configData} 而这里读 {@code data.config}，文件在 {@code data.fileName}
 * 而这里除 {@code server_properties} 外从不读，面板不发 {@code requestId} 而这里拿
 * {@code requestId} 当「是不是一条请求」的判据。第一道就短路：进 else 分支，
 * 记一行 {@code Level.FINE} 然后返回。{@code FINE} 在默认日志配置下不打印，
 * 于是整条链路没有任何一处报错，用户看到「保存成功但没生效」。
 *
 * <p>这类缺陷读代码看不出来——两端各自都是自洽的，不一致只存在于两者之差。所以这里
 * 断言的是「给定面板真实发出的那种消息，究竟有没有落到写配置的调用上」，而不是
 * 某个分支内部的行为。
 */
@DisplayName("PluginInitiationUtils 配置更新载荷契约")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class PluginInitiationUtilsConfigUpdateTest {

    private Logger mockLogger;
    private ConfigManager mockConfigManager;
    private ServerPropertiesManager mockServerProperties;
    private UltiPanelWebSocketClient mockWebSocket;
    private Object previousPanelWs;

    @BeforeEach
    void setUp() throws Exception {
        mockLogger = mock(Logger.class);
        mockConfigManager = mock(ConfigManager.class);
        mockServerProperties = mock(ServerPropertiesManager.class);

        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfigManager()).thenReturn(mockConfigManager);
            lenient().when(ultiTools.getServerPropertiesManager()).thenReturn(mockServerProperties);
        });

        mockWebSocket = mock(UltiPanelWebSocketClient.class);
        lenient().when(mockWebSocket.getServerId()).thenReturn("srv-1");
        previousPanelWs = setPanelWs(mockWebSocket);
    }

    @AfterEach
    void tearDown() throws Exception {
        // panelWS 是静态字段，不还原会漏给同一个 JVM 里后面的测试类。
        setPanelWs(previousPanelWs);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private Object setPanelWs(Object value) throws Exception {
        Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    /** 面板实际下发的那种消息。 */
    private static JsonObject panelMessage(String fileName, String configData, String requestId) {
        JsonObject data = new JsonObject();
        if (fileName != null) {
            data.addProperty("fileName", fileName);
        }
        if (configData != null) {
            data.addProperty("configData", configData);
        }
        if (requestId != null) {
            data.addProperty("requestId", requestId);
        }
        return data;
    }

    /** 取出所有以指定级别记录的日志正文，两参与三参重载都算。 */
    private List<String> loggedAt(Level level) {
        List<String> lines = new ArrayList<>();
        ArgumentCaptor<String> twoArg = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, org.mockito.Mockito.atLeast(0)).log(eq(level), twoArg.capture());
        lines.addAll(twoArg.getAllValues());
        ArgumentCaptor<String> threeArg = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, org.mockito.Mockito.atLeast(0))
                .log(eq(level), threeArg.capture(), any(Throwable.class));
        lines.addAll(threeArg.getAllValues());
        return lines;
    }

    private JsonObject capturedResponse() {
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocket).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("载荷字段")
    class PayloadFields {

        @Test
        @DisplayName("configData 被读取，并按 fileName 写到对应的配置文件")
        void configDataIsReadAndRoutedByFileName() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", "req-1"));

            // 这一句就是 #236 的全部：面板发的这条消息，过去一个写配置的调用都不会发生。
            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"language\":\"zh\"}");
        }

        @Test
        @DisplayName("没有 fileName 时按全量嵌套结构写，走单参入口")
        void missingFileNameFallsBackToTheWholeMap() throws Exception {
            String wholeMap = "{\"UltiTools\":{\"config/lang.yml\":{\"language\":\"zh\"}}}";
            PluginInitiationUtils.handleConfigUpdate(panelMessage(null, wholeMap, "req-2"));

            verify(mockConfigManager).loadFromJson(wholeMap);
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        @Test
        @DisplayName("旧字段 data.config 仍可读，但记一条废弃 WARNING")
        void legacyConfigFieldIsStillReadAndWarnedAbout() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("config", "{\"UltiTools\":{}}");
            data.addProperty("requestId", "req-3");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager).loadFromJson("{\"UltiTools\":{}}");
            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("data.config"));
        }

        @Test
        @DisplayName("configData 与 config 同时存在时以 configData 为准，且不记废弃日志")
        void configDataWinsOverLegacyField() throws Exception {
            JsonObject data = panelMessage("config/lang.yml", "{\"new\":true}", "req-4");
            data.addProperty("config", "{\"old\":true}");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"new\":true}");
            assertThat(loggedAt(Level.WARNING))
                    .noneSatisfy(line -> assertThat(line).contains("data.config"));
        }
    }

    @Nested
    @DisplayName("requestId")
    class RequestIdHandling {

        @Test
        @DisplayName("缺 requestId 的请求仍然被应用，不再静默丢弃")
        void missingRequestIdNoLongerDiscardsTheUpdate() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            verify(mockConfigManager).loadFromJson("config/lang.yml", "{\"language\":\"zh\"}");
        }

        @Test
        @DisplayName("缺 requestId 时的日志级别至少是 WARNING")
        void missingRequestIdIsLoggedAtWarning() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            // 原来这里是 FINE —— 默认日志配置不打印，所以「丢弃」这件事在服务器上不可见。
            assertThat(loggedAt(Level.WARNING))
                    .anySatisfy(line -> assertThat(line).contains("requestId"));
        }

        @Test
        @DisplayName("缺 requestId 时不回响应，因为无从关联")
        void missingRequestIdSendsNoResponse() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", null));

            verify(mockWebSocket, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("成功时回 config_update_response，载荷嵌在 data 里")
        void successSendsANestedResponse() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("config/lang.yml", "{\"language\":\"zh\"}", "req-5"));

            JsonObject response = capturedResponse();
            assertThat(response.get("type").getAsString()).isEqualTo("config_update_response");
            assertThat(response.get("serverId").getAsString()).isEqualTo("srv-1");
            // 嵌套而非扁平：其余所有 插件→Worker 的消息都把载荷放在 data 里。
            JsonObject payload = response.getAsJsonObject("data");
            assertThat(payload.get("requestId").getAsString()).isEqualTo("req-5");
            assertThat(payload.get("status").getAsString()).isEqualTo("success");
        }

        @Test
        @DisplayName("应用失败时回 error 并带上原因")
        void failureSendsAnErrorResponse() throws Exception {
            doThrow(new IOException("No registered config matches path: nope.yml"))
                    .when(mockConfigManager).loadFromJson(anyString(), anyString());

            PluginInitiationUtils.handleConfigUpdate(panelMessage("nope.yml", "{}", "req-6"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("nope.yml");
        }

        @Test
        @DisplayName("configData 不是合法 JSON 时也回 error，而不是把异常抛给上层")
        void malformedConfigDataStillAnswers() {
            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "not json at all", "req-7"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
        }
    }

    @Nested
    @DisplayName("回声与回执")
    class EchoesAndAcknowledgements {

        @Test
        @DisplayName("没有配置内容的消息不写任何配置，也不回响应")
        void messageWithoutContentIsTreatedAsAnEcho() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("message", "Configuration update command sent");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager, never()).loadFromJson(anyString());
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
            verify(mockWebSocket, never()).sendMessage(any(JsonObject.class));
        }

        @Test
        @DisplayName("带 requestId 但不带内容的消息同样按回声处理")
        void requestIdAloneIsNotARequest() throws Exception {
            JsonObject data = new JsonObject();
            data.addProperty("requestId", "req-8");

            PluginInitiationUtils.handleConfigUpdate(data);

            verify(mockConfigManager, never()).loadFromJson(anyString());
            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        @Test
        @DisplayName("null data 不抛异常")
        void nullDataIsSafe() throws Exception {
            PluginInitiationUtils.handleConfigUpdate(null);

            verify(mockConfigManager, never()).loadFromJson(anyString());
        }
    }

    @Nested
    @DisplayName("server_properties")
    class ServerPropertiesRouting {

        @Test
        @DisplayName("带内容时转成 set_all 交给专用管理器，不走配置文件那条路")
        void contentIsForwardedAsSetAll() throws Exception {
            stubSetAllResult(singletonList("max-players"), emptyList(), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"max-players\":\"30\"}", "req-9"));

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockServerProperties).applySetAll(captor.capture());
            assertThat(captor.getValue().get("max-players").getAsString()).isEqualTo("30");

            verify(mockConfigManager, never()).loadFromJson(anyString(), anyString());
        }

        /**
         * issue #281：这条路径此前必报成功。{@code applyConfigUpdate} 调的是 {@code void} 的
         * {@code handleServerProperties}，真相在调用链上根本没有返回路径，于是面板拿到的
         * {@code status} 表达的是「消息处理完了」而不是「配置生效了」——而这两件事在
         * 白名单挡下某个键时就分岔了。
         */
        @Test
        @DisplayName("白名单挡下键时回 error，并点名是哪个键")
        void rejectedKeysMakeTheResponseAnError() throws Exception {
            stubSetAllResult(emptyList(), singletonList("rcon.password"), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"rcon.password\":\"hacked\"}", "req-10"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("rcon.password");
        }

        @Test
        @DisplayName("全部生效时才回 success")
        void fullyAppliedBatchReportsSuccess() throws Exception {
            stubSetAllResult(singletonList("motd"), emptyList(), emptyList());

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"motd\":\"hi\"}", "req-11"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("success");
        }

        @Test
        @DisplayName("写入失败的键同样让响应变成 error")
        void writeFailuresMakeTheResponseAnError() throws Exception {
            stubSetAllResult(emptyList(), emptyList(), singletonList("motd"));

            PluginInitiationUtils.handleConfigUpdate(
                    panelMessage("server_properties", "{\"motd\":\"hi\"}", "req-12"));

            JsonObject payload = capturedResponse().getAsJsonObject("data");
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
            assertThat(payload.get("error").getAsString()).contains("motd");
        }

        private void stubSetAllResult(List<String> updated, List<String> rejected, List<String> failed) {
            lenient().when(mockServerProperties.applySetAll(any(JsonObject.class)))
                    .thenReturn(new ServerPropertiesManager.SetAllResult(
                            updated, rejected, failed, emptyList()));
        }

        @Test
        @DisplayName("不带内容时是一条 get 请求，不是回声")
        void withoutContentItIsAGetRequest() {
            PluginInitiationUtils.handleConfigUpdate(panelMessage("server_properties", null, null));

            ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockServerProperties).handleServerProperties(captor.capture());
            assertThat(captor.getValue().get("action").getAsString()).isEqualTo("get");
        }
    }
}
