package com.ultikits.ultitools.ui.container;

import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.ui.CommitOutcome;
import com.ultikits.ultitools.ui.ContainerChange;
import com.ultikits.ultitools.ui.ContainerChanges;
import com.ultikits.ultitools.ui.ContainerKind;
import com.ultikits.ultitools.ui.ContainerSnapshot;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.ContainerStore;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Disposition;
import com.ultikits.ultitools.ui.EndOutcome;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.StoreAnswer;
import com.ultikits.ultitools.ui.TransferDecision;
import com.ultikits.ultitools.ui.TransferKind;
import com.ultikits.ultitools.ui.TransferRequest;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.runtime.ContainerHost;
import com.ultikits.ultitools.ui.runtime.UiOwner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Thread-confined contents, contribution accounting and exactly-once disposition. */
@ApiStatus.Internal
public final class FrameworkSlotContainer implements SlotContainer {
    private static final UUID NIL = new UUID(0, 0);
    private final FrameworkContainerRuntime runtime;
    private final UiOwner owner;
    private final ContainerSpec spec;
    private final ContainerHost host;
    private final BooleanSupplier onContainerThread;
    private final ContributionLedger ledger = new ContributionLedger();
    private final Set<UiSession<?>> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<ItemStack> contents;
    private List<ItemStack> baseline;
    private ContainerState state = ContainerState.EDITABLE;
    private boolean unloading;
    private long unloadDeadline;
    private long sequence;
    private @Nullable PendingCommit pending;
    private @Nullable CompletionStage<CommitOutcome> unloadCommit;
    private @Nullable CompletionStage<EndOutcome> ending;

    FrameworkSlotContainer(FrameworkContainerRuntime runtime, UiOwner owner, ContainerSpec spec, ContainerHost host,
            BooleanSupplier onContainerThread) {
        this.runtime = runtime;
        this.owner = owner;
        this.spec = spec;
        this.host = host;
        this.onContainerThread = onContainerThread;
        baseline = spec.baseline();
        contents = new ArrayList<>(spec.contributor().isPresent() ? spec.initial() : baseline);
        spec.contributor().ifPresent(player -> contents.forEach(item -> ledger.put(player, item)));
    }

    boolean belongsTo(FrameworkContainerRuntime candidate) { return runtime.equals(candidate); }
    void checkThread() {
        if (!onContainerThread.getAsBoolean()) {
            throw new IllegalStateException("Container called off its thread: " + owner());
        }
    }
    private void requireMutable() {
        checkThread();
        if (unloading || state != ContainerState.EDITABLE && state != ContainerState.READ_ONLY) {
            throw new IllegalStateException("Container cannot be changed in " + state + ": " + owner());
        }
    }
    @Override public String owner() { return owner.name(); }
    @Override public int size() { return spec.size(); }
    @Override public ContainerKind kind() { return spec.kind(); }
    @Override public ContainerState state() { checkThread(); return state; }
    @Override public ItemStack get(int index) { checkThread(); return StackMath.copy(contents.get(index)); }
    @Override public List<ItemStack> contents() { checkThread(); return StackMath.copies(contents); }
    @Override public boolean isViewed() { checkThread(); return !viewers.isEmpty(); }
    @Override public ContainerChanges changes() { checkThread(); return ledger.snapshot(); }
    @Override public void setReadOnly(boolean readOnly) {
        requireMutable();
        state = readOnly ? ContainerState.READ_ONLY : ContainerState.EDITABLE;
        redraw();
    }
    @Override public void load(List<ItemStack> next) {
        requireMutable();
        List<ItemStack> copy = checkedContents(next);
        contents = new ArrayList<>(copy);
        baseline = StackMath.copies(copy);
        ledger.clear();
        redraw();
    }
    private List<ItemStack> checkedContents(List<ItemStack> next) {
        List<ItemStack> copy = StackMath.copies(next);
        if (copy.size() != size()) { throw new IllegalArgumentException("contents must have container size"); }
        return copy;
    }

    void attach(UiSession<?> session) {
        checkThread();
        if (state == ContainerState.ENDED) { return; }
        if (unloading) { throw new IllegalStateException("Container cannot be viewed: " + owner()); }
        viewers.add(session);
    }
    void detach(UiSession<?> session) {
        checkThread();
        boolean removed = viewers.remove(session);
        if (!viewers.isEmpty() || state == ContainerState.ENDED) { return; }
        if (unloading) {
            unload();
        } else if (removed && spec.endWhenUnviewed()) {
            end();
        }
    }
    boolean editable() { checkThread(); return !unloading && state == ContainerState.EDITABLE; }
    TransferDecision decide(Player viewer, TransferKind kind, int index, ItemStack item, int requested) {
        checkThread();
        try {
            return Objects.requireNonNull(spec.policy().decide(new TransferRequest(viewer, kind, index, item, requested)),
                    "policy decision");
        } catch (Exception | LinkageError failure) {
            warn("Transfer policy failed", failure);
            return TransferDecision.refuse();
        }
    }
    void replace(int index, ItemStack item) { checkThread(); contents.set(index, StackMath.copy(item)); }
    void record(Player viewer, TransferKind kind, ItemStack item, int moved) {
        checkThread();
        if (kind() == ContainerKind.STORAGE) {
            if (kind == TransferKind.PUT_IN) { ledger.put(viewer.getUniqueId(), StackMath.amount(item, moved)); }
            else { ledger.take(viewer.getUniqueId(), item, moved); }
        }
    }
    void changed(ContainerChange change) {
        checkThread();
        try { spec.onChange().accept(change); }
        catch (Exception | LinkageError failure) { warn("Container listener failed", failure); }
    }
    void redraw() { checkThread(); host.changed(this); }

