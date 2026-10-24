package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tracer for the log egress path: offered log records leave as one schema-valid
 * {@code log.lines} frame, and the shape of the contract's own redacted-login sample is
 * reproduced exactly.
 */
class LogLinesEndToEndTest {

    /** The line the 22-04 builders produce for a redacted sensitive command (D-20). */
    private static final String REDACTED_LOGIN = "Steve issued server command: /login ***";

    /** The sample's timestamp, so the whole line object can be compared to the fixture. */
    private static final long SAMPLE_TS = 1761274800000L;

    @Test
    void offeredRecordsLeaveAsOneSchemaValidLogLinesFrame() {
        AtomicLong clock = new AtomicLong(SAMPLE_TS);
        AgentLogBatcher batcher = new AgentLogBatcher(clock::get);
        RecordingChannel channel = new RecordingChannel();

        batcher.offer(SAMPLE_TS, Level.SEVERE, "Console crashed");
        batcher.offer(SAMPLE_TS + 1, Level.WARNING, "Can't keep up!");
        batcher.offer(SAMPLE_TS + 2, Level.FINE, "Trace note");
        batcher.offer(SAMPLE_TS, Level.INFO, REDACTED_LOGIN);
        // Level.CONFIG is 700, exactly the INFO threshold: it must not fall through to DEBUG.
        batcher.offer(SAMPLE_TS + 3, Level.CONFIG, "Config noted");
        batcher.flush(channel);

        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).get("type").getAsString()).isEqualTo("log.lines");
        JsonArray lines = frames.get(0).getAsJsonObject("payload").getAsJsonArray("lines");
        assertThat(levels(lines)).containsExactly("ERROR", "WARN", "DEBUG", "INFO", "INFO");
        assertThat(lines.get(0).getAsJsonObject().get("ts").getAsLong()).isEqualTo(SAMPLE_TS);
        assertThat(lines.get(1).getAsJsonObject().get("message").getAsString()).isEqualTo("Can't keep up!");
        assertThat(lines.get(2).getAsJsonObject().get("message").getAsString()).isEqualTo("Trace note");
        assertThat(lines.get(4).getAsJsonObject().get("message").getAsString()).isEqualTo("Config noted");
    }

    @Test
    void theRedactedLineReproducesTheContractSampleExactly() {
        AgentLogBatcher batcher = new AgentLogBatcher(() -> SAMPLE_TS);
        RecordingChannel channel = new RecordingChannel();
        batcher.offer(SAMPLE_TS, Level.INFO, REDACTED_LOGIN);
        batcher.flush(channel);

        JsonObject expected = ContractFixtures
                .object("fixtures/agent/log.lines/valid/redacted-login.json")
                .getAsJsonObject("payload")
                .getAsJsonArray("lines")
                .get(0)
                .getAsJsonObject();
        JsonObject actual = channel.frames().get(0)
                .getAsJsonObject("payload")
                .getAsJsonArray("lines")
                .get(0)
                .getAsJsonObject();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void aClosedChannelKeepsTheLinesForTheNextOpenChannel() {
        AgentLogBatcher batcher = new AgentLogBatcher(() -> SAMPLE_TS);
        RecordingChannel channel = new RecordingChannel();
        channel.setOpen(false);

        batcher.offer(SAMPLE_TS, Level.INFO, "first");
        batcher.offer(SAMPLE_TS, Level.INFO, "second");
        batcher.flush(channel);
        assertThat(channel.frames()).isEmpty();

        channel.setOpen(true);
        batcher.flush(channel);
        List<JsonObject> frames = channel.frames();
        assertThat(frames).hasSize(1);
        JsonArray lines = frames.get(0).getAsJsonObject("payload").getAsJsonArray("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getAsJsonObject().get("message").getAsString()).isEqualTo("first");
        assertThat(lines.get(1).getAsJsonObject().get("message").getAsString()).isEqualTo("second");
    }

    @Test
    void nothingIsSentWhenNoLineIsOffered() {
        AgentLogBatcher batcher = new AgentLogBatcher(() -> SAMPLE_TS);
        RecordingChannel channel = new RecordingChannel();
        batcher.flush(channel);
        assertThat(channel.frames()).isEmpty();
    }

    private static List<String> levels(JsonArray lines) {
        List<String> levels = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            levels.add(lines.get(i).getAsJsonObject().get("level").getAsString());
        }
        return levels;
    }
}
