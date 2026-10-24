package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.manager.RemoteActionLog;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Restart support, redelivery and failure handling of {@link PowerActionHandler}, plus
 * {@link BukkitPowerExecutor} on MockBukkit's real scheduler.
 */
class PowerActionHandlerTest {

    private static final String STOP = "fixtures/agent/server.power/valid/stop.json";
    private static final String RESTART = "fixtures/agent/server.power/valid/restart.json";

    @TempDir
    File workingDirectory;

    private final List<LogRecord> logged = new ArrayList<>();

    private ServerMock server;
    private Handler capture;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
                // Records are kept in memory; there is nothing to flush.
            }

            @Override
            public void close() {
                // No resource is held; there is nothing to close.
            }
        };
        handlerLogger = Logger.getLogger(PowerActionHandler.class.getName());
        handlerLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.removeHandler(capture);
        if (server != null) {
            MockBukkit.unmock();
            server = null;
        }
    }

    @Test
    void restartWithoutARestartScriptIsRefusedAndNothingIsScheduled() {
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        when(executor.restartSupported()).thenReturn(false);
        Runnable hook = mock(Runnable.class);
        RecordingChannel channel = new RecordingChannel();
        List<RemoteActionLog.Entry> audit = new ArrayList<>();

        router(new PowerActionHandler(new MemoryLedger(), () -> true, executor, hook, audit::add))
                .dispatch(ContractFixtures.text(RESTART), channel);

        assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "server.power.result");
        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("error");
        assertThat(payload.get("code").getAsString()).isEqualTo("power_unsupported");
        assertThat(payload.get("message").getAsString())
                .contains("spigot.yml").contains("restart-script");
        verify(executor, never()).scheduleStop();
        verify(executor, never()).scheduleRestart();
        verifyNoInteractions(hook);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(audit.get(0).getAction()).isEqualTo("server.power.restart");
    }

    @Test
    void restartWithARestartScriptIsAnsweredThenScheduled() {
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        when(executor.restartSupported()).thenReturn(true);
        Runnable hook = mock(Runnable.class);
        RecordingChannel channel = new RecordingChannel();
        List<RemoteActionLog.Entry> audit = new ArrayList<>();

        router(new PowerActionHandler(new MemoryLedger(), () -> true, executor, hook, audit::add))
                .dispatch(ContractFixtures.text(RESTART), channel);

        JsonObject payload = channel.frames().get(1).getAsJsonObject("payload");
        assertThat(payload.get("status").getAsString()).isEqualTo("ok");
        assertThat(payload.getAsJsonObject("data").get("action").getAsString()).isEqualTo("restart");
        verify(hook).run();
        verify(executor).scheduleRestart();
        verify(executor, never()).scheduleStop();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(audit.get(0).getAction()).isEqualTo("server.power.restart");
    }

    @Test
    void aRedeliveredStopIdIsAcknowledgedAgainWithoutStoppingTwice() {
        MemoryLedger ledger = new MemoryLedger();
        AtomicInteger stops = new AtomicInteger();
        PowerActionHandler.PowerExecutor executor = new CountingExecutor(stops);
        RecordingChannel channel = new RecordingChannel();

        router(new PowerActionHandler(ledger, () -> true, executor, () -> { }, entry -> { }))
                .dispatch(ContractFixtures.text(STOP), channel);
        router(new PowerActionHandler(ledger, () -> true, executor, () -> { }, entry -> { }))
                .dispatch(ContractFixtures.text(STOP), channel);

        assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "server.power.result", "command.ack");
        assertThat(stops).hasValue(1);
    }

    @Test
    void aFailingPreShutdownHookIsLoggedOnceAndTheStopStillHappens() {
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        RecordingChannel channel = new RecordingChannel();
        Runnable hook = () -> {
            throw new IllegalStateException("hook failure");
        };

        router(new PowerActionHandler(new MemoryLedger(), () -> true, executor, hook, entry -> { }))
                .dispatch(ContractFixtures.text(STOP), channel);

        assertThat(recordsAtLeast(Level.WARNING)).hasSize(1);
        assertThat(recordsAtLeast(Level.WARNING).get(0).getMessage()).contains("stop");
        verify(executor).scheduleStop();
        assertThat(channel.frames()).hasSize(2);
    }

    @Test
    void aFailingSchedulerIsLoggedAtSevereAfterTheResultWasSent() {
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        doThrow(new IllegalStateException("scheduler refused")).when(executor).scheduleStop();
        RecordingChannel channel = new RecordingChannel();

        router(new PowerActionHandler(new MemoryLedger(), () -> true, executor, () -> { }, entry -> { }))
                .dispatch(ContractFixtures.text(STOP), channel);

        assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "server.power.result");
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
        assertThat(recordsAtLeast(Level.SEVERE)).hasSize(1);
        assertThat(recordsAtLeast(Level.SEVERE).get(0).getMessage()).contains("stop");
    }

    @Test
    void scheduleStopRunsOneTaskTwentyTicksLater() {
        server = MockBukkit.mock();
        AtomicInteger stops = new AtomicInteger();
        BukkitPowerExecutor executor = new BukkitPowerExecutor(mock(Plugin.class), workingDirectory,
                () -> "./start.sh", stops::incrementAndGet, () -> { });

        executor.scheduleStop();

        assertThat(server.getScheduler().getPendingTasks()).hasSize(1);
        server.getScheduler().performTicks(BukkitPowerExecutor.DELAY_TICKS - 1L);
        assertThat(stops).hasValue(0);
        server.getScheduler().performTicks(1L);
        assertThat(stops).hasValue(1);
        server.getScheduler().performTicks(40L);
        assertThat(stops).hasValue(1);
    }

    @Test
    void scheduleRestartRunsOneTaskTwentyTicksLater() {
        server = MockBukkit.mock();
        AtomicInteger restarts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        BukkitPowerExecutor executor = new BukkitPowerExecutor(mock(Plugin.class), workingDirectory,
                () -> "./start.sh", stops::incrementAndGet, restarts::incrementAndGet);

        executor.scheduleRestart();

        assertThat(server.getScheduler().getPendingTasks()).hasSize(1);
        server.getScheduler().performTicks(BukkitPowerExecutor.DELAY_TICKS);
        assertThat(restarts).hasValue(1);
        assertThat(stops).hasValue(0);
    }

    @Test
    void restartSupportFollowsTheConfiguredScriptFile() throws IOException {
        BukkitPowerExecutor relative = new BukkitPowerExecutor(mock(Plugin.class), workingDirectory,
                () -> "start.sh", () -> { }, () -> { });
        assertThat(relative.restartSupported()).as("absent script").isFalse();

        File script = new File(workingDirectory, "start.sh");
        Files.write(script.toPath(), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        assertThat(relative.restartSupported()).as("an existing regular file").isTrue();

        BukkitPowerExecutor absolute = new BukkitPowerExecutor(mock(Plugin.class), workingDirectory,
                () -> script.getAbsolutePath(), () -> { }, () -> { });
        assertThat(absolute.restartSupported()).as("an absolute path").isTrue();

        BukkitPowerExecutor unset = new BukkitPowerExecutor(mock(Plugin.class), workingDirectory,
                () -> null, () -> { }, () -> { });
        assertThat(unset.restartSupported()).as("the spigot default is ./start.sh").isTrue();

        Files.delete(script.toPath());
        Files.createDirectory(script.toPath());
        assertThat(relative.restartSupported()).as("a directory is not a restart script").isFalse();
    }

    private List<LogRecord> recordsAtLeast(Level level) {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord record : logged) {
            if (record.getLevel().intValue() >= level.intValue()) {
                result.add(record);
            }
        }
        return result;
    }

    private AgentMessageRouter router(PowerActionHandler handler) {
        AgentMessageRouter router = new AgentMessageRouter("power"::equals, capability -> "not accepted");
        router.register("server.power", handler);
        return router;
    }

    /**
     * The ack path the handler relies on, in memory: a redelivered id is not first-seen again.
     */
    private static final class MemoryLedger implements DedupeLedger {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean firstSeen(String id) {
            return seen.add(id);
        }

        @Override
        public void persistNow() {
            // In memory only; there is nothing to persist.
        }

        @Override
        public JsonObject receiptFor(String id) {
            return null;
        }

        @Override
        public void recordReceipt(String id, JsonObject receipt) {
            // The power path records ids only; no completion receipt is ever written.
        }
    }

    /**
     * Counts scheduled stops so a redelivery is observable without a live server.
     */
    private static final class CountingExecutor implements PowerActionHandler.PowerExecutor {
        private final AtomicInteger stops;

        private CountingExecutor(AtomicInteger stops) {
            this.stops = stops;
        }

        @Override
        public boolean restartSupported() {
            return false;
        }

        @Override
        public void scheduleStop() {
            stops.incrementAndGet();
        }

        @Override
        public void scheduleRestart() {
            // A stop request never reaches the restart path.
        }
    }
}
