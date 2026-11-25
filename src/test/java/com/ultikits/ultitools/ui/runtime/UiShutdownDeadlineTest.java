package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@Timeout(5)
class UiShutdownDeadlineTest {
    private static final long BUDGET = Duration.ofSeconds(10).toNanos();
    private static final long QUICK_ANSWER = Duration.ofMillis(2).toNanos();
    private ManualScheduler.Engine clock;
    private UiRuntime runtime;
    private final AtomicLong nanoTime = new AtomicLong(Duration.ofSeconds(1).toNanos());
    private final Deque<WaitPlan> waits = new ArrayDeque<>();
    private final List<Long> offeredWaits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        clock = new ManualScheduler.Engine();
        Thread serverThread = Thread.currentThread();
        FrameworkContainerRuntime actual = new FrameworkContainerRuntime(
                () -> serverThread.equals(Thread.currentThread()), (player, text) -> { });
        FrameworkContainerRuntime containers = mock(FrameworkContainerRuntime.class, delegatesTo(actual));
        doAnswer(call -> {
            long deadline = call.getArgument(1);
            offeredWaits.add(Math.max(0, deadline - nanoTime.get()));
            // Keep the real container lifecycle, but expire its physical wait: the controlled wait below supplies time.
            actual.prepareUnload(call.getArgument(0), System.nanoTime());
            return null;
        }).when(containers).prepareUnload(any(UiOwner.class), anyLong());
        doAnswer(call -> {
            WaitPlan plan = waits.removeFirst();
            long remaining = Math.max(0, (long) call.getArgument(1) - nanoTime.get());
            long elapsed = Math.min(remaining, plan.answerAfter);
            nanoTime.addAndGet(elapsed);
            if (plan.answerAfter <= remaining) { plan.answer.complete(StoreAnswer.written()); }
            actual.finishUnload(call.getArgument(0), System.nanoTime());
            return null;
        }).when(containers).finishUnload(any(UiOwner.class), anyLong());
        runtime = new UiRuntime(clock.scheduler("SharedShutdown"), containers, Logger.getAnonymousLogger(),
                serverThread, Duration.ofNanos(BUDGET), nanoTime::get);
    }

    @AfterEach
    void tearDown() {
        try {
            runtime.close();
            clock.close();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void slowNonUiUnloadBetweenModulesDoesNotConsumeTheRemainingStorageWait() {
        UiService firstOwner = runtime.service("alpha");
        UiService secondOwner = runtime.service("beta");
        SlotContainer first = committed(firstOwner, QUICK_ANSWER);
        SlotContainer second = committed(secondOwner, QUICK_ANSWER);
        runtime.beginShutdown();
        runtime.unload(firstOwner);
        nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
        runtime.unload(secondOwner);
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(offeredWaits).containsExactly(BUDGET, BUDGET - QUICK_ANSWER);
    }

    @Test
    void twoBlockedModulesShareExactlyOneWaitBudget() {
        SlotContainer first = committed(runtime.service("alpha"), Long.MAX_VALUE);
        SlotContainer second = committed(runtime.service("beta"), Long.MAX_VALUE);
        long start = nanoTime.get();
        runtime.beginShutdown();
        runtime.close();
        assertThat(nanoTime.get() - start).isEqualTo(BUDGET);
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(offeredWaits).containsExactly(BUDGET, 0L);
    }

    @Test
    void firstSuccessfulStoreLeavesTheSecondModuleAlmostAllTheBudget() {
        SlotContainer first = committed(runtime.service("alpha"), QUICK_ANSWER);
        SlotContainer second = committed(runtime.service("beta"), QUICK_ANSWER);
        runtime.close();
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(offeredWaits).containsExactly(BUDGET, BUDGET - QUICK_ANSWER);
    }

    @Test
    void repeatedBeginShutdownDoesNotRestoreAnExhaustedBudget() {
        UiService firstOwner = runtime.service("alpha");
        UiService secondOwner = runtime.service("beta");
        committed(firstOwner, Long.MAX_VALUE);
        SlotContainer second = committed(secondOwner, QUICK_ANSWER);
        runtime.beginShutdown();
        runtime.unload(firstOwner);
        runtime.beginShutdown();
        runtime.unload(secondOwner);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(offeredWaits).containsExactly(BUDGET, 0L);
    }

    @Test
    void ordinaryModuleUnloadsEachRetainAnIndependentWait() {
        UiService firstOwner = runtime.service("alpha");
        UiService secondOwner = runtime.service("beta");
        SlotContainer first = committed(firstOwner, Long.MAX_VALUE);
        SlotContainer second = committed(secondOwner, QUICK_ANSWER);
        runtime.unload(firstOwner);
        nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
        runtime.unload(secondOwner);
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(offeredWaits).containsExactly(BUDGET, BUDGET);
    }

    @Test
    void monotonicClockWrapDoesNotRestoreTheBudgetOrLoseAQuickAnswer() {
        nanoTime.set(Long.MAX_VALUE - QUICK_ANSWER);
        SlotContainer first = committed(runtime.service("alpha"), QUICK_ANSWER * 2);
        SlotContainer second = committed(runtime.service("beta"), Long.MAX_VALUE);
        runtime.close();
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(offeredWaits).containsExactly(BUDGET, BUDGET - QUICK_ANSWER * 2);
    }

    private SlotContainer committed(UiService service, long answerAfter) {
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        waits.addLast(new WaitPlan(answerAfter, answer));
        return service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT)
                .store(snapshot -> answer).undelivered((id, items) -> { })
                .initial(UUID.randomUUID(), List.of(new ItemStack(Material.STONE, 8))).build());
    }

    private static final class WaitPlan {
        private final long answerAfter;
        private final CompletableFuture<StoreAnswer> answer;

        private WaitPlan(long answerAfter, CompletableFuture<StoreAnswer> answer) {
            this.answerAfter = answerAfter;
            this.answer = answer;
        }
    }
}