    @Override public CompletionStage<CommitOutcome> commit() {
        checkThread();
        if (state == ContainerState.COMMITTING) { return Objects.requireNonNull(pending).stage; }
        requireMutable();
        return startCommit();
    }
    private CompletionStage<CommitOutcome> startCommit() {
        ContainerStore store = spec.store().orElseThrow(() -> new IllegalStateException("No store"));
        if (ledger.snapshot().isEmpty()) {
            return CompletableFuture.completedFuture(CommitOutcome.UNCHANGED).minimalCompletionStage();
        }
        PendingCommit work = new PendingCommit(new ContainerSnapshot(contents, baseline, ++sequence), state,
                System.nanoTime() + spec.commitTimeout().toNanos());
        pending = work;
        state = ContainerState.COMMITTING;
        redraw();
        work.timeout = host.scheduler().global().runLater(spec.commitTimeout(),
                () -> finishCommit(work, StoreAnswer.unknown(new TimeoutException("Commit timeout")), true));
        try {
            Objects.requireNonNull(store.write(work.snapshot), "store stage")
                    .whenComplete((answer, failure) -> recordAnswer(work, answer, failure));
        } catch (Exception | LinkageError failure) {
            recordAnswer(work, null, failure);
        }
        return work.stage;
    }
    private void recordAnswer(PendingCommit work, @Nullable StoreAnswer answer, @Nullable Throwable failure) {
        StoreAnswer resolved = failure == null && answer != null ? answer : StoreAnswer.unknown(failure);
        work.answer.complete(resolved);
        onThread(() -> finishCommit(work, resolved, false));
    }
    private void onThread(Runnable action) {
        if (onContainerThread.getAsBoolean()) { action.run(); }
        else { host.scheduler().global().run(action); }
    }
    private void finishCommit(PendingCommit work, StoreAnswer answer, boolean timeout) {
        checkThread();
        if (work.resolved) {
            if (!timeout && !work.answered && !work.lateWarning) {
                work.lateWarning = true;
                warn("Late store answer ignored", null);
            }
            return;
        }
        work.resolved = true;
        work.answered = !timeout;
        if (work.timeout != null) { work.timeout.cancel(); }
        if (answer instanceof StoreAnswer.Written) {
            baseline = work.snapshot.contents();
            ledger.clear();
            state = work.previous;
            redraw();
            work.result.complete(CommitOutcome.WRITTEN);
        } else if (answer instanceof StoreAnswer.Refused refused) {
            if (refused.current().size() != size()) {
                finishUnknown(work, "Refused answer of wrong size counted as Unknown", null);
            } else {
                finishRefused(work, refused);
            }
        } else {
            Throwable cause = answer instanceof StoreAnswer.Unknown unknown ? unknown.cause() : null;
            finishUnknown(work, "Store answer unknown", cause);
        }
    }
    private void finishUnknown(PendingCommit work, String message, @Nullable Throwable failure) {
        state = ContainerState.FAILED;
        warn(message, failure);
        redraw();
        work.result.complete(CommitOutcome.UNKNOWN);
    }
    private void finishRefused(PendingCommit work, StoreAnswer.Refused refused) {
        ContainerChanges changes = ledger.snapshot();
        contents = new ArrayList<>(refused.current());
        baseline = StackMath.copies(contents);
        ledger.clear();
        redraw();
        returnContributions(changes).whenComplete((ignored, failure) -> onThread(() -> {
            if (state != ContainerState.ENDED) { state = work.previous; }
            redraw();
            work.result.complete(CommitOutcome.REFUSED);
        }));
    }

