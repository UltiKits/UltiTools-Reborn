package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * The start-up records the early capture replays when the log stream starts (#487) reach the panel
 * in chunked {@code log_batch} frames even when live batching is off, while live records keep going
 * out one by one.
 * <p>
 * Measured on a real server (phase 17, row {@code ultitools.config.config-yml} part (c)): with
 * {@code ultipanel.logging.batch.enabled: false} every replayed record was its own message, about
 * 350 at once on connect, and the panel's per-client quota (50 messages in 10 seconds) rejected
 * them. The replay is now sent in chunks of up to 64 KiB, about one per second, whatever the live
 * batch settings say.
 */
@DisplayName("Early-capture replay is batched even with live batching off")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EarlyLogReplayBatchingTest {

    private static final int EARLY_LINES = 25;
    private static final int BATCH_SIZE = 10;

    private final Logger bukkitLogger = Logger.getLogger("Minecraft");
    private EarlyLogCaptureTest.CapturingServer server;
    private UltiPanelWebSocketClient client;

    @BeforeEach
    void setUp() throws InterruptedException {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        YamlConfiguration config = new YamlConfiguration();
        config.set("ultipanel.logging.batch.enabled", false);
        config.set("ultipanel.logging.batch.size", BATCH_SIZE);
        config.set("ultipanel.logging.batch.interval", 1000);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("EarlyLogReplayBatchingTest"));
            lenient().when(ultiTools.getConfig()).thenReturn(config);
            lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        });
        server = new EarlyLogCaptureTest.CapturingServer();
        server.start();
        assertThat(server.started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        LogStreamManager.getInstance().shutdown();
        if (client != null) {
            client.disconnect();
        }
        server.stop(1000);
        EarlyLogCapture.release();
        MockBukkitHelper.safeUnmock();
    }

    private List<JsonObject> framesOfType(String type) {
        List<JsonObject> frames = new ArrayList<>();
        for (JsonObject frame : server.frames) {
            if (type.equals(frame.get("type").getAsString())) {
                frames.add(frame);
            }
        }
        return frames;
    }

    private static List<String> messagesOf(JsonObject logBatchFrame) {
        List<String> messages = new ArrayList<>();
        for (JsonElement log : logBatchFrame.getAsJsonArray("data")) {
            messages.add(log.getAsJsonObject().get("message").getAsString());
        }
        return messages;
    }

    private void awaitMessages(String... expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> received = server.logMessages();
            boolean all = true;
            for (String message : expected) {
                all &= received.contains(message);
            }
            if (all) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("replayed records go out in byte-budget log_batch frames, not one per record; live records one by one")
    void replayIsBatchedAndLiveIsNot() throws Exception {
        EarlyLogCapture.start(Collections.emptyList());
        for (int i = 1; i <= EARLY_LINES; i++) {
            bukkitLogger.info("early line " + i);
        }

        client = new UltiPanelWebSocketClient("ws://127.0.0.1:" + server.getPort(), "test-server", "token");
        assertThat(client.connectBlocking(10, TimeUnit.SECONDS)).isTrue();
        LogStreamManager.getInstance().initialize(client);
        bukkitLogger.info("live line 1");
        bukkitLogger.info("live line 2");

        awaitMessages("early line " + EARLY_LINES, "live line 1", "live line 2");

        // Every replayed record travels in a log_batch frame; none in a per-line log_stream frame.
        List<JsonObject> batches = framesOfType("log_batch");
        List<String> replayed = new ArrayList<>();
        for (JsonObject batch : batches) {
            replayed.addAll(messagesOf(batch));
        }
        for (JsonObject stream : framesOfType("log_stream")) {
            assertThat(stream.getAsJsonObject("data").get("message").getAsString())
                    .as("no early record is sent as its own message")
                    .doesNotStartWith("early line");
        }

        // The replay is chunked by a byte budget, not by the live batch.size: a few dozen short
        // start-up records fit in one frame, although batch.size is 10.
        assertThat(batches).as("replay frames for %d replayed records", replayed.size()).hasSize(1);
        assertThat(replayed.size()).isGreaterThan(BATCH_SIZE);

        // Live records after the replay are still sent one by one, each in its own log_stream frame.
        List<String> liveStreamed = new ArrayList<>();
        for (JsonObject stream : framesOfType("log_stream")) {
            liveStreamed.add(stream.getAsJsonObject("data").get("message").getAsString());
        }
        assertThat(liveStreamed).contains("live line 1", "live line 2");
        assertThat(replayed).doesNotContain("live line 1", "live line 2");
    }

    @Test
    @DisplayName("the replay loses and duplicates nothing and keeps the early records in order")
    void replayKeepsEveryRecordOnceInOrder() throws Exception {
        EarlyLogCapture.start(Collections.emptyList());
        List<String> early = new ArrayList<>();
        for (int i = 1; i <= EARLY_LINES; i++) {
            early.add("early line " + i);
            bukkitLogger.info("early line " + i);
        }

        client = new UltiPanelWebSocketClient("ws://127.0.0.1:" + server.getPort(), "test-server", "token");
        assertThat(client.connectBlocking(10, TimeUnit.SECONDS)).isTrue();
        LogStreamManager.getInstance().initialize(client);

        awaitMessages("early line " + EARLY_LINES);

        List<String> receivedEarly = new ArrayList<>();
        for (String message : server.logMessages()) {
            if (message.startsWith("early line ")) {
                receivedEarly.add(message);
            }
        }
        assertThat(receivedEarly).as("each early record exactly once, oldest first").containsExactlyElementsOf(early);
    }
}
