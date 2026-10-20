package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * #486: a log batch whose send fails is held and sent first on the next attempt, and no newer record
 * is drained while it is held -- on the transmitter's own batch sender and on the monitor's external
 * drain ({@code batch_update}).
 * <p>
 * Before the fix both paths polled the records off the queue before sending and put nothing back,
 * so a socket that closed around the send lost the batch without a trace.
 * <p>
 * A send fails here in the two ways the real client fails: {@code send} throws
 * {@link WebsocketNotConnectedException}, or the socket is already closed and {@code sendMessage}
 * returns having sent nothing (the client then reports itself disconnected).
 */
@DisplayName("A failed log batch is held and sent first (#486)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LogBatchHoldTest {

    /** The client the records travel through; records every frame it was asked to send. */
    private UltiPanelWebSocketClient client;
    private final List<JsonObject> attempted = new ArrayList<>();
    private final List<JsonObject> delivered = new ArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    /** How many of the next sends fail, and how. */
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicBoolean failByClosing = new AtomicBoolean();

    private final List<UltiPanelLogTransmitter> transmitters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        client = mock(UltiPanelWebSocketClient.class);
        lenient().when(client.isConnected()).thenAnswer(inv -> connected.get());
        lenient().when(client.getServerId()).thenReturn("test-server");
        doAnswer(inv -> {
            JsonObject frame = inv.getArgument(0);
            attempted.add(frame);
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                if (failByClosing.get()) {
                    // The socket closed first: the real client logs a panel-connection line and returns.
                    connected.set(false);
                    return null;
                }
                throw new WebsocketNotConnectedException();
            }
            delivered.add(frame);
            return null;
        }).when(client).sendMessage(any(JsonObject.class));
    }

    @AfterEach
    void tearDown() {
        transmitters.forEach(UltiPanelLogTransmitter::shutdown);
        MockBukkitHelper.safeUnmock();
    }

    private UltiPanelLogTransmitter transmitter() {
        UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(client, "test-server");
        transmitters.add(transmitter);
        return transmitter;
    }

    private static List<String> messages(JsonArray logs) {
        List<String> messages = new ArrayList<>();
        for (JsonElement log : logs) {
            messages.add(log.getAsJsonObject().get("message").getAsString());
        }
        return messages;
    }

    /** The messages each delivered frame carried, frame by frame. */
    private List<List<String>> deliveredLogs() {
        List<List<String>> frames = new ArrayList<>();
        for (JsonObject frame : delivered) {
            JsonElement data = frame.get("data");
            JsonArray logs = data.isJsonArray() ? data.getAsJsonArray() : data.getAsJsonObject().getAsJsonArray("logs");
            frames.add(messages(logs));
        }
        return frames;
    }

    @Nested
    @DisplayName("the transmitter's own batch sender")
    class OwnSender {

        @BeforeEach
        void publishUltiTools() {
            TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger())
                    .thenReturn(Logger.getLogger("LogBatchHoldTest")));
        }

        @Test
        @DisplayName("a send that throws: the batch is sent first on the next attempt, before any newer record")
        void throwingSendIsHeldAndSentFirst() {
            UltiPanelLogTransmitter transmitter = transmitter();
            transmitter.info("A", "test");
            transmitter.info("B", "test");
            failuresLeft.set(1);

            transmitter.flushLogs();
            transmitter.info("C", "test");
            transmitter.flushLogs();

            assertThat(deliveredLogs()).containsExactly(
                    java.util.Arrays.asList("A", "B"), java.util.Collections.singletonList("C"));
        }

        @Test
        @DisplayName("a send into an already-closed socket: the batch is held, then sent after reconnecting")
        void closedSocketSendIsHeldAndSentAfterReconnect() {
            UltiPanelLogTransmitter transmitter = transmitter();
            transmitter.info("A", "test");
            failByClosing.set(true);
            failuresLeft.set(1);

            transmitter.flushLogs();
            connected.set(true);
            transmitter.info("B", "test");
            transmitter.flushLogs();

            assertThat(deliveredLogs()).containsExactly(
                    java.util.Collections.singletonList("A"), java.util.Collections.singletonList("B"));
        }

        @Test
        @DisplayName("while a batch is held and sends keep failing, no newer record is drained")
        void noNewerRecordIsDrainedWhileHeld() {
            UltiPanelLogTransmitter transmitter = transmitter();
            transmitter.info("A", "test");
            failuresLeft.set(Integer.MAX_VALUE);

            transmitter.flushLogs();
            transmitter.info("B", "test");
            transmitter.flushLogs();

            assertThat(transmitter.getQueueSize()).as("B stays queued behind the held batch").isEqualTo(1);
            assertThat(delivered).isEmpty();
            for (JsonObject frame : attempted) {
                assertThat(messages(frame.getAsJsonArray("data"))).containsExactly("A");
            }
        }
    }

    @Nested
    @DisplayName("the monitor's external drain (batch_update)")
    class ExternalDrain {

        private ServerMonitorManager monitor;
        private UltiPanelLogTransmitter transmitter;

        @BeforeEach
        void wireMonitor() {
            UltiPanelWebSocketClient transmitterClient = mock(UltiPanelWebSocketClient.class);
            lenient().when(transmitterClient.isConnected()).thenReturn(true);
            transmitter = new UltiPanelLogTransmitter(transmitterClient, "test-server");
            transmitters.add(transmitter);
            LogStreamManager logStreamManager = mock(LogStreamManager.class);
            lenient().when(logStreamManager.getLogTransmitter()).thenReturn(transmitter);
            ErrorReportCollector errors = mock(ErrorReportCollector.class);
            lenient().when(errors.drainErrors(anyInt())).thenReturn(new JsonArray());
            YamlConfiguration config = new YamlConfiguration();
            config.set(Capability.LOGS.getConfigPath(), true);
            TestHelper.mockUltiToolsInstance(ultiTools -> {
                lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("LogBatchHoldTest"));
                lenient().when(ultiTools.getConfig()).thenReturn(config);
                lenient().when(ultiTools.getLogStreamManager()).thenReturn(logStreamManager);
                lenient().when(ultiTools.getErrorReportCollector()).thenReturn(errors);
            });
            monitor = new ServerMonitorManager();
            monitor.setWebSocketClient(client);
        }

        @AfterEach
        void stopMonitor() {
            monitor.stopMonitoring();
        }

        @Test
        @DisplayName("a batch_update whose send throws: its logs go out first on the next drain")
        void throwingExternalSendIsHeld() {
            transmitter.info("A", "test");
            failuresLeft.set(1);

            monitor.drainLogsNow();
            transmitter.info("B", "test");
            monitor.drainLogsNow();
            monitor.drainLogsNow();

            assertThat(deliveredLogs()).containsExactly(
                    java.util.Collections.singletonList("A"), java.util.Collections.singletonList("B"));
        }

        @Test
        @DisplayName("a batch_update sent into an already-closed socket: its logs go out after reconnecting")
        void closedSocketExternalSendIsHeld() {
            transmitter.info("A", "test");
            failByClosing.set(true);
            failuresLeft.set(1);

            monitor.drainLogsNow();
            connected.set(true);
            transmitter.info("B", "test");
            monitor.drainLogsNow();
            monitor.drainLogsNow();

            assertThat(deliveredLogs()).containsExactly(
                    java.util.Collections.singletonList("A"), java.util.Collections.singletonList("B"));
        }
    }
}
