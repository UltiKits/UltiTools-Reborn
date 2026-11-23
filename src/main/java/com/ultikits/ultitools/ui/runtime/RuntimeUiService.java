package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.ui.Ack;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.Delivery;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.ShowOptions;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiResult;
import com.ultikits.ultitools.ui.UiService;
import com.ultikits.ultitools.ui.UiSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.LogRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One module copy's view of the shared session registry and item-delivery lifecycle. */
@ApiStatus.Internal
final class RuntimeUiService implements UiService, ContainerHost {
    final UiRuntime runtime;
    final UiOwner ownerIdentity;
    boolean unloaded;
    boolean unloading;
    @Nullable Thread unloadingThread;
    private final List<PendingDelivery> pendingDeliveries = new ArrayList<>();

    RuntimeUiService(UiRuntime runtime, UiOwner owner, boolean unloaded) {
        this.runtime = runtime;
        ownerIdentity = owner;
        this.unloaded = unloaded;
    }
    @Override public String owner() { return ownerIdentity.name(); }
    @Override public <R> UiSession<R> show(Player viewer, UiIntent<R> intent) {
        return show(viewer, intent, ShowOptions.defaults());
    }
    @Override public GridSession show(Player viewer, Grid grid) { return show(viewer, grid, ShowOptions.defaults()); }
    @Override public GridSession show(Player viewer, Grid grid, ShowOptions options) {
        return (GridSession) show(viewer, (UiIntent<Ack>) Objects.requireNonNull(grid, "grid"), options);
    }
    @Override public <R> UiSession<R> show(Player viewer, UiIntent<R> intent, ShowOptions options) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(options, "options");
        if (intent instanceof Grid grid && runtime.isServerThread()) { RuntimeGridSession.validateContainers(grid); }
        RuntimeSession<R> session = newSession(viewer, intent);
        if (runtime.add(session)) {
            session.choose(new UiResult.Closed<>(CloseReason.OWNER_UNLOADED));
            session.closeAndComplete();
        } else if (!viewer.isOnline()) {
            session.requestClose(CloseReason.DISCONNECTED);
        } else {
            session.dispatch(() -> session.openTask(options));
        }
        return session;
    }
    @SuppressWarnings("unchecked") // The sealed Grid intent always has Ack as its answer type.
    private <R> RuntimeSession<R> newSession(Player viewer, UiIntent<R> intent) {
        if (intent instanceof Grid grid) { return (RuntimeSession<R>) new RuntimeGridSession(this, viewer, grid); }
        return new RuntimeSession<>(this, viewer, intent);
    }
    @Override public SlotContainer container(ContainerSpec spec) {
        Objects.requireNonNull(spec, "spec");
        runtime.checkThread();
        synchronized (runtime.lock) {
            if (unloaded || unloading) { throw new IllegalStateException("Owner unloaded: " + owner()); }
        }
        return runtime.containers.create(ownerIdentity, spec, this);
    }

    @Override public CompletionStage<Delivery> deliver(Player player, List<ItemStack> items) {
        Objects.requireNonNull(player, "player");
        List<ItemStack> copies = copyItems(items);
        PendingDelivery delivery = new PendingDelivery(player, copies);
        boolean immediate;
        boolean reject;
        synchronized (runtime.lock) {
            immediate = unloading && Thread.currentThread().equals(unloadingThread);
            reject = !immediate && (unloading || unloaded);
            if (!immediate && !reject) { pendingDeliveries.add(delivery); }
        }
        if (reject) {
            delivery.result.complete(undelivered(copies));
        } else if (immediate) {
            delivery.run();
        } else {
            delivery.lane.execute(delivery);
        }
        return delivery.result.minimalCompletionStage();
    }

    void flushDeliveries() {
        List<PendingDelivery> pending;
        synchronized (runtime.lock) { pending = List.copyOf(pendingDeliveries); }
        pending.forEach(PendingDelivery::run);
    }

    @Override public CompletionStage<Delivery> returnTo(UUID contributor, List<ItemStack> items) {
        Objects.requireNonNull(contributor, "contributor");
        Player player = Bukkit.getPlayer(contributor);
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(undelivered(copyItems(items))).minimalCompletionStage();
        }
        return deliver(player, items);
    }
    @Override public void changed(SlotContainer container) { runtime.redraw(Objects.requireNonNull(container, "container")); }
    @Override public void warn(LogRecord record) { runtime.log(Objects.requireNonNull(record, "record")); }
    @Override public Scheduler scheduler() { return runtime.scheduler; }

    private static List<ItemStack> copyItems(List<ItemStack> items) {
        Objects.requireNonNull(items, "items");
        return items.stream().map(item -> Objects.requireNonNull(item, "item").clone()).toList();
    }
    private static Delivery undelivered(List<ItemStack> items) { return new Delivery(List.of(), List.of(), items); }
    private static ItemStack amount(ItemStack item, int count) {
        if (count <= 0) { return ItemStack.empty(); }
        ItemStack copy = item.clone();
        copy.setAmount(count);
        return copy;
    }

    private final class PendingDelivery implements Runnable {
        private final Player player;
        private final List<ItemStack> items;
        private final ViewerLane lane;
        private final CompletableFuture<Delivery> result = new CompletableFuture<>();
        private boolean started;

        PendingDelivery(Player player, List<ItemStack> items) {
            this.player = player;
            this.items = items;
            lane = runtime.lane(player);
        }
        @Override public void run() {
            synchronized (runtime.lock) {
                if (started) { return; }
                started = true;
                pendingDeliveries.remove(this);
            }
            DeliveryWork work = new DeliveryWork(player, items);
            try {
                if (player.isOnline() && !lane.isRetired()) { work.give(); }
            } catch (RuntimeException failure) {
                runtime.warn(owner(), "Item delivery failed", failure);
            }
            result.complete(work.answer());
        }
    }

    /** Records each successful write before attempting another, so a failure reports only the remaining items. */
    private static final class DeliveryWork {
        private final Player player;
        private final List<ItemStack> items;
        private final List<ItemStack> given;
        private final List<ItemStack> dropped;
        private final List<ItemStack> remaining;

        DeliveryWork(Player player, List<ItemStack> items) {
            this.player = player;
            this.items = items;
            given = new ArrayList<>(items.stream().map(ignored -> ItemStack.empty()).toList());
            dropped = new ArrayList<>(given);
            remaining = new ArrayList<>(items);
        }
        void give() {
            PlayerInventory inventory = player.getInventory();
            ItemStack[] storage = inventory.getStorageContents();
            for (int index = 0; index < items.size(); index++) {
                insert(inventory, storage, index, false);
                insert(inventory, storage, index, true);
                drop(index);
            }
        }
        private void insert(PlayerInventory inventory, ItemStack[] storage, int source, boolean empty) {
            ItemStack original = items.get(source);
            if (original.isEmpty()) { return; }
            for (int slot = 0; slot < storage.length && !remaining.get(source).isEmpty(); slot++) {
                ItemStack previous = storage[slot] == null ? ItemStack.empty() : storage[slot];
                if (previous.isEmpty() != empty) { continue; }
                int moved = Math.min(remaining.get(source).getAmount(), room(previous, original));
                if (moved <= 0) { continue; }
                int base = previous.isEmpty() ? 0 : previous.getAmount();
                ItemStack next = amount(original, base + moved);
                inventory.setItem(slot, next.clone());
                storage[slot] = next;
                int rest = remaining.get(source).getAmount() - moved;
                remaining.set(source, amount(original, rest));
                given.set(source, amount(original, original.getAmount() - rest));
            }
        }
        private static int room(ItemStack previous, ItemStack original) {
            if (previous.isEmpty()) { return original.getMaxStackSize(); }
            return previous.isSimilar(original) ? Math.max(0, previous.getMaxStackSize() - previous.getAmount()) : 0;
        }
        private void drop(int index) {
            ItemStack rest = remaining.get(index);
            if (!rest.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest.clone());
                dropped.set(index, rest);
                remaining.set(index, ItemStack.empty());
            }
        }
        Delivery answer() { return new Delivery(nonempty(given), nonempty(dropped), nonempty(remaining)); }
        private static List<ItemStack> nonempty(List<ItemStack> items) {
            return items.stream().filter(item -> !item.isEmpty()).toList();
        }
    }
}
