package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Case b1fcfdbc (#677): the panel connection drops while {@link EarlyLogCapture#drainInto} hands its
 * start-up records to the stream. Handing a record over is an in-memory step -- the capture has
 * already given the record up -- so a connection that closes halfway must not lose the rest; only
 * sending waits for a connected client.
 * <p>
 * Deterministic end to end: the disconnect is driven by the hand-over itself, right after the first
 * record, never by timing; the capture runs on a fixed clock; nothing is attached to the root logger
 * except the capture, which every test releases.
 */
@DisplayName("A connection drop during the start-up log hand-off loses no record (case b1fcfdbc)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CapturedLogDisconnectHandoffTest {

    private static final int RECORDS = 5;
    private static final String PREFIX = "handoff record ";

    /** Held strongly: {@code Logger.getLogger} keeps only a weak reference. */
    private final Logger recordLogger = Logger.getLogger("CapturedLogDisconnectHandoff.records");

    private final AtomicBoolean firstConnected = new AtomicBoolean(true);
    private final List<JsonObject> sentByFirst = new CopyOnWriteArrayList<>();
    private final List<JsonObject> sentBySecond = new CopyOnWriteArrayList<>();
    private UltiPanelLogTransmitter first;
    private UltiPanelLogTransmitter second;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("CapturedLogDisconnectHandoffTest"));
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        });
        UltiPanelWebSocketClient firstClient = mock(UltiPanelWebSocketClient.class);
        when(firstClient.isConnected()).thenAnswer(inv -> firstConnected.get());
        doAnswer(inv -> sentByFirst.add(inv.getArgument(0))).when(firstClient).sendMessage(any());
        first = quietTransmitter(firstClient);
    }

    @AfterEach
    void tearDown() {
        EarlyLogCapture.release();
        first.shutdown();
        if (second != null) {
            second.shutdown();
        }
        MockBukkitHelper.safeUnmock();
    }

    /** Live batching off and a long live interval, as in the replay suite: only the replay path sends. */
    private static UltiPanelLogTransmitter quietTransmitter(UltiPanelWebSocketClient client) {
        UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(client, "server-1");
        transmitter.setBatchEnabled(false);
        transmitter.setBatchSize(10);
        transmitter.setIntervalMs(600_000);
        return transmitter;
    }

    private static List<String> expected() {
        List<String> messages = new ArrayList<>();
        for (int i = 1; i <= RECORDS; i++) {
            messages.add(PREFIX + i);
        }
        return messages;
    }

    private static List<String> messagesOf(JsonArray records) {
        List<String> messages = new ArrayList<>();
        for (JsonElement record : records) {
            messages.add(record.getAsJsonObject().get("message").getAsString());
        }
        return messages;
    }

    private static List<String> batchedMessages(List<JsonObject> frames) {
        List<String> messages = new ArrayList<>();
        for (JsonObject frame : frames) {
            if ("log_batch".equals(frame.get("type").getAsString())) {
                messages.addAll(messagesOf(frame.getAsJsonArray("data")));
            }
        }
        return messages;
    }

    private static boolean captureAttached() {
        return Arrays.stream(Logger.getLogger("").getHandlers()).anyMatch(EarlyLogCapture.class::isInstance);
    }

    @Test
    @DisplayName("records handed over after the socket closed are kept, sent only once connected, oldest first")
    void disconnectDuringCaptureHandoffKeepsEveryRecord() {
        EarlyLogCapture.start(Collections.emptyList(), 100, Long.MAX_VALUE, 60_000L, () -> 1_000L);
        for (int i = 1; i <= RECORDS; i++) {
            recordLogger.info(PREFIX + i);
        }

        Handler replay = new SystemLogHandler(first).replayHandler();
        Handler handOffThatLosesTheSocket = new Handler() {
            private boolean forwardedOne;

            @Override
            public void publish(LogRecord record) {
                replay.publish(record);
                if (!forwardedOne) {
                    forwardedOne = true;
                    // The socket leaves the open state while the capture is still handing records over.
                    firstConnected.set(false);
                }
            }

            @Override
            public void flush() {
                // Nothing buffered: every record is forwarded at once.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        AtomicBoolean attached = new AtomicBoolean();

        EarlyLogCapture.drainInto(handOffThatLosesTheSocket, () -> attached.set(true));

        assertThat(attached).as("the attach action ran").isTrue();
        assertThat(captureAttached()).as("the capture detached itself after the hand-off").isFalse();
        assertThat(firstConnected).as("the drop happened during the hand-off").isFalse();
        assertThat(first.getReplayQueueSize()).as("every handed-over record is admitted to the replay queue")
                .isEqualTo(RECORDS);

        first.startReplay();
        assertThat(sentByFirst).as("nothing is sent while disconnected").isEmpty();

        // The reconnect on a new client, in LogStreamManager#initialize's order.
        first.shutdown();
        JsonArray pending = first.takePending();
        assertThat(messagesOf(pending)).as("handed to the next transmitter, oldest first")
                .containsExactlyElementsOf(expected());
        assertThat(sentByFirst).as("nothing reached the closed connection").isEmpty();

        UltiPanelWebSocketClient secondClient = mock(UltiPanelWebSocketClient.class);
        when(secondClient.isConnected()).thenReturn(true);
        doAnswer(inv -> sentBySecond.add(inv.getArgument(0))).when(secondClient).sendMessage(any());
        second = quietTransmitter(secondClient);
        second.adoptPending(pending);
        second.flushLogs();

        assertThat(batchedMessages(sentBySecond)).as("delivered once each, in order, over the new connection")
                .containsExactlyElementsOf(expected());
    }
}
