package com.ultikits.ultitools.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.listeners.PlayerJoinListener;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import me.clip.placeholderapi.PlaceholderAPI;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.player.PlayerJoinEvent;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ReflectionUtil;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

@Timeout(30)
class SystemLogHandlerSchedulerTest {
    private ServerMock server;
    private PaperSchedulerEngine engine;
    private Scheduler scheduler;
    private SystemLogHandler handler;
    private UltiPanelLogTransmitter transmitter;
    private ErrorReportCollector collector;
    private MockedStatic<UltiTools> tools;
    private Logger taskLogger;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Plugin host = MockBukkit.createMockPlugin();
        taskLogger = Logger.getAnonymousLogger();
        taskLogger.setUseParentHandlers(false);
        engine = new PaperSchedulerEngine(host, taskLogger, PaperBackend.live(host));
        scheduler = engine.newOwner("UltiTools");
        transmitter = mock(UltiPanelLogTransmitter.class);
        handler = new SystemLogHandler(transmitter);
        collector = mock(ErrorReportCollector.class);
        UltiTools plugin = mock(UltiTools.class);
        when(plugin.getErrorReportCollector()).thenReturn(collector);
        tools = mockStatic(UltiTools.class);
        tools.when(UltiTools::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void tearDown() {
        try {
            if (engine != null && scheduler != null) { engine.retire(scheduler); }
        } finally {
            try {
                if (tools != null) { tools.close(); }
            } finally {
                MockBukkitHelper.safeUnmock();
            }
        }
    }

    @Test
    void actualEngineTaskIsAttributedToTheFrameworkScheduler() {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        TaskHandle task = scheduler.global().run(() -> {
            Throwable failure = new IllegalStateException("scheduled failure");
            thrown.set(failure);
            publish(failure);
        });
        server.getScheduler().performTicks(1);

        assertThat(task.state()).isEqualTo(TaskState.COMPLETED);
        TriggerContext trigger = capturedTrigger(thrown.get());
        assertThat(trigger.getTriggerType()).isEqualTo(TriggerContext.TriggerType.SCHEDULED);
        assertThat(trigger.getTriggerDetail()).isEqualTo("Scheduler");
    }

    @Test
    void joinCommandFailureIsLoggedOnceAndAttributedToTheScheduler() throws NoSuchMethodException {
        PluginMock papi = PluginMock.builder().withPluginName("PlaceholderAPI").build();
        server.getPluginManager().registerLoadedPlugin(papi);
        server.getPluginManager().enablePlugin(papi);
        server.getCommandMap().register("papi", new Command("papi") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] arguments) {
                throw new IllegalStateException("join command failure");
            }
        });
        Handler capture = mock(Handler.class);
        taskLogger.addHandler(capture);
        FrameworkScheduling.install(scheduler);
        try (MockedStatic<PlaceholderAPI> placeholders = mockStatic(PlaceholderAPI.class)) {
            placeholders.when(() -> PlaceholderAPI.isRegistered(anyString()))
                    .thenReturn(true);
            placeholders.when(() -> PlaceholderAPI.isRegistered("player")).thenReturn(false);
            new PlayerJoinListener().onPlayerJoin(new PlayerJoinEvent(server.addPlayer(), "joined"));
            server.getScheduler().performTicks(30);

            ArgumentCaptor<LogRecord> records = ArgumentCaptor.forClass(LogRecord.class);
            verify(capture).publish(records.capture());
            LogRecord warning = records.getValue();
            assertThat(warning.getLevel()).isEqualTo(Level.WARNING);
            assertThat(warning.getMessage()).contains("UltiTools");
            assertThat(warning.getThrown()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("join command failure");
            handler.publish(warning);
            verify(transmitter).sendLog(eq("warning"), anyString(), anyString(), eq(warning.getThrown()));
            // WARNING is streamed without an error report. Classify the original throwable directly,
            // without promoting its level or inventing execution frames.
            TriggerContext trigger = ReflectionUtil.invoke(handler, SystemLogHandler.class.getDeclaredMethod(
                    "inferTriggerFromStackTrace", Throwable.class), warning.getThrown());
            assertThat(trigger.getTriggerType()).isEqualTo(TriggerContext.TriggerType.SCHEDULED);
            assertThat(trigger.getTriggerDetail()).isEqualTo("Scheduler");
        } finally {
            FrameworkScheduling.uninstall(scheduler);
            taskLogger.removeHandler(capture);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "com.ultikits.ultitools.scheduling.internal.TaskRecord, executeClaimed, SCHEDULED, Scheduler",
        "org.bukkit.scheduler.BukkitRunnable, run, SCHEDULED, BukkitRunnable",
        "org.bukkit.craftbukkit.scheduler.CraftScheduler, mainThreadHeartbeat, SCHEDULED, ScheduledTask",
        "example.TaskRecord, executeClaimed, UNCAUGHT, unknown",
        "com.ultikits.ultitools.scheduling.internal.TaskRecord, state, UNCAUGHT, unknown"
    })
    void executionFramesAreRecognizedWithoutMatchingUnrelatedClasses(
            String className, String method, TriggerContext.TriggerType type, String detail) {
        Throwable failure = new IllegalStateException("synthetic frame");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(className, method, "Task.java", 1)
        });
        publish(failure);

        TriggerContext trigger = capturedTrigger(failure);
        assertThat(trigger.getTriggerType()).isEqualTo(type);
        assertThat(trigger.getTriggerDetail()).isEqualTo(detail);
    }

    private void publish(Throwable failure) {
        LogRecord record = new LogRecord(Level.SEVERE, "scheduled failure");
        record.setLoggerName("plugin.Example");
        record.setThrown(failure);
        handler.publish(record);
    }

    private TriggerContext capturedTrigger(Throwable failure) {
        ArgumentCaptor<TriggerContext> trigger = ArgumentCaptor.forClass(TriggerContext.class);
        verify(collector).reportError(eq(failure), eq("Example"), trigger.capture());
        return trigger.getValue();
    }
}
