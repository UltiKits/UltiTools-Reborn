package com.ultikits.ultitools.ui;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** How to create a container. Immutable. */
public final class ContainerSpec {
    /** Maximum number of container indexes. */
    public static final int MAX_SIZE = 4096;
    private final ContainerKind kind;
    private final int size;
    private final TransferPolicy policy;
    private final @Nullable Disposition disposition;
    private final @Nullable ContainerStore store;
    private final Duration commitTimeout;
    private final @Nullable UndeliveredHandler undelivered;
    private final boolean endWhenUnviewed;
    private final Consumer<ContainerChange> onChange;
    private final @Nullable UUID contributor;
    private final List<ItemStack> initial;
    private final List<ItemStack> baseline;

    private ContainerSpec(Builder builder) {
        kind = builder.kind;
        size = builder.size;
        policy = builder.policy;
        disposition = builder.disposition;
        store = builder.store;
        commitTimeout = builder.commitTimeout;
        undelivered = builder.undelivered;
        endWhenUnviewed = builder.endWhenUnviewed;
        onChange = builder.onChange;
        contributor = builder.contributor;
        initial = copyItems(builder.initial, "initial");
        baseline = copyItems(builder.baseline, "baseline");
    }

    /** Real items. A disposition is required. @throws IllegalArgumentException outside 1..{@value #MAX_SIZE} */
    public static Builder storage(int size) { return new Builder(ContainerKind.STORAGE, size); }
    /** Copies (editors of definitions): put-in copies, take-out deletes the copy; no disposition, store or handler. */
    public static Builder template(int size) { return new Builder(ContainerKind.TEMPLATE, size); }
    /** Whether items are real or template copies. */
    public ContainerKind kind() { return kind; }
    /** The number of indexes. */
    public int size() { return size; }
    /** The transfer policy. */
    public TransferPolicy policy() { return policy; }
    /** The storage disposition; absent for templates. */
    public Optional<Disposition> disposition() { return Optional.ofNullable(disposition); }
    /** The optional store. */
    public Optional<ContainerStore> store() { return Optional.ofNullable(store); }
    /** Maximum time to wait for a store answer. */
    public Duration commitTimeout() { return commitTimeout; }
    /** The storage handler; absent for templates. */
    public Optional<UndeliveredHandler> undelivered() { return Optional.ofNullable(undelivered); }
    /** Whether the last screen's close ends the container. */
    public boolean endWhenUnviewed() { return endWhenUnviewed; }
    /** Listener invoked after each applied transfer. */
    public Consumer<ContainerChange> onChange() { return onChange; }
    /** Contributor of {@link #initial()}, absent when baseline contents were selected. */
    public Optional<UUID> contributor() { return Optional.ofNullable(contributor); }
    /** Contributed initial contents; copies, empty stacks when a baseline was selected. */
    public List<ItemStack> initial() { return copyItems(initial, "initial"); }
    /** Baseline contents; copies, empty stacks when contributed initial contents were selected. */
    public List<ItemStack> baseline() { return copyItems(baseline, "baseline"); }

    static ItemStack copyItem(ItemStack item, String name) {
        Objects.requireNonNull(item, name);
        return item.isEmpty() ? ItemStack.empty() : item.clone();
    }

    static List<ItemStack> copyItems(List<ItemStack> items, String name) {
        Objects.requireNonNull(items, name);
        return items.stream().map(item -> copyItem(item, name)).toList();
    }

    /** Reusable builder. The last initial/baseline selection wins. */
    public static final class Builder {
        private final ContainerKind kind;
        private final int size;
        private TransferPolicy policy = TransferPolicy.allowAll();
        private @Nullable Disposition disposition;
        private @Nullable ContainerStore store;
        private Duration commitTimeout = Duration.ofSeconds(30);
        private @Nullable UndeliveredHandler undelivered;
        private boolean endWhenUnviewed = true;
        private Consumer<ContainerChange> onChange = ignored -> { /* No listener by default. */ };
        private @Nullable UUID contributor;
        private List<ItemStack> initial;
        private List<ItemStack> baseline;

