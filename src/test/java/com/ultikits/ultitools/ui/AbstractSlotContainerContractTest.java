package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.SimpleFormatter;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** E12-E17 and E19: the same lifecycle tests apply to reference and real containers. */
@Timeout(30)
public abstract class AbstractSlotContainerContractTest extends EditableContractSupport {
    /**
     * Supplies module-owned containers and a gesture engine for creating genuine ledger changes. Store
     * answers may complete on a foreign thread: marshal them via the returned RecordingScheduler. The
     * fixture's virtual clock must implement exact commit-timeout ticks, and delivery must reach PlayerMock.
     */
    @Override protected abstract AbstractUiServiceContractTest.Fixture createFixture();

    @Test void end_isIdempotent() {
        SlotContainer container = service.container(storage(1).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        CompletionStage<EndOutcome> first = container.end();
        assertThat((Object) container.end()).isSameAs(first);
        settle();
        assertThat(completed(first)).isEqualTo(EndOutcome.RETURNED);
        assertThat((Object) container.end()).isSameAs(first);
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(container.isViewed()).isFalse();
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(8));
    }
    @Test void drain_endsWithoutDisposition() {
        AtomicInteger writes = new AtomicInteger();
        SlotContainer container = service.container(storage(1).disposition(Disposition.COMMIT).store(snapshot -> {
            writes.incrementAndGet(); return CompletableFuture.completedFuture(StoreAnswer.written());
        }).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        List<ItemStack> drained = container.drain();
        assertThat(drained).containsExactly(stone(8));
        drained.getFirst().setAmount(1);
        assertThat(container.get(0).isEmpty()).isTrue();
        assertThat(container.state()).isEqualTo(ContainerState.ENDED);
        assertThat(completed(container.end())).isEqualTo(EndOutcome.DRAINED);
        assertThat(writes).hasValue(0);
        assertThat(undelivered).isEmpty();
        assertThatThrownBy(container::drain).isInstanceOf(IllegalStateException.class);
    }
    @Test void return_offlineContributor_goesToUndeliveredOnce() {
        SlotContainer container = service.container(storage(1).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        fixture.disconnect(viewer);
        CompletionStage<EndOutcome> result = container.end();
        container.end();
        settle();
        assertThat(completed(result)).isEqualTo(EndOutcome.RETURNED);
        assertThat(undeliveredBatches).containsExactly(viewer.getUniqueId());
        assertThat(undelivered.get(viewer.getUniqueId())).containsExactly(stone(8));
    }
    @Test void return_unattributedItems_goToUndeliveredUnderNilUuid() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).build());
        CompletionStage<EndOutcome> result = container.end();
        settle();
        assertThat(completed(result)).isEqualTo(EndOutcome.RETURNED);
        assertThat(undeliveredBatches).containsExactly(new UUID(0, 0));
        assertThat(undelivered.get(new UUID(0, 0))).containsExactly(stone(8));
    }
    @Test void commit_written_movesBaseline() {
        List<ContainerSnapshot> snapshots = new ArrayList<>();
        SlotContainer container = service.container(storage(1).store(snapshot -> {
            snapshots.add(snapshot);
            snapshot.contents().getFirst().setAmount(1);
            return CompletableFuture.completedFuture(StoreAnswer.written());
        }).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.WRITTEN);
        assertThat(container.changes().isEmpty()).isTrue();
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(snapshots.getFirst().contents()).containsExactly(stone(8));
        assertThat(snapshots.getFirst().baseline().getFirst().isEmpty()).isTrue();
        InMemoryViewerItems side = side();
        assertThat(apply(container, side, new Gesture.Pickup(SLOT, true))).isTrue();
        container.setReadOnly(true);
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.WRITTEN);
        assertThat(container.state()).isEqualTo(ContainerState.READ_ONLY);
        assertThat(snapshots.getLast().baseline()).containsExactly(stone(8));
        assertThat(snapshots.getLast().contents()).containsExactly(stone(4));
        assertThat(snapshots.getLast().sequence()).isGreaterThan(snapshots.getFirst().sequence());
    }
    @Test void commit_refused_returnsPutInsAndLoadsCurrent_keepsTakeOuts() {
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8)))
                .store(snapshot -> CompletableFuture.completedFuture(StoreAnswer.refused(List.of(stone(10))))).build());
        InMemoryViewerItems side = side();
        side.cursor(dirt(3));
        assertThat(apply(container, side, new Gesture.SwapWithCursor(SLOT))).isTrue();
        CompletionStage<CommitOutcome> result = container.commit();
        settle();
        assertThat(completed(result)).isEqualTo(CommitOutcome.REFUSED);
        assertThat(side.cursor()).isEqualTo(stone(8));
        assertThat(viewer.getInventory().getItem(0)).isEqualTo(dirt(3));
        assertThat(container.contents()).containsExactly(stone(10));
        assertThat(container.changes().isEmpty()).isTrue();
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
        assertThat(undelivered).isEmpty();
    }
    @Test void commit_unknown_failsReadOnlyAndRefundsNothing() {
        SlotContainer container = service.container(storage(1).store(snapshot -> CompletableFuture.completedFuture(StoreAnswer.unknown(null)))
                .initial(viewer.getUniqueId(), List.of(stone(8))).build());
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.UNKNOWN);
        settle();
        assertThat(container.state()).isEqualTo(ContainerState.FAILED);
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(viewer.getInventory().getStorageContents()).allSatisfy(item -> assertThat(item == null || item.isEmpty()).isTrue());
        assertThat(undelivered).isEmpty();
        assertThatThrownBy(() -> container.setReadOnly(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(container::commit).isInstanceOf(IllegalStateException.class);
        assertThat(apply(container, side(), new Gesture.Pickup(SLOT, false))).isFalse();
    }
    @Test void commit_timeout_countsAsUnknown_lateAnswerIgnored() {
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        SlotContainer container = service.container(storage(1).commitTimeout(Duration.ofSeconds(1)).store(snapshot -> answer)
                .initial(viewer.getUniqueId(), List.of(stone(8))).build());
        CompletionStage<CommitOutcome> result = container.commit();
        fixture.advanceTicks(19);
        assertThat(container.state()).isEqualTo(ContainerState.COMMITTING);
        assertThat(result.toCompletableFuture()).isNotDone();
        fixture.advanceTicks(1);
        assertThat(completed(result)).isEqualTo(CommitOutcome.UNKNOWN);
        answer.complete(StoreAnswer.refused(List.of(ItemStack.empty())));
        settle();
        assertThat(container.get(0)).isEqualTo(stone(8));
        assertThat(container.state()).isEqualTo(ContainerState.FAILED);
        assertThat(undelivered).isEmpty();
        assertThat(fixture.warnings()).anySatisfy(record -> assertThat(new SimpleFormatter().formatMessage(record)).contains(service.owner(), "Late"));
    }
    @Test void commit_noChanges_unchangedWithoutStoreCall() {
        AtomicInteger calls = new AtomicInteger();
        SlotContainer container = service.container(storage(1).baseline(List.of(stone(8))).store(snapshot -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(StoreAnswer.written());
        }).build());
        assertThat(completed(container.commit())).isEqualTo(CommitOutcome.UNCHANGED);
        assertThat(calls).hasValue(0);
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
    }
    @Test void commit_whileCommitting_returnsSameStage() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        SlotContainer container = service.container(storage(1).initial(viewer.getUniqueId(), List.of(stone(8))).store(snapshot -> {
            calls.incrementAndGet(); return answer;
        }).build());
        CompletionStage<CommitOutcome> first = container.commit();
        assertThat((Object) container.commit()).isSameAs(first);
        assertThat(calls).hasValue(1);
        assertThat(container.state()).isEqualTo(ContainerState.COMMITTING);
        answer.complete(StoreAnswer.written());
        settle();
        assertThat(completed(first)).isEqualTo(CommitOutcome.WRITTEN);
    }
    @Test void end_commitDisposition_handlesEachStoreAnswer() {
        for (StoreAnswer answer : List.of(StoreAnswer.written(), StoreAnswer.refused(List.of(ItemStack.empty())), StoreAnswer.unknown(null))) {
            viewer.getInventory().clear();
            SlotContainer container = service.container(storage(1).disposition(Disposition.COMMIT)
                    .initial(viewer.getUniqueId(), List.of(stone(8))).store(snapshot -> CompletableFuture.completedFuture(answer)).build());
            CompletionStage<EndOutcome> result = container.end();
            settle();
            EndOutcome expected = answer instanceof StoreAnswer.Written ? EndOutcome.COMMITTED
                    : answer instanceof StoreAnswer.Refused ? EndOutcome.REFUSED_RETURNED : EndOutcome.UNKNOWN_KEPT_BY_STORE;
            assertThat(completed(result)).isEqualTo(expected);
            assertThat(container.state()).isEqualTo(ContainerState.ENDED);
            assertThat(container.get(0).isEmpty()).isTrue();
            if (answer instanceof StoreAnswer.Refused) {
                assertThat(viewer.getInventory().getItem(0)).isEqualTo(stone(8));
            } else {
                assertThat(viewer.getInventory().getItem(0)).satisfies(item -> assertThat(item == null || item.isEmpty()).isTrue());
            }
        }
    }
    @Test void commit_exceptionOrFailedStage_countsAsUnknown() {
        ContainerStore throwsOnWrite = snapshot -> { throw new IllegalStateException("store"); };
        ContainerStore failedStage = snapshot -> CompletableFuture.failedFuture(new IllegalStateException("store"));
        for (ContainerStore store : List.of(throwsOnWrite, failedStage)) {
            SlotContainer container = service.container(storage(1).store(store).initial(viewer.getUniqueId(), List.of(stone(8))).build());
            assertThat(completed(container.commit())).isEqualTo(CommitOutcome.UNKNOWN);
            assertThat(container.state()).isEqualTo(ContainerState.FAILED);
            assertThat(undelivered).isEmpty();
        }
    }
    @Test void commit_foreignAnswer_completesOnContainerThread() throws InterruptedException {
        CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        SlotContainer container = service.container(storage(1).store(snapshot -> answer).initial(viewer.getUniqueId(), List.of(stone(8))).build());
        AtomicReference<Thread> completionThread = new AtomicReference<>();
        CompletionStage<CommitOutcome> result = container.commit();
        result.thenAccept(ignored -> completionThread.set(Thread.currentThread()));
        Thread storeThread = new Thread(() -> answer.complete(StoreAnswer.written()), "store-answer");
        storeThread.start(); storeThread.join(10_000);
        assertThat(storeThread.isAlive()).isFalse();
        assertThat(result.toCompletableFuture()).isNotDone();
        settle();
        assertThat(completed(result)).isEqualTo(CommitOutcome.WRITTEN);
        assertThat(completionThread).hasValue(Thread.currentThread());
    }
    @Test void offThread_anyMethod_throws() throws InterruptedException {
        SlotContainer container = service.container(storage(1).store(snapshot -> CompletableFuture.completedFuture(StoreAnswer.written())).build());
        List<Runnable> methods = List.of(container::state, () -> container.get(0),
                container::contents, container::isViewed, () -> container.setReadOnly(false), () -> container.load(List.of(ItemStack.empty())),
                container::changes, container::commit, container::drain, container::end);
        List<Throwable> failures = new ArrayList<>();
        Thread caller = new Thread(() -> methods.forEach(method -> {
            try { method.run(); failures.add(new AssertionError("Method allowed off-thread access")); }
            catch (Throwable thrown) { failures.add(thrown); }
        }), "container-caller");
        caller.start(); caller.join(10_000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failures).hasSize(10).allSatisfy(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class));
        assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
    }
    @Test void offThread_immutableGetters_answer() throws Throwable {
        SlotContainer container = service.container(storage(1).build());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                assertThat(container.owner()).isEqualTo(service.owner());
                assertThat(container.size()).isOne();
                assertThat(container.kind()).isEqualTo(ContainerKind.STORAGE);
                assertThat(grid(container).binding(SLOT)).isPresent();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "immutable-container-caller");
        caller.start(); caller.join(10_000);
        assertThat(caller.isAlive()).isFalse();
        if (failure.get() != null) { throw failure.get(); }
    }
    @Test void loadAndChanges_copyAndResetBaseline() {
        SlotContainer container = service.container(storage(1).build());
        InMemoryViewerItems side = side(); side.cursor(stone(8));
        assertThat(apply(container, side, new Gesture.Place(SLOT, false))).isTrue();
        ContainerChanges changes = container.changes();
        changes.putIn().getFirst().setAmount(1);
        changes.putInBy().get(viewer.getUniqueId()).getFirst().setAmount(1);
        assertThat(changes.putIn()).containsExactly(stone(8));
        assertThat(changes.putInBy().get(viewer.getUniqueId())).containsExactly(stone(8));
        ItemStack item = dirt(3);
        container.load(List.of(item)); item.setAmount(1);
        container.contents().getFirst().setAmount(1);
        container.get(0).setAmount(1);
        assertThat(container.get(0)).isEqualTo(dirt(3));
        assertThat(container.changes().isEmpty()).isTrue();
        assertThatThrownBy(() -> container.load(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changes.putInBy().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
