package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * End-to-end: a module registers {@code login} through the framework accessor, a JUL record
 * carrying Paper's command-dispatch phrasing is published while the live log handler is installed,
 * and the entry the panel receives shows {@code /login ***} while the record itself, what the local
 * console and log files print, keeps the original text.
 * <p>
 * Uses the harness of {@link LogStreamManagerCommandCaptureTest}: the real
 * {@link LogStreamManager#initialize} handler on the JUL root logger and a mocked socket capturing
 * {@code sendMessage}, in immediate-send mode.
 */
@DisplayName("Sensitive-command redaction of the cloud log stream (end to end)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SensitiveLogRedactionEndToEndTest {

    private static final String COMMAND_LOG_MESSAGE = "Steve issued server command: /login hunter2";
    private static final String REDACTED_LOG_MESSAGE = "Steve issued server command: /login ***";

    private final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
    private LogStreamManager logStreamManager;
    private UltiPanelWebSocketClient mockWebSocketClient;
    private LocalRecordCapture localCapture;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger mockLogger = mock(Logger.class);
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        when(mockConfig.contains("ultipanel.logging.batch.enabled")).thenReturn(true);
        when(mockConfig.getBoolean("ultipanel.logging.batch.enabled", true)).thenReturn(false);

        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getConfig()).thenReturn(mockConfig);
            lenient().when(ultiTools.getSensitiveCommandRegistry()).thenReturn(registry);
        });

        mockWebSocketClient = mock(UltiPanelWebSocketClient.class);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("redaction-e2e-server");

        resetSingleton();
        logStreamManager = LogStreamManager.getInstance();

        localCapture = new LocalRecordCapture();
        Logger.getLogger("").addHandler(localCapture);
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void resetSingleton() throws Exception {
        Field instanceField = LogStreamManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.removeHandler(localCapture);
        if (logStreamManager != null) {
            try {
                logStreamManager.shutdown();
            } catch (Exception ignored) {
                // shutdown() itself logs; a mocked environment failing that is not this test's concern.
            }
        }
        for (Handler handler : rootLogger.getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                rootLogger.removeHandler(handler);
            }
        }
        resetSingleton();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /** Starts the live stream and forgets what initialize() itself sent. */
    private void startStream() {
        logStreamManager.initialize(mockWebSocketClient);
        reset(mockWebSocketClient);
        when(mockWebSocketClient.isConnected()).thenReturn(true);
        when(mockWebSocketClient.getServerId()).thenReturn("redaction-e2e-server");
    }

    /** Publishes one record through the root logger and returns the message the panel received. */
    private String publishAndCapture(String message) {
        Logger.getLogger("").log(new LogRecord(Level.INFO, message));
        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());
        return captor.getValue().getAsJsonObject("data").get("message").getAsString();
    }

    /** Registers the way a module's enable hook does: through the framework accessor. */
    private static void registerLoginLikeAModule() {
        UltiTools.getInstance().getSensitiveCommandRegistry().register("login", "l");
    }

    @Test
    @DisplayName("a registered command's arguments are replaced in the panel entry; the local record keeps them")
    void registeredCommandIsRedactedOnTheWireOnly() {
        registerLoginLikeAModule();
        registerLoginLikeAModule(); // a module reload registers again: harmless
        startStream();

        String sent = publishAndCapture(COMMAND_LOG_MESSAGE);

        assertThat(sent).isEqualTo(REDACTED_LOG_MESSAGE);
        assertThat(localCapture.messages())
                .as("the local console and log files see the record itself, unchanged")
                .contains(COMMAND_LOG_MESSAGE)
                .doesNotContain(REDACTED_LOG_MESSAGE);
    }

    @Test
    @DisplayName("positive control: with nothing registered the record is forwarded verbatim (the table starts empty)")
    void nothingRegistered_forwardedVerbatim() {
        assertThat(registry.registeredNames()).isEmpty();
        startStream();

        assertThat(publishAndCapture(COMMAND_LOG_MESSAGE)).isEqualTo(COMMAND_LOG_MESSAGE);
    }

    @Test
    @DisplayName("negative control: an unregistered command is forwarded byte-identical while login is registered")
    void unregisteredCommand_forwardedByteIdentical() {
        registerLoginLikeAModule();
        startStream();

        String line = "Steve issued server command: /home set base";
        assertThat(publishAndCapture(line)).isEqualTo(line);
    }

    @Test
    @DisplayName("a stack trace attached to the record is redacted too")
    void stackTraceIsRedacted() {
        registerLoginLikeAModule();
        startStream();

        LogRecord record = new LogRecord(Level.SEVERE, "command failed");
        record.setThrown(new IllegalStateException("while running /login hunter2"));
        Logger.getLogger("").log(record);

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());
        String stackTrace = captor.getValue().getAsJsonObject("data").get("stackTrace").getAsString();
        assertThat(stackTrace).contains("while running /login ***").doesNotContain("hunter2");
    }

    @Test
    @DisplayName("a start-up record replayed once the stream opens is redacted the same way")
    void replayedRecordIsRedacted() {
        startStream();
        // Registered after the record was captured but before the replay is built: the module
        // enabled between the capture and the stream opening.
        registerLoginLikeAModule();

        UltiPanelLogTransmitter transmitter = logStreamManager.getLogTransmitter();
        transmitter.replayLog("info", COMMAND_LOG_MESSAGE, "server", null, 1L);
        transmitter.sendReplayBatch();

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());
        JsonObject frame = captor.getValue();
        assertThat(frame.get("type").getAsString()).isEqualTo("log_batch");
        JsonArray logs = frame.getAsJsonArray("data");
        assertThat(logs.size()).isEqualTo(1);
        assertThat(logs.get(0).getAsJsonObject().get("message").getAsString()).isEqualTo(REDACTED_LOG_MESSAGE);
    }

    @Test
    @DisplayName("a panel command's late output written to the console is redacted on the wire the same way")
    void latePanelCommandOutputIsRedactedOnTheWireOnly() {
        registerLoginLikeAModule();
        startStream();
        CommandExecutionManager.CapturedFeedback feedback = new CommandExecutionManager.CapturedFeedback(
                "relay", CommandExecutionManager.LATE_OUTPUT_WINDOW_NANOS, System::nanoTime,
                CommandExecutionManager::writeToConsole);
        assertThat(feedback.take()).isEmpty();

        feedback.accept(net.kyori.adventure.text.Component.text(COMMAND_LOG_MESSAGE));

        ArgumentCaptor<JsonObject> captor = ArgumentCaptor.forClass(JsonObject.class);
        verify(mockWebSocketClient).sendMessage(captor.capture());
        assertThat(captor.getValue().getAsJsonObject("data").get("message").getAsString())
                .isEqualTo(REDACTED_LOG_MESSAGE);
        assertThat(localCapture.messages())
                .as("the local console keeps the line as the command printed it")
                .contains(COMMAND_LOG_MESSAGE);
    }

    /** Stands for the local console: a plain handler on the root logger recording what it is given. */
    private static final class LocalRecordCapture extends Handler {
        private final List<String> received = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            received.add(record.getMessage());
        }

        synchronized List<String> messages() {
            return new ArrayList<>(received);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
