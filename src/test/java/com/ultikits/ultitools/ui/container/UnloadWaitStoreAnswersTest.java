package com.ultikits.ultitools.ui.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.ui.CommitOutcome;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class UnloadWaitStoreAnswersTest extends FrameworkContainerTestSupport {
    @Test void writtenAnswerIsAwaitedAndAppliedOnUnloadingThread() {
        AtomicInteger writes = new AtomicInteger();
        SlotContainer container = contributed(snapshot -> {
            writes.incrementAndGet();
            return delayed(StoreAnswer.written());
        });
        CompletionStage<CommitOutcome> commit = container.commit();
        AtomicReference<Thread> appliedOn = new AtomicReference<>();
        commit.thenAccept(ignored -> appliedOn.set(Thread.currentThread()));
        fixture.unload(service);
        assertThat(completed(commit)).isEqualTo(CommitOutcome.WRITTEN);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.COMMITTED);
        assertThat(appliedOn).hasValue(Thread.currentThread());
        assertThat(writes).hasValue(1);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        fixture.advanceTicks(2);
        assertThat(fixture.warnings()).isEmpty();
    }
    @Test void silentStoreUsesSharedDeadlineAndWarnsOnceWithOwner() {
        containers.unloadWaitOverride(Duration.ofMillis(120));
        SlotContainer container = contributed(snapshot -> new CompletableFuture<>());
        fixture.unload(service);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(fixture.warnings()).singleElement().satisfies(record -> assertThat(record.getMessage()).contains(service.owner()));
        assertThat(viewer.getInventory().getItem(0)).isNull();
    }
    @Test void refusedAnswerReturnsItemsBeforeUnloadReturns() {
        SlotContainer container = contributed(snapshot -> delayed(StoreAnswer.refused(List.of(ItemStack.empty()))));
        fixture.unload(service);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.REFUSED_RETURNED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(8));
        assertThat(undelivered).isEmpty();
        fixture.advanceTicks(2);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(8));
        assertThat(fixture.warnings()).isEmpty();
    }
    @Test void severalSilentStoresStartBeforeWaitingAndSettleUnknown() {
        containers.unloadWaitOverride(Duration.ofMillis(120));
        AtomicInteger started = new AtomicInteger();
        SlotContainer first = contributed(snapshot -> {
            started.incrementAndGet();
            return new CompletableFuture<>();
        });
        SlotContainer second = contributed(snapshot -> {
            assertThat(first.state()).isEqualTo(ContainerState.COMMITTING);
            started.incrementAndGet();
            return new CompletableFuture<>();
        });
        fixture.unload(service);
        assertThat(started).hasValue(2);
        assertThat(completed(first.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(completed(second.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(fixture.warnings()).hasSize(2);
    }
    @Test void shorterCommitTimeoutSettlesUnknownWithoutTickingTheScheduler() {
        containers.unloadWaitOverride(Duration.ofSeconds(1));
        SlotContainer container = service.container(committed(1, snapshot -> new CompletableFuture<>())
                .initial(viewer.getUniqueId(), List.of(stone(8))).commitTimeout(Duration.ofMillis(80)).build());
        fixture.unload(service);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        assertThat(fixture.warnings()).hasSize(1);
    }
    @Test void exceptionalStoreAnswerIsAppliedWithItsOriginalCauseDuringUnload() {
        IllegalStateException failure = new IllegalStateException("asynchronous write failed");
        SlotContainer container = contributed(snapshot -> {
            CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> answer.completeExceptionally(failure));
            return answer;
        });
        fixture.unload(service);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        fixture.advanceTicks(2);
        assertThat(fixture.warnings()).singleElement().satisfies(record -> assertThat(record.getThrown()).isSameAs(failure));
        assertThat(viewer.getInventory().getItem(0)).isNull();
        assertThat(undelivered).isEmpty();
    }
    @Test void interruptedWaitKeepsStoreResponsibilityAndPreservesInterrupt() {
        SlotContainer container = contributed(snapshot -> new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            fixture.unload(service);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(completed(container.end())).isEqualTo(EndOutcome.UNKNOWN_KEPT_BY_STORE);
            assertThat(fixture.warnings()).singleElement().satisfies(record ->
                    assertThat(record.getThrown()).isInstanceOf(InterruptedException.class));
        } finally {
            Thread.interrupted();
        }
    }
    @Test void returnDispositionCannotHaveAStoreAndNeverWritesDuringUnload() {
        AtomicInteger writes = new AtomicInteger();
        assertThatThrownBy(() -> returning(1).store(snapshot -> {
            writes.incrementAndGet();
            return CompletableFuture.completedFuture(StoreAnswer.written());
        }).build()).isInstanceOf(IllegalArgumentException.class);
        SlotContainer container = service.container(returning(1).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        fixture.unload(service);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.RETURNED);
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(8));
        assertThat(writes).hasValue(0);
    }
    private static CompletionStage<StoreAnswer> delayed(StoreAnswer answer) {
        return CompletableFuture.supplyAsync(() -> answer, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
    }
}
