package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Proves the whole remote-surface policy spine end to end on {@code execute_command} — the tracer
 * task's single deliverable — and pins {@code ping}'s {@link Capability#NONE} bypass and
 * {@code file_operation}'s capability resolver, which the same dispatch-table change reaches.
 * <p>
 * Reaches {@link PluginInitiationUtils#handleInboundMessage} and
 * {@link PluginInitiationUtils#inboundDispatchTable()} via reflection: this class lives in
 * {@code entities}, a different package from {@code utils}, per the plan's file layout — the
 * existing {@code PluginInitiationUtilsInboundMessageTest} reaches the same methods from within the
 * same package and needs no reflection; this class cannot.
 * <p>
 * Action-log entries are asserted by capturing them from a mocked {@link RemoteActionLog} rather
 * than by reading the file, so this test stays sub-second and filesystem-independent.
 */
@DisplayName("Capability 端到端网关 —— execute_command 追踪")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach package-private test seams
class CapabilityGateTracerTest {

    private Logger mockLogger;
    private CommandExecutionManager mockCommandExecutionManager;
    private FileOperationManager mockFileOperationManager;
    private RemoteActionLog mockRemoteActionLog;
    private UltiPanelWebSocketClient mockPanelWs;
    private Object previousPanelWs;

    @BeforeEach
    void setUp() throws Exception {
        mockLogger = mock(Logger.class);
        mockCommandExecutionManager = mock(CommandExecutionManager.class);
        mockFileOperationManager = mock(FileOperationManager.class);
        mockRemoteActionLog = mock(RemoteActionLog.class);

        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(emptyConfig());
            lenient().when(ultiTools.getCommandExecutionManager()).thenReturn(mockCommandExecutionManager);
            lenient().when(ultiTools.getFileOperationManager()).thenReturn(mockFileOperationManager);
            lenient().when(ultiTools.getRemoteActionLog()).thenReturn(mockRemoteActionLog);
        });

        mockPanelWs = mock(UltiPanelWebSocketClient.class);
        lenient().when(mockPanelWs.getServerId()).thenReturn("test-server-uuid");
        previousPanelWs = setPanelWs(mockPanelWs);
    }

    @AfterEach
    void tearDown() throws Exception {
        // panelWS 是静态字段，不还原会漏给同一个 JVM 里后面的测试类。
        setPanelWs(previousPanelWs);
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static YamlConfiguration emptyConfig() {
        return new YamlConfiguration();
    }

    private static YamlConfiguration configWith(String path, boolean value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        return config;
    }

    private Object setPanelWs(Object value) throws Exception {
        Field field = PluginInitiationUtils.class.getDeclaredField("panelWS");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    private static void invokeHandleInboundMessage(JsonObject message) throws Exception {
        Method method = PluginInitiationUtils.class.getDeclaredMethod("handleInboundMessage", JsonObject.class);
        method.setAccessible(true);
        method.invoke(null, message);
    }

    private static JsonObject executeCommandMessage(String command, String commandId) {
        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("commandId", commandId);
        JsonObject message = new JsonObject();
        message.addProperty("type", "execute_command");
        message.add("data", data);
        return message;
    }

    private static JsonObject fileOperationMessage(String operation, String path) {
        JsonObject data = new JsonObject();
        data.addProperty("operation", operation);
        data.addProperty("path", path);
        JsonObject message = new JsonObject();
        message.addProperty("type", "file_operation");
        message.add("data", data);
        return message;
    }

    @Nested
    @DisplayName("Capability.isEnabled() 的出厂默认值")
    class NoConfigDefaults {

        @Test
        @DisplayName("没有 ultipanel.capabilities 块时，commands 为 false、monitoring 为 true")
        void splitDefaultsWithNoConfigBlock() {
            assertThat(Capability.COMMANDS.isEnabled())
                    .as("D-08：commands 出厂默认关闭")
                    .isFalse();
            assertThat(Capability.MONITORING.isEnabled())
                    .as("D-08：monitoring 出厂默认开启，否则升级后的服务器在面板上显示为离线")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("execute_command 网关")
    class ExecuteCommandGate {

        @Test
        @DisplayName("commands 禁用时：handler 不被调用，发送 capability_denied，记一条 DENIED")
        void deniedWhenCommandsDisabled() throws Exception {
            invokeHandleInboundMessage(executeCommandMessage("say hi", "c1"));

            verify(mockCommandExecutionManager, never()).executeCommand(any());

            ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockPanelWs, times(1)).sendMessage(sent.capture());
            JsonObject response = sent.getValue();
            assertThat(response.get("type").getAsString()).isEqualTo("capability_denied");
            JsonObject payload = response.getAsJsonObject("data");
            assertThat(payload.get("type").getAsString()).isEqualTo("execute_command");
            assertThat(payload.get("capability").getAsString()).isEqualTo("COMMANDS");
            assertThat(payload.get("configKey").getAsString()).isEqualTo("ultipanel.capabilities.commands");
            assertThat(payload.get("configFile").getAsString()).isEqualTo("plugins/UltiTools/config.yml");
            assertThat(payload.get("commandId").getAsString()).isEqualTo("c1");
            assertThat(payload.get("reason").getAsString())
                    .contains("ultipanel.capabilities.commands")
                    .contains("plugins/UltiTools/config.yml");

            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();
            assertThat(entry.getCapability()).isEqualTo("COMMANDS");
            assertThat(entry.getAction()).isEqualTo("execute_command");
            assertThat(entry.getTarget()).isEqualTo("say hi");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(entry.getReason()).contains("ultipanel.capabilities.commands");
        }

        @Test
        @DisplayName("commands 启用时：handler 被调用一次，记一条 ALLOWED，不发送拒绝消息")
        void allowedWhenCommandsEnabled() throws Exception {
            lenient().when(UltiTools.getInstance().getConfig())
                    .thenReturn(configWith("ultipanel.capabilities.commands", true));

            invokeHandleInboundMessage(executeCommandMessage("say hi", "c1"));

            verify(mockCommandExecutionManager, times(1)).executeCommand(any());
            verify(mockPanelWs, never()).sendMessage(any());

            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();
            assertThat(entry.getCapability()).isEqualTo("COMMANDS");
            assertThat(entry.getAction()).isEqualTo("execute_command");
            assertThat(entry.getTarget()).isEqualTo("say hi");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
            assertThat(entry.getReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Capability.NONE 完全绕过网关")
    class NoneBypassesGate {

        @Test
        @DisplayName("ping：不检查能力、不记录 action-log，处理器照常响应 pong")
        void pingBypassesGateAndIsNeverLogged() throws Exception {
            JsonObject message = new JsonObject();
            message.addProperty("type", "ping");

            invokeHandleInboundMessage(message);

            ArgumentCaptor<JsonObject> sent = ArgumentCaptor.forClass(JsonObject.class);
            verify(mockPanelWs, times(1)).sendMessage(sent.capture());
            assertThat(sent.getValue().get("type").getAsString()).isEqualTo("pong");

            verify(mockRemoteActionLog, never()).record(any());
        }
    }

    @Nested
    @DisplayName("file_operation 的 resolver")
    class FileOperationResolver {

        @Test
        @DisplayName("operation=write 解析为 FILE_WRITE（默认关闭 -> 拒绝）")
        void writeResolvesToFileWriteAndIsDeniedByDefault() throws Exception {
            invokeHandleInboundMessage(fileOperationMessage("write", "x"));

            verify(mockFileOperationManager, never()).handleFileOperation(any());

            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();
            assertThat(entry.getCapability()).isEqualTo("FILE_WRITE");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(entry.getTarget()).isEqualTo("x");
        }

        @Test
        @DisplayName("operation=read 解析为 FILE_READ（默认开启 -> 放行）")
        void readResolvesToFileReadAndIsAllowedByDefault() throws Exception {
            invokeHandleInboundMessage(fileOperationMessage("read", "x"));

            verify(mockFileOperationManager, times(1)).handleFileOperation(any());

            ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
            verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
            RemoteActionLog.Entry entry = entryCaptor.getValue();
            assertThat(entry.getCapability()).isEqualTo("FILE_READ");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        }
    }

    @Nested
    @DisplayName("畸形/缺失 type 不受网关影响")
    class MalformedTypeUnaffected {

        @Test
        @DisplayName("缺少 type 字段：仍走既有 WARNING 分支，网关从不触发")
        void missingTypeStillTakesWarningBranchAndNeverReachesGate() throws Exception {
            JsonObject message = new JsonObject();
            message.addProperty("data", "irrelevant");

            invokeHandleInboundMessage(message);

            verify(mockRemoteActionLog, never()).record(any());
            verify(mockPanelWs, never()).sendMessage(any());
        }
    }
}
