package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * CR-01 (06-REVIEW.md): proves the capability gate and a handler's own, finer-grained
 * {@code AccessDecision} check never both write a verdict for the same request.
 * <p>
 * {@link CapabilityGateTracerTest} (in {@code entities}) and {@code CapabilityGateIndependenceTest}
 * both mock {@link CommandExecutionManager}/{@link FileOperationManager} — which is exactly why
 * CR-01 shipped undetected: a mocked handler never makes its own {@link RemoteActionLog#record}
 * call, so the interaction between the gate's blanket write and the handler's own write is never
 * exercised. This class wires a <b>real</b> {@link CommandExecutionManager} behind
 * {@code dispatchWithCapabilityGate} so both writers are live at once.
 * <p>
 * Deliberately a top-level test method, not nested inside a {@code @Nested} class — Surefire's
 * {@code -Dtest=Class#method} filter does not descend into {@code @Nested} classes (measured this
 * phase).
 */
@DisplayName("CR-01: 网关与处理器各自的裁决记录不得重复")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach package-private test seam
class CapabilityGateRealCollaboratorsTest {

    private Logger mockLogger;
    // PMD.SingularField: assigned in setUp() and read only by this file's single @Test method
    // (see the class javadoc — deliberately a top-level test, not @Nested). A test-fixture
    // field, not a design smell.
    @SuppressWarnings("PMD.SingularField")
    private RemoteActionLog mockRemoteActionLog;
    @SuppressWarnings("PMD.SingularField")
    private CommandExecutionManager commandExecutionManager;
    private UltiPanelWebSocketClient mockPanelWs;
    private Object previousPanelWs;

    @BeforeEach
    void setUp() throws Exception {
        mockLogger = mock(Logger.class);
        mockRemoteActionLog = mock(RemoteActionLog.class);
        mockPanelWs = mock(UltiPanelWebSocketClient.class);
        lenient().when(mockPanelWs.getServerId()).thenReturn("test-server-uuid");

        // 必须用 Consumer 重载：先打桩、后发布。见 TestHelper 的 javadoc 与 issue #250。
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig())
                    .thenReturn(configWith("ultipanel.capabilities.commands", true));
            lenient().when(ultiTools.getRemoteActionLog()).thenReturn(mockRemoteActionLog);
        });

        // REAL CommandExecutionManager, constructed only after the mock instance above is
        // published — its constructor calls loadConfiguration(), which reads
        // UltiTools.getInstance().getConfig(). This is the point of the test: the handler's own
        // isCommandAllowed()/RemoteActionLog.record() call must be live, not mocked away.
        commandExecutionManager = new CommandExecutionManager();
        commandExecutionManager.setWebSocketClient(mockPanelWs);
        lenient().when(UltiTools.getInstance().getCommandExecutionManager())
                .thenReturn(commandExecutionManager);

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

    @Test
    @DisplayName("黑名单命令 + commands 已启用：只记一条 DENIED，网关不再补一条虚假的 ALLOWED")
    void blocklistedCommandWithCapabilityEnabledRecordsExactlyOneDeniedEntry() throws Exception {
        // "op" is on CommandExecutionManager's shipped default blocklist. Capability.COMMANDS is
        // enabled in setUp, so dispatchWithCapabilityGate invokes the real handler.
        // executeCommand()'s own isCommandAllowed() check refuses "op" and records its own DENIED
        // entry — before CR-01's fix, the gate then unconditionally records a second, spurious
        // ALLOWED entry for the same request because all it checked was that COMMANDS itself is on.
        invokeHandleInboundMessage(executeCommandMessage("op", "c1"));

        ArgumentCaptor<RemoteActionLog.Entry> entryCaptor = ArgumentCaptor.forClass(RemoteActionLog.Entry.class);
        verify(mockRemoteActionLog, times(1)).record(entryCaptor.capture());
        RemoteActionLog.Entry entry = entryCaptor.getValue();
        assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(entry.getCapability()).isEqualTo("COMMANDS");
        assertThat(entry.getTarget()).isEqualTo("op");
    }
}
