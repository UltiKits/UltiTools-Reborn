package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * End-to-end: a module registers {@code login} through the framework accessor, a JUL record
 * carrying Paper's command-dispatch phrasing is published while the live log handler is installed,
 * and the entry the panel receives shows {@code /login ***} while the record itself, what the local
 * console and log files print, keeps the original text.
 * <p>
 * Harness (re-pointed in 6.4.0, when the protocol v1 stream was removed): a real
 * {@link SystemLogHandler} on the JUL root logger over the protocol v2 transmitter,
 * {@link UltiPanelLogTransmitter#UltiPanelLogTransmitter(UltiPanelLogTransmitter.AgentLineSink)},
 * whose sink records each line exactly as it would be handed to the {@code log.lines} feed.
 */
@DisplayName("Sensitive-command redaction of the cloud log stream (end to end)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SensitiveLogRedactionEndToEndTest {

    private static final String COMMAND_LOG_MESSAGE = "Steve issued server command: /login hunter2";
    private static final String REDACTED_LOG_MESSAGE = "Steve issued server command: /login ***";

    private final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
    private final List<String> sent = new ArrayList<>();
    private UltiPanelLogTransmitter transmitter;
    private SystemLogHandler streamHandler;
    private LocalRecordCapture localCapture;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        Logger mockLogger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
            lenient().when(ultiTools.getSensitiveCommandRegistry()).thenReturn(registry);
        });

        localCapture = new LocalRecordCapture();
        Logger.getLogger("").addHandler(localCapture);
    }

    @AfterEach
    void tearDown() {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.removeHandler(localCapture);
        if (streamHandler != null) {
            rootLogger.removeHandler(streamHandler);
        }
        if (transmitter != null) {
            transmitter.shutdown();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /** Starts the live stream: the v2 transmitter's handler on the root logger. */
    private void startStream() {
        transmitter = new UltiPanelLogTransmitter((timestamp, level, message) -> {
            synchronized (sent) {
                sent.add(message);
            }
        });
        streamHandler = new SystemLogHandler(transmitter);
        Logger.getLogger("").addHandler(streamHandler);
    }

    /** The lines handed to the panel so far. */
    private List<String> sentLines() {
        synchronized (sent) {
            return new ArrayList<>(sent);
        }
    }

    /** Publishes one record through the root logger and returns the line the panel received. */
    private String publishAndCapture(String message) {
        Logger.getLogger("").log(new LogRecord(Level.INFO, message));
        assertThat(sentLines()).as("exactly one line handed to the panel").hasSize(1);
        return sentLines().get(0);
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

        // log.lines has no stack-trace field: the redacted trace follows the message.
        assertThat(sentLines()).hasSize(1);
        assertThat(sentLines().get(0)).startsWith("command failed")
                .contains("while running /login ***").doesNotContain("hunter2");
    }

    @Test
    @DisplayName("a start-up record replayed once the stream opens is redacted the same way")
    void replayedRecordIsRedacted() {
        startStream();
        // Registered after the record was captured but before the replay is built: the module
        // enabled between the capture and the stream opening.
        registerLoginLikeAModule();

        transmitter.replayLog("info", COMMAND_LOG_MESSAGE, "server", null, 1L);

        assertThat(sentLines()).containsExactly(REDACTED_LOG_MESSAGE);
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

        assertThat(sentLines()).containsExactly(REDACTED_LOG_MESSAGE);
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
