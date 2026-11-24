package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.Ticks;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.logging.Logger;
import static org.mockito.ArgumentMatchers.anyLong;
import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(10)
class FrameworkSchedulingTracerTest {
    private ShutdownPanelMessageTracerTest.ValidatingServer server;
    private Plugin plugin;
    private Scheduler traced;
    private PaperSchedulerEngine engine;
    private Scheduler live;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock(new ShutdownPanelMessageTracerTest.ValidatingServer());
        plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        PaperBackend backend = mock(PaperBackend.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(backend.global(any(), anyLong())).thenReturn(task);
        when(backend.async(any(), anyLong())).thenReturn(task);
        when(backend.entity(any(), any(), any(), anyLong())).thenReturn(task);
        when(backend.region(any(), anyInt(), anyInt(), any(), anyLong())).thenReturn(task);
        engine = new PaperSchedulerEngine(plugin, Logger.getAnonymousLogger(), backend);
        live = engine.newOwner("traced-owner");
        traced = server.recordFrameworkCalls(plugin, live);
    }

    @AfterEach
    void tearDown() {
        try {
            if (engine != null && live != null) { engine.retire(live); }
        } finally {
            try {
                server.getScheduler().shutdown();
            } finally {
                MockBukkitHelper.safeUnmock();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "global, run", "global, later", "global, repeat", "global, supply", "global, execute",
        "async, run", "async, later", "async, repeat", "async, supply", "async, execute",
        "entity, run", "entity, later", "entity, repeat", "entity, supply", "entity, execute",
        "location, run", "location, later", "location, repeat", "location, supply", "location, execute",
        "region, run", "region, later", "region, repeat", "region, supply", "region, execute"
    })
    void everySubmissionIsRecordedAndRejectedWhenDisabled(String affinity, String entry) {
        TaskContext context = context(affinity);
        submit(context, entry);
        assertThat(server.getScheduler().enabledCalls).hasSize(1);
        when(plugin.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> submit(context, entry)).isInstanceOf(IllegalPluginAccessException.class);
        assertThat(server.getScheduler().disabledCalls).hasSize(1);
        assertThat(server.getScheduler().enabledCalls).hasSize(1);
    }

    private TaskContext context(String affinity) {
        switch (affinity) {
            case "global":
                return traced.global();
            case "async":
                return traced.async();
            case "entity":
                return traced.entity(mock(Entity.class));
            case "location":
                return traced.region(new Location(mock(World.class), 0, 64, 0));
            default:
                return traced.region(mock(World.class), 0, 0);
        }
    }

    private static void submit(TaskContext context, String entry) {
        Runnable body = () -> { /* Only submission tracing is under test. */ };
        switch (entry) {
            case "run":
                context.run(body);
                break;
            case "later":
                context.runLater(Ticks.TICK, body);
                break;
            case "repeat":
                context.runRepeating(Duration.ZERO, Ticks.TICK, handle -> body.run());
                break;
            case "supply":
                context.supply(() -> "value");
                break;
            default:
                context.execute(body);
                break;
        }
    }
}
