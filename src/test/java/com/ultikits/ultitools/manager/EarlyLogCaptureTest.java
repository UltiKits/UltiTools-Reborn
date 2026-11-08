package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #487: records logged after {@code UltiTools#onLoad()} and before the log stream starts
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

    @Nested
    @DisplayName("through the protocol v2 log handler")
    class EndToEnd {

        private final List<String> sent = new CopyOnWriteArrayList<>();
        private UltiPanelLogTransmitter transmitter;
        private SystemLogHandler handler;

        @AfterEach
        void detachHandler() {
            if (handler != null) {
                Logger.getLogger("").removeHandler(handler);
            }
            if (transmitter != null) {
                transmitter.shutdown();
            }
        }

        @Test
        @DisplayName("lines logged before the stream starts arrive first, oldest first, then live lines")
        void earlyLinesArriveFirstInOrder() {
            EarlyLogCapture.start(Collections.emptyList());
            bukkitLogger.info("early line 1");
            bukkitLogger.info("early line 2");

            // What AgentOutboundFeeds#ensureLogCapture does when the v2 stream starts.
            transmitter = new UltiPanelLogTransmitter((timestamp, level, message) -> sent.add(message));
            handler = new SystemLogHandler(transmitter);
            Logger root = Logger.getLogger("");
            EarlyLogCapture.drainInto(handler.replayHandler(), () -> root.addHandler(handler));
            bukkitLogger.info("live line 1");

            assertThat(sent).containsSubsequence("early line 1", "early line 2", "live line 1");
            assertThat(sent.indexOf("early line 2")).isLessThan(sent.indexOf("live line 1"));
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