    @Override public List<ItemStack> drain() {
        requireMutable();
        List<ItemStack> drained = contents();
        clearAndEnd();
        ending = CompletableFuture.completedFuture(EndOutcome.DRAINED).minimalCompletionStage();
        return drained;
    }
    @Override public CompletionStage<EndOutcome> end() {
        checkThread();
        if (ending != null) { return ending; }
        CompletableFuture<EndOutcome> result = new CompletableFuture<>();
        ending = result.minimalCompletionStage();
        if (kind() == ContainerKind.TEMPLATE) {
            endNow(result, EndOutcome.DISCARDED_TEMPLATE);
        } else if (state == ContainerState.FAILED) {
            endNow(result, EndOutcome.UNKNOWN_KEPT_BY_STORE);
        } else if (state == ContainerState.COMMITTING || spec.disposition().orElseThrow() == Disposition.COMMIT) {
            endAfterCommit(result);
        } else {
            ContainerChanges changes = ledger.snapshot();
            List<ItemStack> unattributed = StackMath.subtract(contents, changes.putIn());
            CompletionStage<Void> returned = returnContributions(changes);
            undelivered(NIL, unattributed);
            clearAndEnd();
            returned.whenComplete((ignored, failure) -> onThread(() -> result.complete(EndOutcome.RETURNED)));
        }
        return ending;
    }
    private void endAfterCommit(CompletableFuture<EndOutcome> result) {
        CompletionStage<CommitOutcome> selected = unloadCommit;
        if (selected == null) {
            selected = state == ContainerState.COMMITTING ? Objects.requireNonNull(pending).stage : startCommit();
        }
        selected.thenAccept(outcome -> endNow(result, switch (outcome) {
            case WRITTEN -> EndOutcome.COMMITTED;
            case REFUSED -> EndOutcome.REFUSED_RETURNED;
            case UNKNOWN -> EndOutcome.UNKNOWN_KEPT_BY_STORE;
            case UNCHANGED -> EndOutcome.UNCHANGED;
        }));
    }
    private void endNow(CompletableFuture<EndOutcome> result, EndOutcome outcome) {
        clearAndEnd();
        result.complete(outcome);
    }
    private void clearAndEnd() {
        contents = new ArrayList<>(StackMath.empty(size()));
        ledger.clear();
        viewers.clear();
        state = ContainerState.ENDED;
        redraw();
    }

    void prepareUnload(long deadline) {
        checkThread();
        unloading = true;
        unloadDeadline = deadline;
        if (state == ContainerState.COMMITTING) { unloadCommit = Objects.requireNonNull(pending).stage; }
    }
    void startUnloadCommit() {
        checkThread();
        if (unloadCommit == null && (state == ContainerState.EDITABLE || state == ContainerState.READ_ONLY)
                && spec.disposition().orElse(null) == Disposition.COMMIT && !ledger.snapshot().isEmpty()) {
            unloadCommit = startCommit();
        }
    }
    void unload() {
        checkThread();
        end();
        PendingCommit work = pending;
        if (state == ContainerState.COMMITTING && work != null && !work.resolved) {
            awaitAnswer(work);
        }
    }
    private void awaitAnswer(PendingCommit work) {
        long remaining = Math.max(0, Math.min(work.deadline, unloadDeadline) - System.nanoTime());
        try {
            StoreAnswer answer = work.answer.get(remaining, TimeUnit.NANOSECONDS);
            finishCommit(work, answer, false);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            finishCommit(work, StoreAnswer.unknown(failure), true);
        } catch (ExecutionException | TimeoutException failure) {
            finishCommit(work, StoreAnswer.unknown(failure), true);
        }
    }
    private CompletionStage<Void> returnContributions(ContainerChanges changes) {
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        changes.putInBy().forEach((player, items) -> deliveries.add(returnBatch(player, items)));
        return CompletableFuture.allOf(deliveries.toArray(CompletableFuture<?>[]::new));
    }
    private CompletableFuture<Void> returnBatch(UUID player, List<ItemStack> items) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        try {
            host.returnTo(player, StackMath.copies(items)).whenComplete((delivery, failure) -> onThread(() -> {
                finishReturn(player, items, delivery, failure);
                settled.complete(null);
            }));
        } catch (Exception | LinkageError failure) {
            finishReturn(player, items, null, failure);
            settled.complete(null);
        }
        return settled;
    }
    private void finishReturn(UUID player, List<ItemStack> items, @Nullable Delivery delivery, @Nullable Throwable failure) {
        if (failure != null) { warn("Contributor delivery failed", failure); }
        undelivered(player, failure == null && delivery != null ? delivery.undelivered() : items);
    }
    private void undelivered(UUID player, List<ItemStack> items) {
        if (items.isEmpty()) { return; }
        try { spec.undelivered().orElseThrow().undelivered(player, StackMath.copies(items)); }
        catch (Exception | LinkageError failure) { warn("Undelivered handler failed", failure); }
    }
    void warn(String message, @Nullable Throwable failure) {
        LogRecord record = new LogRecord(Level.WARNING, message + " for owner " + owner());
        record.setThrown(failure);
        host.warn(record);
    }

    private static final class PendingCommit {
        final ContainerSnapshot snapshot;
        final ContainerState previous;
        final long deadline;
        final CompletableFuture<StoreAnswer> answer = new CompletableFuture<>();
        final CompletableFuture<CommitOutcome> result = new CompletableFuture<>();
        final CompletionStage<CommitOutcome> stage = result.minimalCompletionStage();
        @Nullable TaskHandle timeout;
        boolean resolved;
        boolean answered;
        boolean lateWarning;
        PendingCommit(ContainerSnapshot snapshot, ContainerState previous, long deadline) {
            this.snapshot = snapshot;
            this.previous = previous;
            this.deadline = deadline;
        }
    }
}
