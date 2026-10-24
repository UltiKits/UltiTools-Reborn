package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Contract rules for log egress: per-line byte limit with the {@code …[truncated]} suffix, batch
 * line count, frame byte budget, the local token bucket, the bounded closed-channel buffer and the
 * once-a-minute FINE summary of locally dropped lines.
 */
class AgentLogBatcherTest {

    private static final long START = 1761274800000L;
    private static final String SUFFIX = "…[truncated]";

    @Test
    void overlongMessagesAreCutToTheLineLimitAndMarked() {
        for (int lineMaxBytes : new int[] {8192, 256}) {
            AgentLogBatcher batcher = new AgentLogBatcher(() -> START);
            RecordingChannel channel = new RecordingChannel();
            channel.setLimits(limits(50, 200, lineMaxBytes, 100, 1048576));

            String message = multiByteMessage();
            batcher.offer(START, Level.INFO, message);
            batcher.flush(channel);

            JsonArray lines = onlyLines(channel.frames().get(0));
            assertThat(lines).hasSize(1);
            JsonObject line = lines.get(0).getAsJsonObject();
            String cut = line.get("message").getAsString();
            assertThat(utf8(line.toString())).isLessThanOrEqualTo(lineMaxBytes);
            assertThat(cut).endsWith(SUFFIX).startsWith("漢😀ab").hasSizeLessThan(message.length());
            assertThat(hasLoneSurrogate(cut)).as("a cut never splits a surrogate pair").isFalse();
        }
    }

    @Test
    void aBatchNeverExceedsTheLineCountAndTheFramesFollowOneAnother() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();
        channel.setLimits(limits(1000, 1000, 8192, 100, 1048576));
        batcher.flush(channel);
        clock.addAndGet(1000L);

