package com.ultikits.ultitools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Deterministic task-lifecycle assertions reusable by the real scheduler implementation. */
public abstract class AbstractSchedulerContractTest {
    private Fixture fixture;
    private Scheduler scheduler;
    private ServerMock server;

    /**
     * Creates an isolated scheduling environment. A production subclass supplies real Scheduler
     * instances and adapts the backend's test clock, entity removal, owner unload and captured logger
     * through Fixture. Clock advances must finish due executions before returning, without sleeps.
     * Multiple owners must share the same environment, so unload-isolation assertions are meaningful.
     */
    protected abstract Fixture createFixture();

    /** Backend/lifecycle controls for tests; none of these methods extend the public Scheduler API. */
    public interface Fixture extends AutoCloseable {
        Scheduler scheduler(String owner);
        void advanceTicks(long ticks);
        void advanceTime(Duration elapsed);
        void retire(Entity entity);
        void unload(String owner);
        List<LogRecord> warnings();
        @Override default void close() { }
    }

    @BeforeEach
    void setUpSchedulerContract() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        fixture = createFixture();
        scheduler = fixture.scheduler("FirstModule");
    }

    @AfterEach
    void tearDownSchedulerContract() {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    @Test
    void run_onOwnThread_isDeferredNotInline() {
        TaskContext context = scheduler.global();
        List<String> events = new ArrayList<>();
        AtomicBoolean ownsThread = new AtomicBoolean();
        TaskHandle outer = context.run(() -> {
            ownsThread.set(context.isCurrentThread());
            events.add("outer-start");
            context.run(() -> events.add("inner"));
            events.add("outer-end");
        });
        assertThat(events).isEmpty();
        assertThat(outer.state()).isEqualTo(TaskState.SCHEDULED);
        fixture.advanceTicks(1);
        assertThat(ownsThread).isTrue();
        assertThat(events).containsExactly("outer-start", "outer-end");
        assertThat(outer.state()).isEqualTo(TaskState.COMPLETED);
        fixture.advanceTicks(1);
        assertThat(events).containsExactly("outer-start", "outer-end", "inner");
    }

    @Test
    void runLater_zero_behavesAsRun() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle first = scheduler.global().run(runs::incrementAndGet);
        TaskHandle second = scheduler.global().runLater(Duration.ZERO, runs::incrementAndGet);
        assertThat(runs).hasValue(0);
        assertThat(List.of(first.state(), second.state())).containsOnly(TaskState.SCHEDULED);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(2);
        assertThat(List.of(first.state(), second.state())).containsOnly(TaskState.COMPLETED);
    }

    @Test
    void runLater_51ms_runsAfterTwoTicks() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.global().runLater(Duration.ofMillis(51), runs::incrementAndGet);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(0);
        assertThat(handle.state()).isEqualTo(TaskState.SCHEDULED);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(1);
        assertThat(handle.state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void runRepeating_subTickPeriodOnTickContext_isRejected() {
        assertThatThrownBy(() -> scheduler.global().runRepeating(Duration.ZERO, Duration.ofMillis(49), ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runRepeating_subTickPeriodOnAsync_isAccepted() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.async().runRepeating(Duration.ofMillis(49), Duration.ofMillis(49),
                ignored -> runs.incrementAndGet());
        assertThat(handle.isRepeating()).isTrue();
        fixture.advanceTime(Duration.ofMillis(48));
        assertThat(runs).hasValue(0);
        fixture.advanceTime(Duration.ofMillis(1));
        assertThat(runs).hasValue(1);
        fixture.advanceTime(Duration.ofMillis(49));
        assertThat(runs).hasValue(2);
        assertThat(handle.cancel()).isTrue();
    }

    @Test
    void cancel_beforeRun_preventsRunAndCompletesCancelled() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.global().run(runs::incrementAndGet);
        assertThat(handle.isRepeating()).isFalse();
        assertThat(handle.cancel()).isTrue();
        fixture.advanceTicks(3);
        assertThat(runs).hasValue(0);
        assertThat(handle.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(failure(handle.completion())).isInstanceOf(CancellationException.class);
    }

    @Test
    void cancel_isIdempotent() {
        TaskHandle handle = scheduler.global().run(() -> { });
        assertThat(handle.cancel()).isTrue();
        assertThat(handle.cancel()).isFalse();
        fixture.advanceTicks(1);
        assertThat(handle.cancel()).isFalse();
        assertThat(handle.state()).isEqualTo(TaskState.CANCELLED);
    }

    @Test
    void oneShot_throwing_endsFailedWithException() {
        IllegalStateException thrown = new IllegalStateException("one-shot failure");
        TaskHandle handle = scheduler.global().run(() -> { throw thrown; });
        fixture.advanceTicks(1);
        assertThat(handle.state()).isEqualTo(TaskState.FAILED);
        assertThat(failure(handle.completion())).isSameAs(thrown);
        assertThat(handle.cancel()).isFalse();
    }

    @Test
    void repeating_throwing_logsAndContinues() {
        AtomicInteger runs = new AtomicInteger();
        IllegalStateException thrown = new IllegalStateException("repeating failure");
        TaskHandle handle = scheduler.global().runRepeating(Duration.ZERO, Ticks.TICK, own -> {
            assertThat(own.isRepeating()).isTrue();
            assertThat(own.state()).isEqualTo(TaskState.RUNNING);
            if (runs.incrementAndGet() == 1) {
                throw thrown;
            }
        });
        fixture.advanceTicks(1);
        assertThat(handle.state()).isEqualTo(TaskState.SCHEDULED);
        assertThat(handle.completion().toCompletableFuture()).isNotDone();
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(2);
        assertThat(fixture.warnings()).anySatisfy(record -> {
            assertThat(record.getMessage()).contains(scheduler.owner());
            assertThat(record.getThrown()).isSameAs(thrown);
        });
        assertThat(handle.cancel()).isTrue();
    }

    @Test
    void entity_removedBeforeRun_endsRetired() {
        PlayerMock entity = server.addPlayer();
        TaskContext context = scheduler.entity(entity);
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = context.run(runs::incrementAndGet);
        CompletableFuture<Integer> future = context.supply(runs::incrementAndGet);
        fixture.retire(entity);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(0);
        assertThat(handle.state()).isEqualTo(TaskState.RETIRED);
        assertThat(failure(handle.completion())).isInstanceOf(TaskRetiredException.class);
        assertThat(failure(future)).isInstanceOf(TaskRetiredException.class);
        assertThat(handle.cancel()).isFalse();
    }

    @Test
    void entity_alreadyRemoved_executeRejects() {
        PlayerMock entity = server.addPlayer();
        TaskContext context = scheduler.entity(entity);
        fixture.retire(entity);
        assertThatThrownBy(() -> context.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void entity_removedBetweenRepeats_stopsFurtherRuns() {
        PlayerMock entity = server.addPlayer();
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.entity(entity).runRepeating(Duration.ZERO, Ticks.TICK,
                ignored -> runs.incrementAndGet());
        fixture.advanceTicks(1);
        fixture.retire(entity);
        fixture.advanceTicks(3);
        assertThat(runs).hasValue(1);
        assertThat(handle.state()).isEqualTo(TaskState.RETIRED);
        assertThat(failure(handle.completion())).isInstanceOf(TaskRetiredException.class);
    }

    @Test
    void ownerUnload_cancelsOwnTasksOnly() {
        Scheduler other = fixture.scheduler("SecondModule");
        AtomicInteger ownRuns = new AtomicInteger();
        AtomicInteger otherRuns = new AtomicInteger();
        TaskHandle pending = scheduler.global().runLater(Ticks.of(5), ownRuns::incrementAndGet);
        TaskHandle repeating = scheduler.global().runRepeating(Duration.ZERO, Ticks.TICK,
                ignored -> ownRuns.incrementAndGet());
        TaskHandle async = scheduler.async().runLater(Duration.ofSeconds(1), ownRuns::incrementAndGet);
        TaskHandle region = scheduler.region(server.addSimpleWorld("owner-world"), 0, 0)
                .runLater(Ticks.of(5), ownRuns::incrementAndGet);
        TaskHandle entity = scheduler.entity(server.addPlayer()).runLater(Ticks.of(5), ownRuns::incrementAndGet);
        TaskHandle otherTask = other.global().runRepeating(Duration.ZERO, Ticks.TICK,
                ignored -> otherRuns.incrementAndGet());
        fixture.advanceTicks(1);
        CompletableFuture<Integer> supply = scheduler.global().supply(ownRuns::incrementAndGet);
        fixture.unload(scheduler.owner());
        for (TaskHandle task : List.of(pending, repeating, async, region, entity)) {
            assertThat(task.state()).isEqualTo(TaskState.CANCELLED);
            assertThat(failure(task.completion())).isInstanceOf(CancellationException.class);
        }
        assertThat(failure(supply)).isInstanceOf(CancellationException.class);
        int before = ownRuns.get();
        fixture.advanceTicks(20);
        assertThat(ownRuns).hasValue(before);
        assertThat(otherRuns).hasValue(21);
        assertThat(otherTask.state()).isEqualTo(TaskState.SCHEDULED);
    }

    @Test
    void ownerUnload_rejectsNewSubmissions() {
        List<TaskContext> contexts = List.of(scheduler.global(), scheduler.async(),
                scheduler.region(server.addSimpleWorld("world"), 0, 0), scheduler.entity(server.addPlayer()));
        fixture.unload(scheduler.owner());
        for (TaskContext context : contexts) {
            assertThatThrownBy(() -> context.run(() -> { })).isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> context.runLater(Duration.ZERO, () -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> context.runRepeating(Duration.ZERO, Ticks.TICK, ignored -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> context.supply(() -> 1)).isInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(() -> context.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        }
        assertThatThrownBy(() -> scheduler.global().run(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void supply_cancelledBeforeStart_preventsRun() {
        AtomicInteger runs = new AtomicInteger();
        CompletableFuture<Integer> future = scheduler.global().supply(runs::incrementAndGet);
        assertThat(future.cancel(false)).isTrue();
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(0);
        assertThat(future).isCancelled();
    }

    @Test
    void supply_cancelledAfterStart_finishesBodyButRemainsCancelled() {
        AtomicReference<CompletableFuture<Integer>> reference = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture<Integer> future = scheduler.global().supply(() -> {
            reference.get().cancel(false);
            finished.set(true);
            return 42;
        });
        reference.set(future);
        fixture.advanceTicks(1);
        assertThat(finished).isTrue();
        assertThat(future).isCancelled();
    }

    @Test
    void supply_returnValueAndException_arePropagated() {
        IllegalArgumentException thrown = new IllegalArgumentException("supply failure");
        CompletableFuture<Integer> successful = scheduler.global().supply(() -> 42);
        CompletableFuture<Integer> failed = scheduler.global().supply(() -> { throw thrown; });
        fixture.advanceTicks(1);
        assertThat(successful).isCompletedWithValue(42);
        assertThat(failure(failed)).isSameAs(thrown);
    }

    @Test
    void completion_cannotBeCompletedByCaller() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.global().run(runs::incrementAndGet);
        CompletableFuture<Void> copy = handle.completion().toCompletableFuture();
        copy.complete(null);
        assertThat(handle.state()).isEqualTo(TaskState.SCHEDULED);
        assertThat(handle.completion().toCompletableFuture()).isNotDone();
        CompletableFuture<Void> cancelledCopy = handle.completion().toCompletableFuture();
        cancelledCopy.cancel(false);
        assertThat(handle.state()).isEqualTo(TaskState.SCHEDULED);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(1);
        assertThat(handle.state()).isEqualTo(TaskState.COMPLETED);
        assertThat(handle.completion().toCompletableFuture()).isCompletedWithValue(null);
    }

    @Test
    void isCurrentThread_insideOwnTask_isTrue() {
        List<TaskContext> contexts = List.of(scheduler.global(),
                scheduler.region(server.addSimpleWorld("affinity-world"), 1, -1), scheduler.entity(server.addPlayer()));
        for (TaskContext context : contexts) {
            AtomicBoolean owns = new AtomicBoolean();
            assertThat(context.isCurrentThread()).isFalse();
            TaskHandle handle = context.run(() -> owns.set(context.isCurrentThread()));
            fixture.advanceTicks(1);
            assertThat(owns).isTrue();
            assertThat(handle.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(context.isCurrentThread()).isFalse();
        }
    }

    @Test
    void isCurrentThread_asyncTask_doesNotOwnTickContexts() {
        TaskContext async = scheduler.async();
        TaskContext global = scheduler.global();
        List<Boolean> ownership = new ArrayList<>();
        TaskHandle handle = async.run(() -> {
            ownership.add(async.isCurrentThread());
            ownership.add(global.isCurrentThread());
        });
        fixture.advanceTime(Duration.ofMillis(1));
        assertThat(ownership).containsExactly(false, false);
        assertThat(handle.state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void repeating_selfCancellation_isTerminalWithoutInterruptingBody() {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = scheduler.global().runRepeating(Duration.ZERO, Ticks.TICK, own -> {
            runs.incrementAndGet();
            own.cancel();
            finished.set(true);
        });
        fixture.advanceTicks(3);
        assertThat(finished).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(handle.state()).isEqualTo(TaskState.CANCELLED);
        assertThat(failure(handle.completion())).isInstanceOf(CancellationException.class);
    }

    @Test
    void invalidDelaysPeriodsAndWorld_areRejected() {
        for (TaskContext context : List.of(scheduler.global(), scheduler.async())) {
            assertThatThrownBy(() -> context.runLater(Duration.ofNanos(-1), () -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.runRepeating(Duration.ofNanos(-1), Ticks.TICK, ignored -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.runRepeating(Duration.ZERO, Duration.ZERO, ignored -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.runRepeating(Duration.ZERO, Duration.ofNanos(-1), ignored -> { }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> scheduler.region(new Location(null, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_delegatesWithoutRunningInline() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.global().execute(runs::incrementAndGet);
        assertThat(runs).hasValue(0);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(1);
        TaskContext region = scheduler.region(new Location(server.addSimpleWorld("location-world"), 16, 64, -16));
        region.execute(runs::incrementAndGet);
        fixture.advanceTicks(1);
        assertThat(runs).hasValue(2);
    }

    @Test
    void completion_nonAsyncContinuation_runsOnCompletingThread() {
        TaskContext context = scheduler.global();
        TaskHandle handle = context.run(() -> { });
        AtomicBoolean owned = new AtomicBoolean();
        CompletionStage<Void> dependent = handle.completion().thenRun(() -> owned.set(context.isCurrentThread()));
        fixture.advanceTicks(1);
        assertThat(dependent.toCompletableFuture()).isCompletedWithValue(null);
        assertThat(owned).isTrue();
    }

    @Test
    void nullArguments_areRejectedByName() {
        TaskContext context = scheduler.global();
        assertThatNullPointerException().isThrownBy(() -> context.run(null)).withMessage("task");
        assertThatNullPointerException().isThrownBy(() -> context.runLater(null, () -> { })).withMessage("delay");
        assertThatNullPointerException().isThrownBy(() -> context.runLater(Duration.ZERO, null)).withMessage("task");
        assertThatNullPointerException().isThrownBy(() -> context.runRepeating(null, Ticks.TICK, ignored -> { }))
                .withMessage("initialDelay");
        assertThatNullPointerException().isThrownBy(() -> context.runRepeating(Duration.ZERO, null, ignored -> { }))
                .withMessage("period");
        assertThatNullPointerException().isThrownBy(() -> context.runRepeating(Duration.ZERO, Ticks.TICK, null))
                .withMessage("task");
        assertThatNullPointerException().isThrownBy(() -> context.supply(null)).withMessage("task");
        assertThatNullPointerException().isThrownBy(() -> context.execute(null)).withMessage("command");
        assertThatNullPointerException().isThrownBy(() -> scheduler.region((Location) null)).withMessage("location");
        assertThatNullPointerException().isThrownBy(() -> scheduler.region(null, 0, 0)).withMessage("world");
        assertThatNullPointerException().isThrownBy(() -> scheduler.entity(null)).withMessage("entity");
    }

    private static Throwable failure(CompletionStage<?> stage) {
        assertThat(stage.toCompletableFuture()).isCompletedExceptionally();
        Throwable thrown = stage.handle((value, failure) -> failure).toCompletableFuture().join();
        while (thrown instanceof CompletionException && thrown.getCause() != null) {
            thrown = thrown.getCause();
        }
        return thrown;
    }
}
