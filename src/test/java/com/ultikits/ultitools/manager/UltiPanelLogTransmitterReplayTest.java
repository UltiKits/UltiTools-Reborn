package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * The transmitter's replay of start-up records (#487): fixed chunks bounded by a byte budget, sent
 * about one second apart, independent of the live {@code batch.size} / {@code batch.interval}.
 * <p>
 * The panel's quota counts messages (50 in 10 seconds per client), not records, and the Worker sets
 * no record limit per {@code log_batch}; the platform caps one WebSocket message at 1 MiB. So a
 * chunk is as many records as fit in 64 KiB of serialized frame, and chunks go out one per second,
 * which uses at most 10 of the 50 messages and leaves the rest for live traffic. A full start-up
 * buffer (2,000 records) therefore drains in seconds. The literals below are that contract.
 */
@DisplayName("UltiPanelLogTransmitter replay")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiPanelLogTransmitterReplayTest {

    /** The byte budget of one replay frame, serialized as the client sends it. */
    private static final int CHUNK_BUDGET_BYTES = 64 * 1024;

    /** The spacing between replay frames. */
    private static final long SPACING_MS = 1000L;

    private static final Gson WIRE_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final List<JsonObject> sent = new CopyOnWriteArrayList<>();
    private final List<Long> sentAt = new CopyOnWriteArrayList<>();
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
            sentAt.add(System.nanoTime());
            return null;
        }).when(client).sendMessage(any());
        transmitter = new UltiPanelLogTransmitter(client, "server-1");
        transmitter.setBatchEnabled(false);
        // Live batch settings deliberately far from the replay's: the replay must not follow them.
        transmitter.setBatchSize(10);
        transmitter.setIntervalMs(600_000);
    }

    @AfterEach
    void tearDown() {
        transmitter.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private static String text(int length, char fill) {
        return String.join("", Collections.nCopies(length, String.valueOf(fill)));
    }

    private void replay(int count, int messageLength) {
        for (int i = 1; i <= count; i++) {
            String padding = messageLength > 0 ? " " + text(messageLength, 'x') : "";
            transmitter.replayLog("info", "early " + i + padding, "server", null, 1000L + i);
        }
    }

    private List<JsonObject> replayFrames() {
        List<JsonObject> frames = new ArrayList<>();
        for (JsonObject frame : sent) {
            if ("log_batch".equals(frame.get("type").getAsString())) {
                frames.add(frame);
            }
        }
        return frames;
    }

    private List<String> replayedIds() {
        List<String> ids = new ArrayList<>();
        for (JsonObject frame : replayFrames()) {
            for (JsonElement log : frame.getAsJsonArray("data")) {
                String message = log.getAsJsonObject().get("message").getAsString();
                int space = message.indexOf(' ', "early ".length());
                ids.add(space < 0 ? message : message.substring(0, space));
            }
        }
        return ids;
    }

    private static List<String> expected(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add("early " + i);
        }
        return ids;
    }

    private static int wireBytes(JsonObject frame) {
        return WIRE_GSON.toJson(frame).getBytes(StandardCharsets.UTF_8).length;
    }

    private void drainManually(int maxTicks) {
        for (int i = 0; i < maxTicks && transmitter.getReplayQueueSize() > 0; i++) {
            transmitter.sendReplayBatch();
        }
    }

    @Test
    @DisplayName("a chunk is not tied to batch.size: 25 small records go out in one frame")
    void smallReplayIsOneFrame() {
        replay(25, 0);

        drainManually(5);

        assertThat(replayFrames()).hasSize(1);
        assertThat(replayedIds()).containsExactlyElementsOf(expected(25));
    }

    @Test
    @DisplayName("no replay frame exceeds the byte budget, and every frame but the last is filled")
    void chunkNeverExceedsBudget() {
        replay(300, 1000);

        drainManually(100);

        List<JsonObject> frames = replayFrames();
        assertThat(frames).hasSizeGreaterThan(1);
        for (JsonObject frame : frames) {
            assertThat(wireBytes(frame)).as("serialized frame").isLessThanOrEqualTo(CHUNK_BUDGET_BYTES);
        }
        for (int i = 0; i < frames.size() - 1; i++) {
            assertThat(wireBytes(frames.get(i))).as("frame %d is filled before the next starts", i)
                    .isGreaterThan(CHUNK_BUDGET_BYTES - 2 * 1100);
        }
        assertThat(replayedIds()).containsExactlyElementsOf(expected(300));
    }

    @Test
    @DisplayName("a full start-up buffer (2,000 records) drains in a handful of frames")
    void fullBufferDrainsInFewFrames() {
        replay(2000, 120);
        long totalEntryBytes = 0;

        drainManually(200);

        List<JsonObject> frames = replayFrames();
        for (JsonObject frame : frames) {
            totalEntryBytes += wireBytes(frame);
        }
        long expectedFrames = (totalEntryBytes + CHUNK_BUDGET_BYTES - 1) / CHUNK_BUDGET_BYTES + 1;
        assertThat((long) frames.size()).isLessThanOrEqualTo(expectedFrames);
        assertThat(frames.size()).as("2,000 records at one frame per second drain in seconds").isLessThanOrEqualTo(20);
        assertThat(replayedIds()).containsExactlyElementsOf(expected(2000));
    }

    @Test
    @DisplayName("a single record larger than the budget is shortened to fit, not dropped")
    void oversizedRecordIsShortenedToFit() {
        transmitter.replayLog("error", "huge " + text(200_000, 'y'), "server", new IllegalStateException("boom"), 5L);

        drainManually(3);

        assertThat(replayFrames()).hasSize(1);
        JsonObject frame = replayFrames().get(0);
        assertThat(wireBytes(frame)).isLessThanOrEqualTo(CHUNK_BUDGET_BYTES);
        assertThat(frame.getAsJsonArray("data").get(0).getAsJsonObject().get("message").getAsString())
                .startsWith("huge ");
    }

    @Test
    @DisplayName("the first frame goes out at once, the rest about one second apart, not at batch.interval")
    void framesAreSpacedByAboutOneSecond() throws InterruptedException {
        replay(150, 1000);

        transmitter.startReplay();
        assertThat(replayFrames()).hasSize(1);

        long deadline = System.currentTimeMillis() + 10_000;
        while (transmitter.getReplayQueueSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(transmitter.getReplayQueueSize()).as("drained well before batch.interval (600 s)").isZero();
        assertThat(replayFrames()).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 1; i < sentAt.size(); i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(sentAt.get(i) - sentAt.get(i - 1));
            assertThat(gapMs).as("gap before frame %d", i).isBetween(SPACING_MS - 100, SPACING_MS + 900);
        }
        assertThat(replayedIds()).containsExactlyElementsOf(expected(150));
    }

    @Test
    @DisplayName("each replayed entry keeps the time its record was logged")
    void replayKeepsRecordTime() {
        replay(1, 0);
        transmitter.sendReplayBatch();

        JsonArray data = replayFrames().get(0).getAsJsonArray("data");
        assertThat(data.get(0).getAsJsonObject().get("timestamp").getAsLong()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("a live record during the replay still goes out on its own, at once")
    void liveRecordIsNotHeldBack() {
        replay(300, 1000);
        transmitter.sendReplayBatch();

        transmitter.sendLog("info", "live", "server", null);

        JsonObject last = sent.get(sent.size() - 1);
        assertThat(last.get("type").getAsString()).isEqualTo("log_stream");
        assertThat(last.getAsJsonObject("data").get("message").getAsString()).isEqualTo("live");
        assertThat(transmitter.getReplayQueueSize()).isPositive();
    }

    @Test
    @DisplayName("a chunk that does not go out is kept at the front and sent once")
    void failedChunkIsKeptNotDuplicated() {
        replay(300, 1000);
        connected.set(false);
        transmitter.sendReplayBatch();
        assertThat(sent).isEmpty();

        connected.set(true);
        // A send into a closing socket: sendMessage returns, the client is no longer connected.
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            connected.set(false);
            return null;
        }).doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(client).sendMessage(any());
        transmitter.sendReplayBatch();
        assertThat(transmitter.getReplayQueueSize()).as("the undelivered chunk is back at the front").isEqualTo(300);

        sent.clear();
        connected.set(true);
        drainManually(100);
        assertThat(replayedIds()).containsExactlyElementsOf(expected(300));
    }

    @Test
    @DisplayName("on a reconnect the records not yet replayed are handed over first, in order")
    void pendingReplayIsHandedOver() {
        replay(300, 1000);
        transmitter.sendReplayBatch();
        int sentCount = replayedIds().size();

        JsonArray pending = transmitter.takePending();

        List<String> ids = new ArrayList<>();
        for (JsonElement log : pending) {
            String message = log.getAsJsonObject().get("message").getAsString();
            ids.add(message.substring(0, message.indexOf(' ', "early ".length())));
        }
        List<String> all = expected(300);
        assertThat(ids).containsExactlyElementsOf(all.subList(sentCount, 300));
        assertThat(transmitter.getReplayQueueSize()).isZero();
    }
}