        for (int i = 0; i < 250; i++) {
            batcher.offer(START, Level.INFO, "line " + i);
        }
        batcher.flush(channel);
        batcher.flush(channel);
        batcher.flush(channel);

        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(3);
        assertThat(sizes(frames)).containsExactly(100, 100, 50);
    }

    @Test
    void aFrameNeverExceedsTheFrameByteBudget() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();
        channel.setLimits(limits(1000, 1000, 8192, 100, 65536));
        batcher.flush(channel);
        clock.addAndGet(1000L);

        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            message.append('a');
        }
        for (int i = 0; i < 250; i++) {
            batcher.offer(START, Level.INFO, message.toString());
        }
        drain(batcher, channel);

        List<JsonObject> frames = channel.frames();
        assertThat(frames).isNotEmpty();
        int total = 0;
        for (JsonObject frame : frames) {
            assertThat(utf8(frame.toString())).isLessThanOrEqualTo(65536);
            int size = onlyLines(frame).size();
            assertThat(size).isLessThanOrEqualTo(100).isPositive();
            total += size;
        }
        assertThat(total).isEqualTo(250);
        assertThat(sizes(frames)).anySatisfy(size -> assertThat(size).isLessThan(100));
    }

    @Test
    void theLocalTokenBucketDropsOverTheLimitCountsThemAndTellsNobody() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();

        try (LogCapture capture = new LogCapture(AgentLogBatcher.class)) {
            offerMany(batcher, 500, START);
            drain(batcher, channel);
            assertThat(batcher.droppedByRateLimit()).isEqualTo(300);
            assertThat(capture.fines()).hasSize(1);
            assertThat(capture.fines().get(0)).contains("dropped 300 log line(s)");

            clock.addAndGet(1000L);
            offerMany(batcher, 100, START);
            drain(batcher, channel);
            assertThat(batcher.droppedByRateLimit()).isEqualTo(350);
            assertThat(capture.fines()).as("at most one summary per minute").hasSize(1);
            assertThat(totalLines(channel.frames())).isEqualTo(250);

            clock.addAndGet(61_000L);
            offerMany(batcher, 500, START);
            drain(batcher, channel);
            assertThat(capture.fines()).hasSize(2);
            assertThat(capture.fines().get(1)).contains("dropped 650 log line(s)");
        }

        for (JsonObject frame : channel.frames()) {
            assertThat(frame.get("type").getAsString()).isEqualTo("log.lines");
            JsonObject payload = frame.getAsJsonObject("payload");
            assertThat(payload.keySet()).containsExactly("lines");
            for (int i = 0; i < onlyLines(frame).size(); i++) {
                JsonObject line = onlyLines(frame).get(i).getAsJsonObject();
                assertThat(line.keySet()).containsExactlyInAnyOrder("ts", "level", "message");
            }
        }
    }

    @Test
    void aFullBatchIsReportedSoTheCallerCanFlushWithoutWaitingForTheTick() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();
        channel.setLimits(limits(1000, 200, 8192, 10, 1048576));
        // A flush with nothing buffered is how the batcher learns a connection's limits.
        batcher.flush(channel);
        clock.addAndGet(1000L);

        for (int i = 0; i < 9; i++) {
            assertThat(batcher.offer(START, Level.INFO, "line " + i)).as("line %d", i).isFalse();
        }
        assertThat(batcher.offer(START, Level.INFO, "line 9")).as("the tenth line fills the batch").isTrue();

        batcher.flush(channel);
        assertThat(sizes(channel.frames())).containsExactly(10);
        assertThat(batcher.offer(START, Level.INFO, "line 10")).as("a flush empties the batch").isFalse();
    }

    @Test
    void aBurstAboveOneBatchPerSecondIsNotDroppedWhenTheCallerFlushesOnAFullBatch() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();
        // Ten batches a second: anything held back for a whole tick overflows the burst-sized buffer.
        channel.setLimits(limits(1000, 200, 8192, 100, 1048576));
        batcher.flush(channel);

        for (int i = 0; i < 1000; i++) {
            clock.addAndGet(1L);
            if (batcher.offer(START + i, Level.INFO, "line " + i)) {
                batcher.flush(channel);
            }
        }
        batcher.flush(channel);

        assertThat(batcher.droppedByRateLimit()).as("the bucket admitted every line").isZero();
        assertThat(totalLines(channel.frames())).isEqualTo(1000);
        assertThat(sizes(channel.frames())).hasSize(10).containsOnly(100);
    }

    @Test
    void aClosedChannelBuffersAtMostOneBurstAndDropsTheOldest() {
        AtomicLong clock = new AtomicLong(START);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();
        channel.setLimits(limits(1000, 100, 256, 100, 1048576));
        channel.setOpen(false);
        // A flush with nothing buffered is how the batcher learns a connection's limits.
        batcher.flush(channel);

        offerNamed(batcher, 0, 100);
        clock.addAndGet(1000L);
        offerNamed(batcher, 100, 100);
        assertThat(batcher.droppedByRateLimit()).isEqualTo(100);
        batcher.flush(channel);
        assertThat(channel.frames()).isEmpty();

        channel.setOpen(true);
        batcher.flush(channel);
        JsonArray lines = onlyLines(channel.frames().get(0));
        assertThat(lines).hasSize(100);
        assertThat(lines.get(0).getAsJsonObject().get("message").getAsString()).isEqualTo("line 100");
        assertThat(lines.get(99).getAsJsonObject().get("message").getAsString()).isEqualTo("line 199");
    }

    @Test
    void resetClearsTheBufferAndRefillsTheBucket() {
        AgentLogBatcher batcher = new AgentLogBatcher(() -> START);
        RecordingChannel channel = new RecordingChannel();

        offerMany(batcher, 500, START);
        batcher.reset();
        batcher.flush(channel);
        assertThat(channel.frames()).isEmpty();
        assertThat(batcher.droppedByRateLimit()).isZero();

        offerMany(batcher, 200, START);
        drain(batcher, channel);
        assertThat(totalLines(channel.frames())).isEqualTo(200);
        assertThat(batcher.droppedByRateLimit()).isZero();
    }

    @Test
    void everyFlushReadsTheLimitsOfTheChannelItSendsOn() {
        AgentLogBatcher batcher = new AgentLogBatcher(() -> START);
        RecordingChannel first = new RecordingChannel();
        first.setLimits(limits(1000, 1000, 8192, 100, 1048576));
        for (int i = 0; i < 150; i++) {
            batcher.offer(START, Level.INFO, "line " + i);
        }
        batcher.flush(first);
        assertThat(sizes(first.frames())).containsExactly(100);

        RecordingChannel second = new RecordingChannel();
        second.setLimits(limits(1000, 1000, 8192, 10, 1048576));
        batcher.flush(second);
        assertThat(sizes(second.frames())).containsExactly(10);
    }

    private static CloudLimits limits(int ratePerSec, int burst, int lineMaxBytes, int batchMaxLines,
                                      int maxFrameBytes) {
        JsonObject json = ContractFixtures.object("fixtures/agent/welcome/valid/default-limits.json")
                .getAsJsonObject("payload").getAsJsonObject("limits");
        json.addProperty("maxFrameBytes", maxFrameBytes);
        JsonObject log = json.getAsJsonObject("log");
        log.addProperty("ratePerSec", ratePerSec);
        log.addProperty("burst", burst);
        log.addProperty("lineMaxBytes", lineMaxBytes);
        log.addProperty("batchMaxLines", batchMaxLines);
        return CloudLimits.fromWelcome(json);
    }

    /** 20 000 characters of four-byte and three-byte code points plus ASCII. */
    private static String multiByteMessage() {
        StringBuilder text = new StringBuilder();
        for (int repeat = 0; repeat < 5000; repeat++) {
            text.append('漢').append("😀").append("ab");
        }
        return text.toString();
    }

    private static void offerMany(AgentLogBatcher batcher, int count, long ts) {
        for (int i = 0; i < count; i++) {
            batcher.offer(ts, Level.INFO, "line " + i);
        }
    }

    private static void offerNamed(AgentLogBatcher batcher, int from, int count) {
        for (int i = from; i < from + count; i++) {
            batcher.offer(START, Level.INFO, "line " + i);
        }
    }

    /** Flushes until the buffer stops producing frames, so a batch limit is not mistaken for a cap. */
    private static void drain(AgentLogBatcher batcher, RecordingChannel channel) {
        int before = channel.frames().size();
        while (true) {
            batcher.flush(channel);
            int after = channel.frames().size();
            if (after == before) {
                return;
            }
            before = after;
        }
    }

    private static int totalLines(List<JsonObject> frames) {
        int total = 0;
        for (JsonObject frame : frames) {
            total += onlyLines(frame).size();
        }
        return total;
    }

    private static List<Integer> sizes(List<JsonObject> frames) {
        List<Integer> sizes = new ArrayList<>();
        for (JsonObject frame : frames) {
            sizes.add(onlyLines(frame).size());
        }
        return sizes;
    }

    private static JsonArray onlyLines(JsonObject frame) {
        return frame.getAsJsonObject("payload").getAsJsonArray("lines");
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }

    /** Captures the batcher's own FINE lines, which must never travel through the channel. */
    private static final class LogCapture extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(AgentLogBatcher.class.getName());
        private final Level previousLevel = logger.getLevel();
        private final boolean previousParents = logger.getUseParentHandlers();
        private final List<LogRecord> records = new ArrayList<>();

        private LogCapture(Class<?> type) {
            assertThat(type).isEqualTo(AgentLogBatcher.class);
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            setLevel(Level.ALL);
            logger.addHandler(this);
        }

        private List<String> fines() {
            List<String> messages = new ArrayList<>();
            for (LogRecord record : records) {
                if (record.getLevel().equals(Level.FINE)) {
                    messages.add(String.format(Locale.ROOT, "%s", record.getMessage()));
                }
            }
            return messages;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParents);
        }
    }
}
