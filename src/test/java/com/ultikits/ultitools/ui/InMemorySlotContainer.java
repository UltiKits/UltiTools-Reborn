package com.ultikits.ultitools.ui;

import static com.ultikits.ultitools.ui.UiTestItems.amount;
import static com.ultikits.ultitools.ui.UiTestItems.count;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Reference container: no Bukkit inventory, persistence or event handlers. */
final class InMemorySlotContainer implements SlotContainer {
    static final UUID NIL = new UUID(0, 0);
    private final String owner;
    private final ContainerSpec spec;
    private final Thread thread = Thread.currentThread();
    private final RecordingScheduler scheduler;
    private final BiFunction<UUID, List<ItemStack>, CompletionStage<Delivery>> returns;
    private final Consumer<LogRecord> warnings;
    private final Consumer<InMemorySlotContainer> redraw;
    private final Set<UiSession<?>> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Contribution> ledger = new ArrayList<>();
    private final List<ItemStack> taken = new ArrayList<>();
    private List<ItemStack> contents;
    private List<ItemStack> baseline;
    private ContainerState state = ContainerState.EDITABLE;
    private CompletionStage<CommitOutcome> inFlight;
    private PendingCommit pendingCommit;
    private CompletionStage<CommitOutcome> unloadCommit;
    private CompletionStage<EndOutcome> ending;
    private long sequence;
    private long answeredSequence;
    private long appliedAnswerSequence;

