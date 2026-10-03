package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * The transmitter's batched replay of start-up records (#487): batch size, the live path beside it,
 * and that a batch which does not go out is neither lost nor duplicated.
 */
@DisplayName("UltiPanelLogTransmitter replay")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiPanelLogTransmitterReplayTest {

    private final List<JsonObject> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private UltiPanelLogTransmitter transmitter;
    private UltiPanelWebSocketClient client;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance(ultiTools ->
                lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("UltiPanelLogTransmitterReplayTest")));
        client = mock(UltiPanelWebSocketClient.class);
        when(client.isConnected()).thenAnswer(inv -> connected.get());
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(client).sendMessage(any());
        transmitter = new UltiPanelLogTransmitter(client, "server-1");
        transmitter.setBatchEnabled(false);
        transmitter.setBatchSize(10);
        // Long enough that no scheduled replay tick runs during a test; the tests drive the ticks.
        transmitter.setIntervalMs(600_000);
    }

    @AfterEach
    void tearDown() {
        transmitter.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private void replay(int count) {
        for (int i = 1; i <= count; i++) {
            transmitter.replayLog("info", "early " + i, "server", null, 1000L + i);
        }
    }

    private List<String> replayedMessages() {
        List<String> messages = new ArrayList<>();
        for (JsonObject frame : sent) {
            if ("log_batch".equals(frame.get("type").getAsString())) {
                for (JsonElement log : frame.getAsJsonArray("data")) {
                    messages.add(log.getAsJsonObject().get("message").getAsString());
                }
            }
        }
        return messages;
    }

    private static List<String> expected(int count) {
        List<String> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add("early " + i);
        }
        return messages;
    }

    @Test
    @DisplayName("nothing is sent before startReplay; then the first batch at once, the rest one per tick")
    void replayIsBatched() {
        replay(25);
        assertThat(sent).isEmpty();

        transmitter.startReplay();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getAsJsonArray("data")).hasSize(10);

        transmitter.sendReplayBatch();
        transmitter.sendReplayBatch();
        transmitter.sendReplayBatch();

        assertThat(sent).hasSize(3);
        assertThat(sent.get(2).getAsJsonArray("data")).hasSize(5);
        assertThat(replayedMessages()).containsExactlyElementsOf(expected(25));
        assertThat(transmitter.getReplayQueueSize()).isZero();
    }

    @Test
    @DisplayName("each replayed entry keeps the time its record was logged")
    void replayKeepsRecordTime() {
        replay(1);
        transmitter.startReplay();

        JsonArray data = sent.get(0).getAsJsonArray("data");
        assertThat(data.get(0).getAsJsonObject().get("timestamp").getAsLong()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("a live record during the replay still goes out on its own, at once")
    void liveRecordIsNotHeldBack() {
        replay(25);
        transmitter.startReplay();

        transmitter.sendLog("info", "live", "server", null);

        JsonObject last = sent.get(sent.size() - 1);
        assertThat(last.get("type").getAsString()).isEqualTo("log_stream");
        assertThat(last.getAsJsonObject("data").get("message").getAsString()).isEqualTo("live");
    }

    @Test
    @DisplayName("a batch that does not go out is kept and sent once on the next tick")
    void failedBatchIsKeptNotDuplicated() {
        replay(15);
        connected.set(false);
        transmitter.startReplay();
        assertThat(sent).isEmpty();

        connected.set(true);
        // A send that goes into a closing socket: sendMessage returns, the client is no longer connected.
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            connected.set(false);
            return null;
        }).doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(client).sendMessage(any());
        transmitter.sendReplayBatch();
        assertThat(transmitter.getReplayQueueSize()).as("the undelivered batch is back at the front").isEqualTo(15);

        sent.clear();
        connected.set(true);
        transmitter.sendReplayBatch();
        transmitter.sendReplayBatch();
        assertThat(replayedMessages()).containsExactlyElementsOf(expected(15));
    }

    @Test
    @DisplayName("on a reconnect the records not yet replayed are handed over first, in order")
    void pendingReplayIsHandedOver() {
        replay(25);
        transmitter.startReplay();

        JsonArray pending = transmitter.takePending();

        List<String> messages = new ArrayList<>();
        for (JsonElement log : pending) {
            messages.add(log.getAsJsonObject().get("message").getAsString());
        }
        assertThat(messages).startsWith("early 11", "early 12").contains("early 25");
        assertThat(transmitter.getReplayQueueSize()).isZero();
    }
}
