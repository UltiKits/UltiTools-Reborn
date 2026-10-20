package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * #487: records logged after {@code UltiTools#onLoad()} and before {@code LogStreamManager#initialize}
 * reach the panel's log stream, oldest first and before live records, from a bounded buffer that is
 * released when the stream does not start in time.
 * <p>
 * Before the fix the capture was attached inside {@code initialize()}, so the whole early boot --
 * module loading, dependency resolution, the framework's own start-up diagnostics -- never reached
 * the panel.
 */
@DisplayName("Early boot log lines reach the panel's stream (#487)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EarlyLogCaptureTest {

    private final Logger bukkitLogger = Logger.getLogger("Minecraft");

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("EarlyLogCaptureTest"));
            lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration());
            lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        });
    }

    @AfterEach
    void tearDown() {
        EarlyLogCapture.release();
        MockBukkitHelper.safeUnmock();
    }

    /** A local WebSocket server that records every text frame it receives. */
    static final class CapturingServer extends WebSocketServer {
        final List<JsonObject> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);

        CapturingServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // Nothing to send.
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            // Nothing to release.
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            frames.add(JsonParser.parseString(message).getAsJsonObject());
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            // Surfaced by the assertions.
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        /** The log messages of every log_batch / log_stream frame received, in arrival order. */
        List<String> logMessages() {
            List<String> messages = new ArrayList<>();
            for (JsonObject frame : frames) {
                String type = frame.get("type").getAsString();
                if ("log_batch".equals(type)) {
                    for (JsonElement log : frame.getAsJsonArray("data")) {
                        messages.add(log.getAsJsonObject().get("message").getAsString());
                    }
                } else if ("log_stream".equals(type)) {
                    messages.add(frame.getAsJsonObject("data").get("message").getAsString());
                }
            }
            return messages;
        }
    }

    @Nested
    @DisplayName("through a real WebSocket connection")
    class EndToEnd {

        private CapturingServer server;
        private UltiPanelWebSocketClient client;

        @BeforeEach
        void startServer() throws InterruptedException {
            server = new CapturingServer();
            server.start();
            assertThat(server.started.await(10, TimeUnit.SECONDS)).isTrue();
        }

        @AfterEach
        void stopServer() throws InterruptedException {
            LogStreamManager.getInstance().shutdown();
            if (client != null) {
                client.disconnect();
            }
            server.stop(1000);
        }

        @Test
        @DisplayName("lines logged before the stream starts arrive first, oldest first, then live lines")
        void earlyLinesArriveFirstInOrder() throws Exception {
            EarlyLogCapture.start(Collections.emptyList());
            bukkitLogger.info("early line 1");
            bukkitLogger.info("early line 2");

            client = new UltiPanelWebSocketClient("ws://127.0.0.1:" + server.getPort(), "test-server", "token");
            assertThat(client.connectBlocking(10, TimeUnit.SECONDS)).isTrue();
            LogStreamManager.getInstance().initialize(client);
            bukkitLogger.info("live line 1");
            LogStreamManager.getInstance().getLogTransmitter().flushLogs();

            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.logMessages().contains("live line 1") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            List<String> received = server.logMessages();
            assertThat(received).containsSubsequence("early line 1", "early line 2", "live line 1");
            assertThat(received.indexOf("early line 2")).isLessThan(received.indexOf("live line 1"));
        }
    }

    @Nested
    @DisplayName("the buffer itself")
    class Buffer {

        private final List<LogRecord> replayed = new ArrayList<>();
        private final Handler live = new Handler() {
            @Override
            public void publish(LogRecord record) {
                replayed.add(record);
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };

        private List<String> replayedMessages() {
            List<String> messages = new ArrayList<>();
            for (LogRecord record : replayed) {
                messages.add(record.getMessage());
            }
            return messages;
        }

        private void log(String loggerName, Level level, String message) {
            LogRecord record = new LogRecord(level, message);
            record.setLoggerName(loggerName);
            Logger.getLogger("").log(record);
        }

        @Test
        @DisplayName("bounded by count: the oldest records are kept and the rest are counted as dropped")
        void boundedByCount() {
            EarlyLogCapture.start(Collections.emptyList(), 3, Long.MAX_VALUE, 60_000L, System::currentTimeMillis);
            for (int i = 1; i <= 5; i++) {
                log("Minecraft", Level.INFO, "count " + i);
            }

            int dropped = EarlyLogCapture.drainInto(live, () -> { });

            assertThat(replayedMessages()).containsExactly("count 1", "count 2", "count 3");
            assertThat(dropped).isEqualTo(2);
        }

        @Test
        @DisplayName("bounded by bytes: a record that would exceed the budget is dropped")
        void boundedByBytes() {
            EarlyLogCapture.start(Collections.emptyList(), 100, 150, 60_000L, System::currentTimeMillis);
            log("Minecraft", Level.INFO, "0123456789");
            log("Minecraft", Level.INFO, "this message is far too long to fit into what is left of a 150 byte budget");

            int dropped = EarlyLogCapture.drainInto(live, () -> { });

            assertThat(replayedMessages()).containsExactly("0123456789");
            assertThat(dropped).isEqualTo(1);
        }

        @Test
        @DisplayName("released, and replays nothing, when the stream does not start within its limit")
        void releasedWhenTheStreamDoesNotStartInTime() {
            AtomicLong now = new AtomicLong(1_000L);
            EarlyLogCapture.start(Collections.emptyList(), 100, Long.MAX_VALUE, 5_000L, now::get);
            log("Minecraft", Level.INFO, "before the limit");
            now.set(7_000L);
            log("Minecraft", Level.INFO, "after the limit");

            assertThat(Arrays.asList(Logger.getLogger("").getHandlers()))
                    .as("the capture detached itself once past its limit")
                    .noneMatch(handler -> handler instanceof EarlyLogCapture);
            EarlyLogCapture.drainInto(live, () -> { });
            assertThat(replayed).isEmpty();
        }

        @Test
        @DisplayName("the live stream's filters apply at capture: an excluded logger and a debug record are not kept")
        void liveFiltersApplyAtCapture() {
            EarlyLogCapture.start(Collections.singletonList("com.noisy"), 100, Long.MAX_VALUE, 60_000L,
                    System::currentTimeMillis);
            log("com.noisy.Library", Level.INFO, "excluded");
            log("Minecraft", Level.FINE, "debug");
            log("Minecraft", Level.INFO, "kept");

            EarlyLogCapture.drainInto(live, () -> { });

            assertThat(replayedMessages()).containsExactly("kept");
        }

        @Test
        @DisplayName("draining attaches the live handler and detaches the capture")
        void drainingHandsOverToTheLiveHandler() {
            EarlyLogCapture.start(Collections.emptyList());
            List<String> attached = new ArrayList<>();

            EarlyLogCapture.drainInto(live, () -> attached.add("live"));

            assertThat(attached).containsExactly("live");
            assertThat(Arrays.asList(Logger.getLogger("").getHandlers()))
                    .noneMatch(handler -> handler instanceof EarlyLogCapture);
        }
    }
}
