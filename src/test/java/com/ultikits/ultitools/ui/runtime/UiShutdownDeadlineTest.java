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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(5)
class UiShutdownDeadlineTest {
    private ManualScheduler.Engine clock;
    private FrameworkContainerRuntime containers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        clock = new ManualScheduler.Engine();
        Thread serverThread = Thread.currentThread();
        containers = spy(new FrameworkContainerRuntime(() -> serverThread.equals(Thread.currentThread()), (player, text) -> { }));
        runtime = new UiRuntime(clock.scheduler("SharedShutdown"), containers, Logger.getAnonymousLogger(),
                serverThread, Duration.ofMillis(300));
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
    void twoBlockedModulesShareExactlyOneDeadlineAndOneWaitBudget() {
        SlotContainer first = committed(runtime.service("alpha"), CompletableFuture::new);
        SlotContainer second = committed(runtime.service("beta"), CompletableFuture::new);
        long start = System.nanoTime();
        runtime.beginShutdown();
        runtime.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(550));
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        List<Long> deadlines = deadlines();
        assertThat(deadlines.get(1)).isEqualTo(deadlines.get(0));
    }

    @Test
    void firstSuccessfulStoreLeavesTheSecondModuleTimeToAnswer() {
        SlotContainer first = committed(runtime.service("alpha"), UiShutdownDeadlineTest::delayedWritten);
        SlotContainer second = committed(runtime.service("beta"), UiShutdownDeadlineTest::delayedWritten);
        runtime.close();
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        List<Long> deadlines = deadlines();
        assertThat(deadlines.get(1)).isEqualTo(deadlines.get(0));
    }

    @Test
    void ordinaryModuleUnloadsEachRetainAnIndependentWait() {
        UiService firstOwner = runtime.service("alpha");
        UiService secondOwner = runtime.service("beta");
        SlotContainer first = committed(firstOwner, CompletableFuture::new);
        SlotContainer second = committed(secondOwner, UiShutdownDeadlineTest::delayedWritten);
        runtime.unload(firstOwner);
        runtime.unload(secondOwner);
        assertThat(first.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(second.end().toCompletableFuture()).isCompletedWithValue(EndOutcome.COMMITTED);
        List<Long> deadlines = deadlines();
        assertThat(deadlines.get(1)).isGreaterThan(deadlines.get(0));
    }

    private List<Long> deadlines() {
        ArgumentCaptor<Long> deadlines = ArgumentCaptor.forClass(Long.class);
        verify(containers, times(2)).prepareUnload(any(UiOwner.class), deadlines.capture());
        return deadlines.getAllValues();
    }

    private static SlotContainer committed(UiService service, Supplier<CompletionStage<StoreAnswer>> answers) {
        return service.container(ContainerSpec.storage(1).disposition(Disposition.COMMIT)
                .store(snapshot -> answers.get()).undelivered((id, items) -> { })
                .initial(UUID.randomUUID(), List.of(new ItemStack(Material.STONE, 8))).build());
    }

    private static CompletionStage<StoreAnswer> delayedWritten() {
        return CompletableFuture.supplyAsync(StoreAnswer::written, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
    }
}