    InMemorySlotContainer(String owner, ContainerSpec spec, RecordingScheduler scheduler,
            BiFunction<UUID, List<ItemStack>, CompletionStage<Delivery>> returns,
            Consumer<LogRecord> warnings, Consumer<InMemorySlotContainer> redraw) {
        this.owner = owner;
        this.spec = spec;
        this.scheduler = scheduler;
        this.returns = returns;
        this.warnings = warnings;
        this.redraw = redraw;
        baseline = new ArrayList<>(spec.baseline());
        contents = new ArrayList<>(spec.contributor().isPresent() ? spec.initial() : spec.baseline());
        spec.contributor().ifPresent(player -> contents.forEach(item -> contribute(player, item)));
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) { // NOPMD - containers are confined by thread identity
            throw new IllegalStateException("Container called off its thread");
        }
    }
    private void requireMutable() {
        checkThread();
        if (state != ContainerState.EDITABLE && state != ContainerState.READ_ONLY) {
            throw new IllegalStateException("Container cannot be changed in " + state);
        }
    }
    @Override public String owner() { return owner; }
    @Override public int size() { return spec.size(); }
    @Override public ContainerKind kind() { return spec.kind(); }
    @Override public ContainerState state() { checkThread(); return state; }
    @Override public ItemStack get(int index) { checkThread(); return UiTestItems.copy(contents.get(index)); }
    @Override public List<ItemStack> contents() { checkThread(); return UiTestItems.copies(contents); }
    @Override public boolean isViewed() { checkThread(); return !viewers.isEmpty(); }
    @Override public void setReadOnly(boolean readOnly) {
        requireMutable();
        state = readOnly ? ContainerState.READ_ONLY : ContainerState.EDITABLE;
    }
    @Override public void load(List<ItemStack> next) {
        requireMutable();
        List<ItemStack> copy = checkedContents(next);
        contents = new ArrayList<>(copy);
        baseline = new ArrayList<>(UiTestItems.copies(copy));
        ledger.clear();
        taken.clear();
        redraw.accept(this);
    }
    private List<ItemStack> checkedContents(List<ItemStack> next) {
        List<ItemStack> copy = UiTestItems.copies(next);
        if (copy.size() != size()) {
            throw new IllegalArgumentException("contents must have container size");
        }
        return copy;
    }
    @Override public ContainerChanges changes() {
        checkThread();
        Map<UUID, List<ItemStack>> by = new LinkedHashMap<>();
        for (Contribution entry : ledger) {
            by.computeIfAbsent(entry.player, ignored -> new ArrayList<>()).add(entry.item);
        }
        return new Changes(ledger.stream().map(entry -> entry.item).toList(), taken, by);
    }

    void attach(UiSession<?> session) {
        checkThread();
        if (state == ContainerState.ENDED) {
            return;
        }
        viewers.add(session);
    }
    void detach(UiSession<?> session) {
        checkThread();
        boolean removed = viewers.remove(session);
        if (removed && viewers.isEmpty() && spec.endWhenUnviewed() && state != ContainerState.ENDED) {
            end();
        }
    }
    TransferDecision decide(Player viewer, TransferKind kind, int index, ItemStack item, int requested) {
        checkThread();
        try {
            return Objects.requireNonNull(spec.policy().decide(new TransferRequest(viewer, kind, index, item, requested)));
        } catch (Exception thrown) {
            warn("Transfer policy failed for owner {0}", thrown);
            return TransferDecision.refuse();
        }
    }
    void replace(int index, ItemStack item) { checkThread(); contents.set(index, UiTestItems.copy(item)); }
    void record(Player viewer, TransferKind kind, int index, ItemStack item, int moved) {
        checkThread();
        if (spec.kind() == ContainerKind.STORAGE) {
            if (kind == TransferKind.PUT_IN) {
                contribute(viewer.getUniqueId(), amount(item, moved));
            } else {
                withdraw(viewer.getUniqueId(), item, moved);
            }
        }
    }
    void redraw() { redraw.accept(this); }
    void changed(ContainerChange change) {
        checkThread();
        try {
            spec.onChange().accept(change);
        } catch (Exception thrown) {
            warn("Container listener failed for owner {0}", thrown);
        }
    }
    private void contribute(UUID player, ItemStack item) {
        if (!item.isEmpty()) {
            ledger.add(new Contribution(player, UiTestItems.copy(item)));
        }
    }
    private void withdraw(UUID player, ItemStack item, int moved) {
        int remaining = withdrawPass(player, item, moved, true);
        remaining = withdrawPass(player, item, remaining, false);
        ledger.removeIf(entry -> entry.item.isEmpty());
        if (remaining > 0) {
            taken.add(amount(item, remaining));
        }
    }
    private int withdrawPass(UUID player, ItemStack item, int remaining, boolean own) {
        int left = remaining;
        for (Contribution entry : ledger) {
            if (entry.player.equals(player) == own && entry.item.isSimilar(item)) {
                int removed = Math.min(left, count(entry.item));
                entry.item = amount(entry.item, count(entry.item) - removed);
                left -= removed;
            }
        }
        return left;
    }

    @Override public CompletionStage<CommitOutcome> commit() {
        checkThread();
        ContainerStore store = spec.store().orElseThrow(() -> new IllegalStateException("No store"));
        if (state == ContainerState.COMMITTING) {
            return inFlight;
        }
        requireMutable();
        if (changes().isEmpty()) {
            return CompletableFuture.completedFuture(CommitOutcome.UNCHANGED).minimalCompletionStage();
        }
        ContainerState previous = state;
        state = ContainerState.COMMITTING;
        ContainerSnapshot snapshot = new ContainerSnapshot(contents, baseline, ++sequence);
        CompletableFuture<CommitOutcome> expected = new CompletableFuture<>();
        inFlight = expected.minimalCompletionStage();
        PendingCommit pending = new PendingCommit(expected, snapshot, previous, new CompletableFuture<>(),
                System.nanoTime() + spec.commitTimeout().toNanos());
        pendingCommit = pending;
        scheduler.global().runLater(spec.commitTimeout(), () -> finishCommit(pending, StoreAnswer.unknown(null), true));
        try {
            store.write(snapshot).whenComplete((answer, failure) -> recordAnswer(pending, answer, failure));
        } catch (Exception thrown) {
            recordAnswer(pending, null, thrown);
        }
        return inFlight;
    }
    private void recordAnswer(PendingCommit pending, StoreAnswer answer, Throwable failure) {
        StoreAnswer resolved = failure == null && answer != null ? answer : StoreAnswer.unknown(failure);
        pending.answer().complete(resolved);
        onThread(() -> finishCommit(pending, resolved, false));
    }
    private void onThread(Runnable action) {
        if (Thread.currentThread() == thread) { // NOPMD - marshal only a foreign completing thread
            action.run();
        } else {
            scheduler.global().run(action);
        }
    }
    private void finishCommit(PendingCommit pending, StoreAnswer answer, boolean timeout) {
        checkThread();
        long answered = pending.snapshot().sequence();
        if (answered <= answeredSequence) {
            if (!timeout && answered > appliedAnswerSequence) {
                warn("Late store answer ignored for owner {0}", null);
            }
            return;
        }
        StoreAnswer normalized = normalizeAnswer(answer);
        answeredSequence = answered;
        if (!timeout) {
            appliedAnswerSequence = answered;
        }
        if (normalized instanceof StoreAnswer.Written) {
            baseline = new ArrayList<>(pending.snapshot().contents());
            ledger.clear();
            taken.clear();
            state = pending.previous();
            pending.result().complete(CommitOutcome.WRITTEN);
        } else if (normalized instanceof StoreAnswer.Refused refused) {
            finishRefused(pending.result(), refused, pending.previous());
        } else {
            if (answer instanceof StoreAnswer.Unknown unknown) {
                warn("Store answer unknown for owner {0}", unknown.cause());
            }
            state = ContainerState.FAILED;
            pending.result().complete(CommitOutcome.UNKNOWN);
        }
    }
    private StoreAnswer normalizeAnswer(StoreAnswer answer) {
        if (answer instanceof StoreAnswer.Refused refused && refused.current().size() != size()) {
            warn("Refused answer of wrong size counted as Unknown for owner {0}", null);
            return StoreAnswer.unknown(null);
        }
        return answer;
    }
    private void finishRefused(CompletableFuture<CommitOutcome> expected, StoreAnswer.Refused answer, ContainerState previous) {
        List<ItemStack> current = checkedContents(answer.current());
        CompletionStage<Void> refunded = returnContributions(false);
        contents = new ArrayList<>(current);
        baseline = new ArrayList<>(UiTestItems.copies(current));
        redraw.accept(this);
        ledger.clear();
        taken.clear();
        refunded.whenComplete((ignored, failure) -> onThread(() -> {
            if (state != ContainerState.ENDED) {
                state = previous;
            }
            expected.complete(CommitOutcome.REFUSED);
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
        if (ending != null) {
            return ending;
        }
        CompletableFuture<EndOutcome> result = new CompletableFuture<>();
        ending = result.minimalCompletionStage();
        if (spec.kind() == ContainerKind.TEMPLATE) {
            clearAndEnd();
            result.complete(EndOutcome.DISCARDED_TEMPLATE);
        } else if (state == ContainerState.FAILED) {
            clearAndEnd();
            result.complete(EndOutcome.UNKNOWN_KEPT_BY_STORE);
        } else if (state == ContainerState.COMMITTING || spec.disposition().orElseThrow() == Disposition.COMMIT) {
            endAfterCommit(result);
        } else {
            CompletionStage<Void> returned = returnContributions(true);
            clearAndEnd();
            returned.whenComplete((ignored, failure) -> result.complete(EndOutcome.RETURNED));
        }
        return ending;
    }
    private void endAfterCommit(CompletableFuture<EndOutcome> result) {
        CompletionStage<CommitOutcome> selected = unloadCommit == null ? commit() : unloadCommit;
        selected.thenAccept(outcome -> {
            EndOutcome end = switch (outcome) {
                case WRITTEN -> EndOutcome.COMMITTED;
                case UNCHANGED -> EndOutcome.UNCHANGED;
                case REFUSED -> EndOutcome.REFUSED_RETURNED;
                case UNKNOWN -> EndOutcome.UNKNOWN_KEPT_BY_STORE;
            };
            clearAndEnd();
            result.complete(end);
        });
    }
    void prepareUnload() {
        checkThread();
        if (state == ContainerState.COMMITTING) {
            unloadCommit = inFlight;
        }
    }
    void startUnloadCommit() {
        checkThread();
        if (unloadCommit == null && (state == ContainerState.EDITABLE || state == ContainerState.READ_ONLY)
                && spec.disposition().orElse(null) == Disposition.COMMIT) {
            unloadCommit = commit();
        }
    }
    void unload(long unloadDeadline) {
        checkThread();
        end();
        PendingCommit pending = pendingCommit;
        if (state == ContainerState.COMMITTING && pending != null && !pending.result().isDone()
                && pending.snapshot().sequence() > answeredSequence) {
            awaitAnswer(pending, unloadDeadline);
        }
    }
    private void awaitAnswer(PendingCommit pending, long unloadDeadline) {
        long remaining = Math.max(0, Math.min(pending.deadline(), unloadDeadline) - System.nanoTime());
        try {
            StoreAnswer answer = pending.answer().get(remaining, TimeUnit.NANOSECONDS);
            finishCommit(pending, answer, false);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            finishCommit(pending, StoreAnswer.unknown(failure), true);
        } catch (ExecutionException | TimeoutException failure) {
            finishCommit(pending, StoreAnswer.unknown(failure), true);
        }
    }
    private record PendingCommit(CompletableFuture<CommitOutcome> result, ContainerSnapshot snapshot,
            ContainerState previous, CompletableFuture<StoreAnswer> answer, long deadline) { }
    private void clearAndEnd() {
        contents = UiTestItems.empty(spec.size());
        ledger.clear();
        taken.clear();
        viewers.clear();
        state = ContainerState.ENDED;
        redraw.accept(this);
    }
    private CompletionStage<Void> returnContributions(boolean includeBaseline) {
        Map<UUID, List<ItemStack>> batches = changes().putInBy();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        batches.forEach((player, items) -> pending.add(returns.apply(player, UiTestItems.copies(items))
                .handle((delivery, failure) -> {
                    undelivered(player, failure == null ? delivery.undelivered() : items);
                    return null;
                }).toCompletableFuture()));
        if (includeBaseline) {
            List<ItemStack> remaining = subtract(contents, changes().putIn());
            undelivered(NIL, remaining);
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }
    private void undelivered(UUID player, List<ItemStack> items) {
        if (!items.isEmpty()) {
            spec.undelivered().orElseThrow().undelivered(player, UiTestItems.copies(items));
        }
    }
    private static List<ItemStack> subtract(List<ItemStack> held, List<ItemStack> contributed) {
        List<ItemStack> remaining = new ArrayList<>(UiTestItems.copies(held));
        for (ItemStack item : contributed) {
            int left = count(item);
            for (int index = 0; index < remaining.size() && left > 0; index++) {
                ItemStack current = remaining.get(index);
                if (current.isSimilar(item)) {
                    int removed = Math.min(left, count(current));
                    remaining.set(index, amount(current, count(current) - removed));
                    left -= removed;
                }
            }
        }
        return UiTestItems.merged(remaining);
    }
    private void warn(String message, Throwable failure) {
        LogRecord record = new LogRecord(Level.WARNING, message);
        record.setParameters(new Object[] {owner});
        record.setThrown(failure);
        warnings.accept(record);
    }
    private static final class Contribution {
        private final UUID player;
        private ItemStack item;
        Contribution(UUID player, ItemStack item) { this.player = player; this.item = item; }
    }
    private static final class Changes implements ContainerChanges {
        private final List<ItemStack> put;
        private final List<ItemStack> taken;
        private final Map<UUID, List<ItemStack>> by = new LinkedHashMap<>();
        Changes(List<ItemStack> put, List<ItemStack> taken, Map<UUID, List<ItemStack>> by) {
            this.put = UiTestItems.merged(put);
            this.taken = UiTestItems.merged(taken);
            by.forEach((player, items) -> this.by.put(player, UiTestItems.merged(items)));
        }
        @Override public boolean isEmpty() { return put.isEmpty() && taken.isEmpty(); }
        @Override public List<ItemStack> putIn() { return UiTestItems.copies(put); }
        @Override public List<ItemStack> takenOut() { return UiTestItems.copies(taken); }
        @Override public Map<UUID, List<ItemStack>> putInBy() {
            Map<UUID, List<ItemStack>> result = new LinkedHashMap<>();
            by.forEach((player, items) -> result.put(player, UiTestItems.copies(items)));
            return Collections.unmodifiableMap(result);
        }
    }
}