        private Builder(ContainerKind kind, int size) {
            if (size < 1 || size > MAX_SIZE) {
                throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE);
            }
            this.kind = kind;
            this.size = size;
            initial = emptyContents();
            baseline = emptyContents();
        }

        private List<ItemStack> emptyContents() { return Collections.nCopies(size, ItemStack.empty()); }
        /** Default {@link TransferPolicy#allowAll()}. */
        public Builder policy(TransferPolicy policy) { this.policy = Objects.requireNonNull(policy, "policy"); return this; }
        /** Required for storage; rejected for template. */
        public Builder disposition(Disposition disposition) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            return this;
        }
        /** Required when the disposition is COMMIT, optional otherwise (enables {@link SlotContainer#commit()}). */
        public Builder store(ContainerStore store) { this.store = Objects.requireNonNull(store, "store"); return this; }
        /** Default 30 s; positive, at most 10 minutes. A store answer later than this counts as Unknown. */
        public Builder commitTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("timeout must be positive and at most 10 minutes");
            }
            commitTimeout = timeout;
            return this;
        }
        /** Required for storage (items owed to a player who cannot receive them). */
        public Builder undelivered(UndeliveredHandler handler) {
            undelivered = Objects.requireNonNull(handler, "handler");
            return this;
        }
        /**
         * Default true: the container ends as soon as no session shows it any more, whatever the reason: the last
         * showing session closes (in that session's close step, after its screen closes and before its result
         * completes), or {@link GridSession#update} gives every showing session a snapshot that no longer binds it.
         * Showing another grid that binds the same container hands it over without ending it; paging within the same
         * container never ends it. Opening any intent that does not bind it (a Confirm, a Form, another grid) for its
         * last viewer ends it, so a module that opens sub-screens over a container and comes back builds it with
         * {@code endWhenUnviewed(false)} and calls {@link SlotContainer#end()} or {@link SlotContainer#drain()} itself.
         * A container that was never shown does not end this way.
         */
        public Builder endWhenUnviewed(boolean end) { endWhenUnviewed = end; return this; }
        /** Called after each applied transfer, on the container's thread; exceptions are logged and swallowed. */
        public Builder onChange(Consumer<ContainerChange> listener) {
            onChange = Objects.requireNonNull(listener, "listener");
            return this;
        }
        /** Initial contents, counted as put in by {@code contributor} (the RETURN disposition gives them back to them). */
        public Builder initial(UUID contributor, List<ItemStack> contents) {
            Objects.requireNonNull(contributor, "contributor");
            List<ItemStack> copy = sizedCopy(contents);
            this.contributor = contributor;
            initial = copy;
            baseline = emptyContents();
            return this;
        }
        /** Initial contents that are the baseline (for example read from storage); not owed to anyone. */
        public Builder baseline(List<ItemStack> contents) {
            List<ItemStack> copy = sizedCopy(contents);
            contributor = null;
            initial = emptyContents();
            baseline = copy;
            return this;
        }
        private List<ItemStack> sizedCopy(List<ItemStack> contents) {
            List<ItemStack> copy = copyItems(contents, "contents");
            if (copy.size() != size) {
                throw new IllegalArgumentException("contents must have container size");
            }
            return copy;
        }
        private void validate() {
            if (kind == ContainerKind.TEMPLATE) {
                if (disposition != null || store != null || undelivered != null) {
                    throw new IllegalArgumentException("templates have no disposition, store or undelivered handler");
                }
                return;
            }
            if (disposition == null || undelivered == null) {
                throw new IllegalArgumentException("storage requires a disposition and undelivered handler");
            }
            if (disposition == Disposition.COMMIT && store == null) {
                throw new IllegalArgumentException("COMMIT requires a store");
            }
        }
        /** @throws IllegalArgumentException on a missing required or a forbidden attribute (see each method) */
        public ContainerSpec build() { validate(); return new ContainerSpec(this); }
    }
}
