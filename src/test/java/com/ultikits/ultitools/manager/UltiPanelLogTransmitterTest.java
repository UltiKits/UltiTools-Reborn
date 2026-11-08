package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * UltiPanelLogTransmitter after the protocol v1 removal (6.4.0): its only constructor takes the protocol v2
 * {@link UltiPanelLogTransmitter.AgentLineSink}, and every line goes to that sink at once. The v1 batch sender,
 * replay sender and their scheduling were removed with the v1 client; what is pinned here is what is left.
 */
@DisplayName("UltiPanelLogTransmitter (protocol v2 sink)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiPanelLogTransmitterTest {

    /** One line as the sink received it. */
    private static final class Line {
        final long timestamp;
        final Level level;
        final String message;

        Line(long timestamp, Level level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private UltiPanelLogTransmitter transmitter;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        Logger logger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
        transmitter = new UltiPanelLogTransmitter(new UltiPanelLogTransmitter.AgentLineSink() {
            @Override
            public void offer(long timestamp, Level level, String message) {
                lines.add(new Line(timestamp, level, message));
            }

            @Override
            public boolean accepting() {
                return accepting.get();
            }
        });
    }

    @AfterEach
    void tearDown() {
        transmitter.shutdown();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static JsonObject record(String message) {
        JsonObject log = new JsonObject();
        log.addProperty("message", message);
        return log;
    }

    private static List<String> messagesOf(JsonArray logs) {
        List<String> messages = new ArrayList<>();
        logs.forEach(e -> messages.add(e.getAsJsonObject().get("message").getAsString()));
        return messages;
    }

    @Test
    @DisplayName("the sink is required")
    void sinkIsRequired() {
        assertThatThrownBy(() -> new UltiPanelLogTransmitter(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("sendLog hands the line to the sink at once, with the panel level mapped to a JUL level")
    void sendLogHandsEachLineToTheSink() {
        transmitter.sendLog("info", "an info line", "server", null);
        transmitter.sendLog("warning", "a warning", "server", null);
        transmitter.sendLog("error", "an error", "server", null);
        transmitter.sendLog("debug", "a debug line", "server", null);
        transmitter.sendLog(null, "no level", null, null);

        assertThat(lines).extracting(l -> l.message)
                .containsExactly("an info line", "a warning", "an error", "a debug line", "no level");
        assertThat(lines).extracting(l -> l.level)
                .containsExactly(Level.INFO, Level.WARNING, Level.SEVERE, Level.FINE, Level.INFO);
    }

    @Test
    @DisplayName("the convenience methods send at their own level")
    void convenienceMethodsSendAtTheirLevel() {
        transmitter.info("i", "server");
        transmitter.warning("w", "server");
        transmitter.error("e", "server", null);
        transmitter.debug("d", "server");

        assertThat(lines).extracting(l -> l.level)
                .containsExactly(Level.INFO, Level.WARNING, Level.SEVERE, Level.FINE);
    }

    @Test
    @DisplayName("a stack trace follows the message on the following lines (log.lines has no stack-trace field)")
    void stackTraceIsAppendedToTheMessage() {
        transmitter.sendLog("error", "it failed", "server", new IllegalStateException("the cause"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).message).startsWith("it failed\n").contains("IllegalStateException: the cause");
    }

    @Test
    @DisplayName("a replayed record keeps the time it was logged")
    void replayKeepsTheRecordTime() {
        transmitter.replayLog("info", "early", "server", null, 42L);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).timestamp).isEqualTo(42L);
        assertThat(lines.get(0).message).isEqualTo("early");
    }

    @Test
    @DisplayName("nothing is handed over while transmission is off, while the sink is not accepting, or after shutdown")
    void nothingIsHandedOverWhenSwitchedOff() {
        assertThat(transmitter.isLogTransmissionEnabled()).isTrue();

        transmitter.setLogTransmissionEnabled(false);
        transmitter.info("while off", "server");
        transmitter.setLogTransmissionEnabled(true);
        accepting.set(false);
        transmitter.info("while not accepting", "server");
        accepting.set(true);
        transmitter.info("sent", "server");
        transmitter.shutdown();
        transmitter.info("after shutdown", "server");

        assertThat(lines).extracting(l -> l.message).containsExactly("sent");
        assertThat(transmitter.isLogTransmissionEnabled()).isFalse();
    }

    @Test
    @DisplayName("a sink that throws does not break the logging call")
    void throwingSinkDoesNotPropagate() {
        UltiPanelLogTransmitter throwing = new UltiPanelLogTransmitter((timestamp, level, message) -> {
            throw new IllegalStateException("sink down");
        });

        assertThatCode(() -> throwing.info("line", "server")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the replay and flush entry points are no-ops: nothing is ever queued for replay")
    void replayAndFlushAreNoOps() {
        transmitter.startReplay();
        transmitter.flushLogs();

        assertThat(transmitter.getReplayQueueSize()).isZero();
        assertThat(transmitter.getQueueSize()).isZero();
        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("batch settings below their floors are refused")
    void batchSettingsBelowTheirFloorsAreRefused() {
        assertThatThrownBy(() -> transmitter.setIntervalMs(UltiPanelLogTransmitter.MIN_INTERVAL_MS - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transmitter.setBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> {
            transmitter.setIntervalMs(UltiPanelLogTransmitter.MIN_INTERVAL_MS);
            transmitter.setBatchSize(1);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an undelivered batch is handed back first, before anything adopted later")
    void heldBatchIsDrainedFirst() {
        transmitter.adoptPending(new JsonArray());
        JsonArray adopted = new JsonArray();
        adopted.add(record("queued"));
        transmitter.adoptPending(adopted);
        JsonArray undelivered = new JsonArray();
        undelivered.add(record("held"));
        transmitter.holdUndelivered(undelivered);

        assertThat(messagesOf(transmitter.drainQueue(10))).containsExactly("held");
        assertThat(messagesOf(transmitter.drainQueue(10))).containsExactly("queued");
        assertThat(transmitter.drainQueue(10).size()).isZero();
    }

    @Test
    @DisplayName("takePending returns the held batch, then the queue, oldest first")
    void takePendingReturnsHeldThenQueued() {
        JsonArray adopted = new JsonArray();
        adopted.add(record("q1"));
        adopted.add(record("q2"));
        transmitter.adoptPending(adopted);
        JsonArray undelivered = new JsonArray();
        undelivered.add(record("h1"));
        transmitter.holdUndelivered(undelivered);

        assertThat(messagesOf(transmitter.takePending())).containsExactly("h1", "q1", "q2");
        assertThat(transmitter.getQueueSize()).isZero();
    }

    @Test
    @DisplayName("external drain mode is a plain switch")
    void externalDrainModeIsASwitch() {
        assertThat(transmitter.isExternalDrainMode()).isFalse();
        transmitter.setExternalDrainMode(true);
        assertThat(transmitter.isExternalDrainMode()).isTrue();
    }
}
