package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises the real lifecycle with deterministic deliveries, independently of Paper's clock. */
@Timeout(10)
class TaskFailureLoggingTest {
    private final Queue<Consumer<ScheduledTask>> deliveries = new ArrayDeque<>();
    private final List<LogRecord> warnings = new ArrayList<>();
    private PaperSchedulerEngine engine;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // Records are captured synchronously.
            }

            @Override
            public void close() {
                // No external resources are held.
            }
        });
        PaperBackend backend = mock(PaperBackend.class);
        doAnswer(call -> enqueue(call.getArgument(0))).when(backend).global(any(), anyLong());
        doAnswer(call -> enqueue(call.getArgument(3))).when(backend).region(any(), anyInt(), anyInt(), any(), anyLong());
        doAnswer(call -> enqueue(call.getArgument(1))).when(backend).entity(any(), any(), any(), anyLong());
        doAnswer(call -> enqueue(call.getArgument(0))).when(backend).async(any(), anyLong());
        engine = new PaperSchedulerEngine(mock(Plugin.class), logger, backend);
        scheduler = engine.newOwner("failure-owner");
    }

    @AfterEach
    void tearDown() {
        engine.retire(scheduler);
    }

    @ParameterizedTest
    @EnumSource(Context.Affinity.class)
    void runFailureLogsExactlyOnceAndCompletesFailed(Context.Affinity affinity) {
        IllegalStateException failure = new IllegalStateException("run failure");
        TaskHandle handle = context(affinity).run(() -> { throw failure; });
        deliver();
        assertFailed(handle, failure);
        assertWarning(failure);
    }

    @ParameterizedTest
    @EnumSource(Context.Affinity.class)
    void delayedFailureLogsExactlyOnceAndCompletesFailed(Context.Affinity affinity) {
        IllegalStateException failure = new IllegalStateException("delayed failure");
        TaskHandle handle = context(affinity).runLater(Ticks.of(2), () -> { throw failure; });
        deliver();
        assertFailed(handle, failure);
        assertWarning(failure);
    }

    @ParameterizedTest
    @EnumSource(Context.Affinity.class)
    void executeFailureLogsExactlyOnce(Context.Affinity affinity) {
        IllegalStateException failure = new IllegalStateException("executor failure");
        context(affinity).execute(() -> { throw failure; });
        deliver();
        assertWarning(failure);
    }

    @ParameterizedTest
    @EnumSource(Context.Affinity.class)
    void supplyFailureOnlyCompletesItsFuture(Context.Affinity affinity) {
        IllegalStateException failure = new IllegalStateException("supplier failure");
        CompletableFuture<Object> result = context(affinity).supply(() -> { throw failure; });
        deliver();
        assertThatThrownBy(result::join).hasCause(failure);
        assertThat(warnings).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Context.Affinity.class)
    void repeatingFailureStillLogsOnceAndContinues(Context.Affinity affinity) {
        IllegalStateException failure = new IllegalStateException("repeating failure");
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = context(affinity).runRepeating(Duration.ZERO, Ticks.TICK, own -> {
            if (runs.incrementAndGet() == 1) {
                throw failure;
            }
            own.cancel();
        });
        deliver();
        assertThat(handle.state()).isEqualTo(TaskState.SCHEDULED);
        assertWarning(failure);
        deliver();
        assertThat(runs).hasValue(2);
        assertThat(handle.state()).isEqualTo(TaskState.CANCELLED);
        assertWarning(failure);
    }

    @Test
    void virtualMachineErrorKeepsTheExistingBodyFailureSemantics() {
        InternalError failure = new InternalError("synthetic VM error");
        TaskHandle handle = scheduler.global().run(() -> { throw failure; });
        assertThatCode(this::deliver).doesNotThrowAnyException();
        assertFailed(handle, failure);
        assertWarning(failure);
    }

    @Test
    void supplyErrorStillOnlyCompletesItsFuture() {
        InternalError failure = new InternalError("synthetic supplier VM error");
        CompletableFuture<Object> result = scheduler.async().supply(() -> { throw failure; });
        assertThatCode(this::deliver).doesNotThrowAnyException();
        assertThatThrownBy(result::join).hasCause(failure);
        assertThat(warnings).isEmpty();
    }

    private ScheduledTask enqueue(Consumer<ScheduledTask> delivery) {
        deliveries.add(delivery);
        return mock(ScheduledTask.class);
    }

    private void deliver() {
        deliveries.remove().accept(mock(ScheduledTask.class));
    }

    private TaskContext context(Context.Affinity affinity) {
        switch (affinity) {
            case GLOBAL:
                return scheduler.global();
            case REGION:
                return scheduler.region(mock(World.class), 2, -2);
            case ENTITY:
                return scheduler.entity(mock(Entity.class));
            default:
                return scheduler.async();
        }
    }

    private static void assertFailed(TaskHandle handle, Throwable failure) {
        assertThat(handle.state()).isEqualTo(TaskState.FAILED);
        assertThatThrownBy(() -> handle.completion().toCompletableFuture().join()).hasCause(failure);
    }

    private void assertWarning(Throwable failure) {
        assertThat(warnings).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).isEqualTo(
                    "[UltiTools-API] Error executing scheduled task of failure-owner");
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }
}
