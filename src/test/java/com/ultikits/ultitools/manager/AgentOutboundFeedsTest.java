package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;

/**
 * {@link AgentOutboundFeeds} without a connection (plan 22-22 fix): the main-thread sampler of the v2 session.
 * MockBukkit treats the test thread as the server thread.
 */
@DisplayName("AgentOutboundFeeds: the v2 main-thread sampler")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test clears the UltiTools singleton
class AgentOutboundFeedsTest {

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        final Logger logger = Logger.getLogger("AgentOutboundFeedsTest");
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
    }

    @AfterEach
    void tearDown() throws Exception {
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static AgentOutboundFeeds feeds(final ServerMonitorManager monitor) {
        return new AgentOutboundFeeds(() -> monitor, () -> true, () -> false, () -> false, () -> null,
                System::currentTimeMillis, Runnable::run, targets -> { });
    }

    @Test
    @DisplayName("construction rejects a null snapshot audit callback")
    void rejectsNullSnapshotReadAtConstruction() {
        assertThatThrownBy(() -> new AgentOutboundFeeds(() -> null, () -> true, () -> false, () -> false,
                () -> null, System::currentTimeMillis, Runnable::run, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshotRead");
    }

    @Test
    @DisplayName("the first sample after a late first connection measures 20 ticks, not the time since enable")
    void firstSampleIgnoresTheIdleGapSinceTheMonitorWasCreated() throws Exception {
        ServerMonitorManager monitor = new ServerMonitorManager(); // created at plugin enable
        Thread.sleep(2000L); // the first connection comes later (on a real server: seconds to hours)
        AgentOutboundFeeds feeds = feeds(monitor);

        feeds.mainThreadSecond(); // the first 20-tick run on an idle server
        assertThat(monitor.getCurrentTPS()).as("an idle 20-TPS server").isGreaterThanOrEqualTo(19.9);

        // The sampler still measures: two seconds between two 20-tick runs is a slow second.
        Thread.sleep(2000L);
        feeds.mainThreadSecond();
        assertThat(monitor.getCurrentTPS()).as("a 2 s gap between runs").isLessThan(15.0);
    }

    @Test
    @DisplayName("a full batch is flushed on the agent thread, never on the logging thread")
    void aFullBatchLeavesOnTheAgentThread() {
        // The agent executor records its tasks instead of running them, so a send before they run is a send on the
        // logging thread.
        final AtomicInteger sends = new AtomicInteger();
        CloudChannel channel = new RecordingChannel() {
            @Override
            public boolean send(String type, JsonObject payload) {
                sends.incrementAndGet();
                return true;
            }
        };
        final List<Runnable> agentTasks = new ArrayList<>();
        final AtomicBoolean logs = new AtomicBoolean(false); // off while established: no root-logger handler
        AgentOutboundFeeds feeds = new AgentOutboundFeeds(() -> null, () -> false, logs::get, () -> false,
                () -> null, System::currentTimeMillis, null, targets -> { });
        feeds.established(channel, agentTasks::add, true);
        logs.set(true);
        for (int i = 0; i < 150; i++) {
            feeds.offerLine(System.currentTimeMillis(), Level.INFO, "line " + i);
        }
        assertThat(sends.get()).as("nothing sent on the logging thread").isZero();
        assertThat(agentTasks).as("one flush queued on the agent thread").hasSize(1);
        agentTasks.get(0).run();
        assertThat(sends.get()).isPositive();
        feeds.shutdown();
    }

    @Test
    @DisplayName("logs declared but not accepted: no line is batched and no flush is attempted")
    void logsNotAcceptedMakesTheStreamInert() {
        final AtomicInteger sends = new AtomicInteger();
        CloudChannel channel = new RecordingChannel() {
            @Override
            public boolean send(String type, JsonObject payload) {
                sends.incrementAndGet();
                return false; // the channel refuses a type the gateway did not accept
            }
        };
        final List<Runnable> agentTasks = new ArrayList<>();
        // The root-logger handler may stay attached (it also feeds the error collector); its lines must go nowhere.
        // The switch is off while established only so this unit test attaches no handler; it is on for every line.
        final AtomicBoolean logs = new AtomicBoolean(false);
        AgentOutboundFeeds feeds = new AgentOutboundFeeds(() -> null, () -> false, logs::get, () -> false,
                () -> null, System::currentTimeMillis, null, targets -> { });
        feeds.established(channel, agentTasks::add, false);
        logs.set(true);
        for (int i = 0; i < 2000; i++) {
            feeds.offerLine(System.currentTimeMillis(), Level.INFO, "flood line " + i);
        }
        feeds.agentSecond();
        assertThat(agentTasks).as("no flush queued").isEmpty();
        assertThat(sends.get()).as("no log.lines attempted").isZero();
        final AtomicInteger buffered = new AtomicInteger();
        feeds.logBatcher().flush(new RecordingChannel() {
            @Override
            public boolean send(String type, JsonObject payload) {
                buffered.incrementAndGet();
                return true;
            }
        });
        assertThat(buffered.get()).as("nothing batched").isZero();
        feeds.shutdown();
    }

    @Test
    @DisplayName("a v2 transmitter whose sink is not accepting builds and hands over nothing")
    void aTransmitterIsInertWhileItsSinkIsNotAccepting() {
        final AtomicBoolean accepting = new AtomicBoolean(false);
        final List<String> offered = new ArrayList<>();
        UltiPanelLogTransmitter transmitter = new UltiPanelLogTransmitter(new UltiPanelLogTransmitter.AgentLineSink() {
            @Override
            public void offer(long timestamp, Level level, String message) {
                offered.add(message);
            }

            @Override
            public boolean accepting() {
                return accepting.get();
            }
        });
        transmitter.sendLog("info", "while inert", "server", null);
        assertThat(offered).isEmpty();
        accepting.set(true);
        transmitter.sendLog("info", "while accepting", "server", null);
        assertThat(offered).hasSize(1);
        assertThat(offered.get(0)).contains("while accepting");
    }

    /** A channel that accepts everything and records nothing; tests override what they observe. */
    private static class RecordingChannel implements CloudChannel {
        @Override public boolean isOpen() { return true; }
        @Override public CloudLimits limits() { return CloudLimits.defaults(); }
        @Override public boolean send(String type, JsonObject payload) { return true; }
        @Override public boolean reply(String replyTo, String type, JsonObject payload) { return true; }
        @Override public boolean replyOk(String replyTo, String type, JsonElement data) { return true; }
        @Override public boolean replyPartial(String replyTo, String type, JsonElement data, String code,
                                              String message) { return true; }
        @Override public boolean replyError(String replyTo, String type, String code, String message,
                                            JsonObject details) { return true; }
    }
}
