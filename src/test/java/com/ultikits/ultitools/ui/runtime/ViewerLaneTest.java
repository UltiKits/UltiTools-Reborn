package com.ultikits.ultitools.ui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.ui.RecordingScheduler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ViewerLaneTest {
    private ManualScheduler.Engine clock;
    private RecordingScheduler scheduler;
    private GlobalPump pump;
    private PlayerMock viewer;
    private final Logger logger = Logger.getLogger(ViewerLaneTest.class.getName());

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        viewer = MockBukkit.mock().addPlayer();
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
        pump = new GlobalPump(scheduler, logger);
    }
    @AfterEach void tearDown() {
        try {
            if (pump != null) { pump.close(); }
            if (clock != null) { clock.close(); }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }
    @Test void thousandTasks_keepSubmissionOrderWithReversingScheduler() {
        List<Runnable> submitted = new ArrayList<>();
        TaskContext context = mock(TaskContext.class);
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return scheduler.entity(viewer).run(() -> {
                List<Runnable> batch = new ArrayList<>(submitted);
                submitted.clear();
                batch.reversed().forEach(Runnable::run);
            });
        }).when(context).run(any(Runnable.class));
        Scheduler reversing = mock(Scheduler.class);
        when(reversing.entity(viewer)).thenReturn(context);
        ViewerLane lane = new ViewerLane(reversing, viewer, pump, logger);
        List<Integer> seen = new ArrayList<>();
        IntStream.range(0, 1000).forEach(index -> lane.execute(() -> seen.add(index)));
        int drainCount = submitted.size();
        assertThat(seen).isEmpty();
        clock.advanceTicks(1);
        assertThat(seen).containsExactlyElementsOf(IntStream.range(0, 1000).boxed().toList());
        assertThat(drainCount).isOne();
    }
    @Test void retiredTasks_runOnceOnNextGlobalTick() {
        ViewerLane lane = new ViewerLane(scheduler, viewer, pump, logger);
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> scopes = new ArrayList<>();
        IntStream.range(0, 1000).forEach(ignored -> lane.execute(() -> {
            calls.incrementAndGet();
            scopes.add(RecordingScheduler.inGlobalTask());
        }));
        clock.retire(viewer);
        assertThat(calls).hasValue(0);
        clock.advanceTicks(1);
        assertThat(calls).hasValue(1000);
        assertThat(scopes).containsOnly(true);
        clock.advanceTicks(3);
        assertThat(calls).hasValue(1000);
        assertThat(lane.isRetired()).isTrue();
    }
    @Test void actionError_doesNotBlockQueuedOrLaterWork() {
        ViewerLane lane = new ViewerLane(scheduler, viewer, pump, logger);
        List<Integer> seen = new ArrayList<>();
        lane.execute(() -> { throw new AssertionError("module action"); });
        lane.execute(() -> seen.add(1));
        clock.advanceTicks(1);
        lane.execute(() -> seen.add(2));
        clock.advanceTicks(1);
        assertThat(seen).containsExactly(1, 2);
    }
    @Test void failedDrain_retriesOnEntityContext() { retryDrain(new AssertionError("scheduler wrapper")); }
    @Test void cancelledDrain_retriesOnEntityContext() { retryDrain(new CancellationException()); }
    private void retryDrain(Throwable failure) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        TaskHandle failed = mock(TaskHandle.class);
        when(failed.completion()).thenReturn(completion.minimalCompletionStage());
        TaskContext context = mock(TaskContext.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> attempts.getAndIncrement() == 0 ? failed
                : scheduler.entity(viewer).run(invocation.getArgument(0))).when(context).run(any(Runnable.class));
        Scheduler unreliable = mock(Scheduler.class);
        when(unreliable.entity(viewer)).thenReturn(context);
        ViewerLane lane = new ViewerLane(unreliable, viewer, pump, logger);
        List<Boolean> seen = new ArrayList<>();
        lane.execute(() -> seen.add(RecordingScheduler.inEntityTask(viewer)));
        completion.completeExceptionally(failure);
        clock.advanceTicks(3);
        assertThat(attempts).hasValue(2);
        assertThat(seen).containsExactly(true);
        assertThat(lane.isRetired()).isFalse();
    }
    @Test void vmError_releasesDrainAndResubmitsPendingWork() {
        ViewerLane lane = new ViewerLane(scheduler, viewer, pump, logger);
        List<Boolean> seen = new ArrayList<>();
        lane.execute(() -> { throw new InternalError("simulated VM failure"); });
        lane.execute(() -> seen.add(RecordingScheduler.inEntityTask(viewer)));
        clock.advanceTicks(3);
        assertThat(seen).containsExactly(true);
        lane.execute(() -> seen.add(RecordingScheduler.inEntityTask(viewer)));
        clock.advanceTicks(1);
        assertThat(seen).containsExactly(true, true);
    }
    @Test void offlineSubmission_runsOnGlobalWithoutEntityTask() {
        viewer.disconnect();
        ViewerLane lane = new ViewerLane(scheduler, viewer, pump, logger);
        List<Boolean> scopes = new ArrayList<>();
        lane.execute(() -> scopes.add(RecordingScheduler.inGlobalTask()));
        assertThat(scopes).isEmpty();
        clock.advanceTicks(1);
        assertThat(scopes).containsExactly(true);
    }
    @Test void quittingDuringDrain_movesRemainingWorkToGlobal() {
        ViewerLane lane = new ViewerLane(scheduler, viewer, pump, logger);
        List<Boolean> scopes = new ArrayList<>();
        lane.execute(() -> { viewer.disconnect(); lane.retire(); });
        lane.execute(() -> scopes.add(RecordingScheduler.inGlobalTask()));
        clock.advanceTicks(1);
        assertThat(scopes).isEmpty();
        clock.advanceTicks(1);
        assertThat(scopes).containsExactly(true);
    }
}
