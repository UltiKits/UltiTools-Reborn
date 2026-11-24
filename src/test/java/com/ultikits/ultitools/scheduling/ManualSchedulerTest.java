package com.ultikits.ultitools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bukkit.entity.Entity;
import org.mockito.Mockito;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Virtual-clock details belong to this double, not to the portable scheduler contract. */
class ManualSchedulerTest {
    @Test
    void asyncRepeating_virtualClock_runsAtExactPeriods() {
        try (ManualScheduler.Engine engine = new ManualScheduler.Engine()) {
            AtomicInteger runs = new AtomicInteger();
            TaskHandle handle = engine.scheduler("sample").async().runRepeating(
                    Duration.ofMillis(49), Duration.ofMillis(49), ignored -> runs.incrementAndGet());
            engine.elapseAsync(Duration.ofMillis(48));
            assertThat(runs).hasValue(0);
            engine.elapseAsync(Duration.ofMillis(1));
            assertThat(runs).hasValue(1);
            engine.elapseAsync(Duration.ofMillis(49));
            assertThat(runs).hasValue(2);
            assertThat(handle.cancel()).isTrue();
        }
    }

    @Test
    void clockAdvances_onOtherThread_areRejected() throws Exception {
        try (ManualScheduler.Engine engine = new ManualScheduler.Engine()) {
            FutureTask<Void> work = new FutureTask<>(() -> {
                assertThatThrownBy(() -> engine.advanceTicks(1)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> engine.elapseAsync(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
                return null;
            });
            Thread thread = new Thread(work, "manual-clock-probe");
            thread.setDaemon(true);
            thread.start();
            thread.join(10_000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(work.get()).isNull();
            assertThatThrownBy(() -> engine.elapseAsync(Duration.ofNanos(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void retire_continuationSchedulingNewTask_doesNotThrow() {
        try (ManualScheduler.Engine engine = new ManualScheduler.Engine()) {
            Entity entity = Mockito.mock(Entity.class);
            Scheduler scheduler = engine.scheduler("sample");
            AtomicInteger completions = new AtomicInteger();
            AtomicInteger runs = new AtomicInteger();
            TaskHandle first = scheduler.entity(entity).run(runs::incrementAndGet);
            TaskHandle second = scheduler.entity(entity).run(runs::incrementAndGet);
            first.completion().whenComplete((value, failure) -> {
                scheduler.global().run(runs::incrementAndGet);
                completions.incrementAndGet();
            });
            engine.retire(entity);
            engine.advanceTicks(1);
            assertThat(first.state()).isEqualTo(TaskState.RETIRED);
            assertThat(second.state()).isEqualTo(TaskState.RETIRED);
            assertThat(completions).hasValue(1);
            assertThat(runs).hasValue(1);
        }
    }

    @Test
    void advance_removesTerminalTasks() throws ReflectiveOperationException {
        try (ManualScheduler.Engine engine = new ManualScheduler.Engine()) {
            TaskHandle task = engine.scheduler("sample").global().run(() -> { /* Only lifecycle is measured. */ });
            engine.advanceTicks(1);
            assertThat(task.state()).isEqualTo(TaskState.COMPLETED);
            Field field = ManualScheduler.Engine.class.getDeclaredField("tasks");
            field.setAccessible(true); // NOPMD - The double's retention is intentionally checked without a public API.
            assertThat((List<?>) field.get(engine)).isEmpty();
        }
    }
}
