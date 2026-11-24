package com.ultikits.ultitools.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import com.ultikits.ultitools.utils.MockBukkitHelper;
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
    private ErrorReportCollector collector;
    private MockedStatic<UltiTools> tools;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        Plugin host = MockBukkit.createMockPlugin();
        engine = new PaperSchedulerEngine(host, host.getLogger(), PaperBackend.live(host));
        scheduler = engine.newOwner("UltiTools");
        handler = new SystemLogHandler(mock(UltiPanelLogTransmitter.class));
        collector = mock(ErrorReportCollector.class);
        UltiTools plugin = mock(UltiTools.class);
        when(plugin.getErrorReportCollector()).thenReturn(collector);
        tools = mockStatic(UltiTools.class);
        tools.when(UltiTools::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void tearDown() {
        engine.retire(scheduler);
        tools.close();
        MockBukkitHelper.safeUnmock();
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
