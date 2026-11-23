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
    private final Runnable redraw;
    private final Set<UiSession<?>> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Contribution> ledger = new ArrayList<>();
    private final List<ItemStack> taken = new ArrayList<>();
    private List<ItemStack> contents;
    private List<ItemStack> baseline;
    private ContainerState state = ContainerState.EDITABLE;
    private CompletionStage<CommitOutcome> inFlight;
    private CompletableFuture<CommitOutcome> commitFuture;
    private CompletionStage<EndOutcome> ending;
    private long sequence;
    private long answeredSequence;

    InMemorySlotContainer(String owner, ContainerSpec spec, RecordingScheduler scheduler,
            BiFunction<UUID, List<ItemStack>, CompletionStage<Delivery>> returns,
            Consumer<LogRecord> warnings, Runnable redraw) {
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
    @Override public String owner() { checkThread(); return owner; }
    @Override public int size() { checkThread(); return spec.size(); }
    @Override public ContainerKind kind() { checkThread(); return spec.kind(); }
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
        redraw.run();
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
            throw new IllegalStateException("Cannot show an ended container");
        }
        viewers.add(session);
    }
    void detach(UiSession<?> session, boolean closed) {
        checkThread();
        boolean removed = viewers.remove(session);
        if (removed && closed && viewers.isEmpty() && spec.endWhenUnviewed()) {
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
    void changed(ContainerChange change) {
        checkThread();
        redraw.run();
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
        for (Contribution entry : ledger) {
            if (entry.player.equals(player) == own && entry.item.isSimilar(item)) {
                int removed = Math.min(remaining, count(entry.item));
                entry.item = amount(entry.item, count(entry.item) - removed);
                remaining -= removed;
            }
        }
        return remaining;
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
        commitFuture = new CompletableFuture<>();
        inFlight = commitFuture.minimalCompletionStage();
        CompletableFuture<CommitOutcome> expected = commitFuture;
        scheduler.global().runLater(spec.commitTimeout(), () -> finishCommit(expected, snapshot, previous,
                StoreAnswer.unknown(null), true));
        try {
            store.write(snapshot).whenComplete((answer, failure) -> onThread(() -> finishCommit(expected,
                    snapshot, previous, failure == null && answer != null ? answer : StoreAnswer.unknown(failure), false)));
        } catch (Exception thrown) {
            finishCommit(expected, snapshot, previous, StoreAnswer.unknown(thrown), false);
        }
        return inFlight;
    }
    private void onThread(Runnable action) {
        if (Thread.currentThread() == thread) { // NOPMD - marshal only a foreign completing thread
            action.run();
        } else {
            scheduler.global().run(action);
        }
    }
    private void finishCommit(CompletableFuture<CommitOutcome> expected, ContainerSnapshot snapshot,
            ContainerState previous, StoreAnswer answer, boolean timeout) {
        checkThread();
        if (snapshot.sequence() <= answeredSequence) {
            if (!timeout) {
                warn("Late store answer ignored for owner {0}", null);
            }
            return;
        }
        answeredSequence = snapshot.sequence();
        if (answer instanceof StoreAnswer.Written) {
            baseline = new ArrayList<>(snapshot.contents());
            ledger.clear();
            taken.clear();
            state = previous;
            expected.complete(CommitOutcome.WRITTEN);
        } else if (answer instanceof StoreAnswer.Refused refused) {
            finishRefused(expected, refused, previous);
        } else {
            state = ContainerState.FAILED;
            expected.complete(CommitOutcome.UNKNOWN);
        }
    }
    private void finishRefused(CompletableFuture<CommitOutcome> expected, StoreAnswer.Refused answer, ContainerState previous) {
        List<ItemStack> current = checkedContents(answer.current());
        CompletionStage<Void> refunded = returnContributions(false);
        contents = new ArrayList<>(current);
        baseline = new ArrayList<>(UiTestItems.copies(current));
        redraw.run();
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
        commit().thenAccept(outcome -> {
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
    void unload() {
        checkThread();
        end();
        if (state == ContainerState.COMMITTING && commitFuture != null && !commitFuture.isDone()) {
            if (answeredSequence < sequence) {
                answeredSequence = sequence;
                state = ContainerState.FAILED;
                commitFuture.complete(CommitOutcome.UNKNOWN);
            } else {
                clearAndEnd();
            }
        }
    }
    private void clearAndEnd() {
        contents = UiTestItems.empty(spec.size());
        ledger.clear();
        taken.clear();
        viewers.clear();
        state = ContainerState.ENDED;
        redraw.run();
    }
    private CompletionStage<Void> returnContributions(boolean includeBaseline) {
        Map<UUID, List<ItemStack>> batches = changes().putInBy();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        batches.forEach((player, items) -> pending.add(returns.apply(player, UiTestItems.copies(items))
                .thenAccept(delivery -> undelivered(player, delivery.undelivered())).toCompletableFuture()));
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
