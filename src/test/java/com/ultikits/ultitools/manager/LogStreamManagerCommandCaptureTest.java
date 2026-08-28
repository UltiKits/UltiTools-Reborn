package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Measurement for #217 Question 3: does {@link LogStreamManager}'s JUL root-handler capture
 * mechanism observe player-typed command text?
 * <p>
 * This class supplies the harness measurement D-32 requires before any conclusion is written:
 * install the manager's handler onto the JUL root logger through the manager's own public path
 * ({@link LogStreamManager#initialize}), publish a {@link LogRecord} carrying the exact phrasing
 * Paper's command dispatch path uses, and observe whether the handler forwards it (Tests 1-2),
 * plus a negative control proving a null result is attributable to the mechanism and not to a
 * broken harness (Test 3).
 * <p>
 * The result recorded here — and the reason a real player was never needed to reproduce it — is
 * written with its full evidence chain in
 * {@code .planning/phases/01-adjudication-foundations-compatibility-baseline/01-ADJUDICATION.md}
 * (section Q3): Paper's own command-dispatch log call
 * ({@code net.minecraft.server.network.ServerGamePacketListenerImpl}, verified by disassembling
 * the actual {@code paper-1.20.6.jar} / {@code paper-1.21.1.jar} / {@code paper-1.21.4.jar}
 * server jars under {@code ~/servers/versions/}) is emitted through a {@code static final
 * org.slf4j.Logger LOGGER} field, never through {@code java.util.logging}. Paper's bundled
 * {@code log4j2.xml} carries no appender that bridges Log4j2 back into
 * {@code java.util.logging}, and no {@code log4j-jul} artifact is present under
 * {@code ~/servers/libraries/}. The only cross-framework bridge that does exist —
 * {@code org.bukkit.craftbukkit.util.ForwardLogHandler} — runs the other way ({@code
 * java.util.logging} into Log4j2), so it cannot carry this record either. The mechanism this
 * class exercises therefore cannot ever receive that record, independent of what a live player
 * session would show.
 *
 * @since 6.3.0
 */
@DisplayName("LogStreamManager command-capture measurement (#217 Q3)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LogStreamManagerCommandCaptureTest {

    /**
     * A record shaped exactly like the message Paper's {@code ServerGamePacketListenerImpl}
     * would produce once its two {@code {}} placeholders are resolved — the exact template,
     * {@code "{} issued server command: {}"}, is quoted and evidenced in 01-ADJUDICATION.md's
     * Q3 section. Deliberately includes command-argument text shaped like a login-plugin
     * password, matching the disclosure concern D-32 names.
     */
    private static final String COMMAND_LOG_MESSAGE =
            "TestPlayer issued server command: /login s3cr3t-password";

    private ServerMock server;
    private LogStreamManager logStreamManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private Logger mockLogger;
    private FileConfiguration mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockLogger = mock(Logger.class);
        mockConfig = mock(FileConfiguration.class);

        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(mockConfig);
        });

        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("q3-measurement-server");

        // Immediate-send mode: a forwarded record reaches webSocketClient.sendMessage()
        // synchronously, on the same thread as publish(), instead of waiting on the transmitter's
        // batch scheduler.
        when(mockConfig.contains("ultipanel.logging.batch.enabled")).thenReturn(true);
        when(mockConfig.getBoolean("ultipanel.logging.batch.enabled", true)).thenReturn(false);

        resetSingleton();
        logStreamManager = LogStreamManager.getInstance();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void resetSingleton() throws Exception {
        Field instanceField = LogStreamManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        // See LogStreamManagerTest's tearDown for why both shutdown() and the by-type sweep are
        // required: initialize() leaks a live handler and a scheduler thread past this test's
        // lifetime unless both are torn down (issue #250).
        if (logStreamManager != null) {
            try {
                logStreamManager.shutdown();
            } catch (Exception ignored) {
                // shutdown() itself logs; a mocked environment failing that is not this test's concern.
            }
        }
        removeLeakedSystemLogHandlers();
        resetSingleton();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private void removeLeakedSystemLogHandlers() {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                rootLogger.removeHandler(handler);
            }
        }
    }

    private boolean systemLogHandlerInstalledOnRoot() {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("Test 1 (positive control): a LogRecord carrying the command-dispatch phrasing "
            + "is observed while the handler is installed")
    void positiveControl_commandRecordIsObservedWhileHandlerInstalled() {
        logStreamManager.initialize(mockWebSocketClient);
        assertThat(systemLogHandlerInstalledOnRoot())
                .as("LogStreamManager.initialize() must install its handler onto the JUL root logger")
                .isTrue();

        // initialize() itself already triggered sendMessage (stream-start response, init logs);
        // reset so the assertion below is scoped to the record this test publishes.
        reset(mockWebSocketClient);
        when(mockWebSocketClient.isConnected()).thenReturn(true);

        Logger.getLogger("").log(new LogRecord(Level.INFO, COMMAND_LOG_MESSAGE));

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());

        JsonObject data = captor.getValue().getAsJsonObject("data");
        assertThat(data.get("message").getAsString()).isEqualTo(COMMAND_LOG_MESSAGE);
    }

    @Test
    @DisplayName("Test 2: the handler's forwarding decision for the record is captured -- "
            + "it reaches the transmitter at info level, with the record's text intact")
    void forwardingDecisionIsCapturedAtInfoLevelWithTextIntact() {
        logStreamManager.initialize(mockWebSocketClient);
        reset(mockWebSocketClient);
        when(mockWebSocketClient.isConnected()).thenReturn(true);

        Logger.getLogger("").log(new LogRecord(Level.INFO, COMMAND_LOG_MESSAGE));

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());

        JsonObject sent = captor.getValue();
        assertThat(sent.get("type").getAsString()).isEqualTo("log_stream");

        JsonObject data = sent.getAsJsonObject("data");
        assertThat(data.get("level").getAsString()).isEqualTo("info");
        assertThat(data.get("message").getAsString()).contains("issued server command");
        assertThat(data.get("message").getAsString()).contains("s3cr3t-password");
    }

    @Test
    @DisplayName("Test 3 (negative control): with the handler removed, the same record is not observed")
    void negativeControl_recordIsNotObservedOnceHandlerIsRemoved() {
        logStreamManager.initialize(mockWebSocketClient);
        assertThat(systemLogHandlerInstalledOnRoot()).isTrue();

        // The manager's own by-type removal path (also exercised by shutdown()) -- see
        // LogStreamManager.detachAllSystemLogHandlers().
        logStreamManager.shutdown();
        assertThat(systemLogHandlerInstalledOnRoot())
                .as("shutdown() must detach the handler from the JUL root logger")
                .isFalse();

        reset(mockWebSocketClient);
        when(mockWebSocketClient.isConnected()).thenReturn(true);

        Logger.getLogger("").log(new LogRecord(Level.INFO, COMMAND_LOG_MESSAGE));

        verify(mockWebSocketClient, never()).sendMessage(any(JsonObject.class));
    }
}
