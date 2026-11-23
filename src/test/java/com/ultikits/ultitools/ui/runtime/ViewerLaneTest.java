package com.ultikits.ultitools.ui.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
