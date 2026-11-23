package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.UiBackend.BackendCallbacks;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** One session's screen identity and last backend snapshot. The inventory holds display copies only. */
@ApiStatus.Internal
public final class ChestScreen implements InventoryHolder {
    private final GridSession session;
    private final BackendCallbacks<?> callbacks;
    private final Scheduler scheduler;
    private Inventory inventory;
    private Grid grid;
    private TaskHandle syncTask;
    private boolean closed;

    ChestScreen(GridSession session, BackendCallbacks<?> callbacks, Scheduler scheduler, Grid grid, Component title) {
        this.session = session;
        this.callbacks = callbacks;
        this.scheduler = scheduler;
        this.grid = grid;
        inventory = Bukkit.createInventory(this, grid.rows() * Grid.COLUMNS, title);
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }
    GridSession session() { return session; }
    BackendCallbacks<?> callbacks() { return callbacks; }
    Grid grid() { return grid; }
    void grid(Grid next) { grid = next; }

    boolean showing(InventoryView view) { return inventory.equals(view.getTopInventory()); }

    SlotKind kind(int rawSlot) {
        if (rawSlot < 0) { return SlotKind.OUTSIDE; }
        if (rawSlot >= inventory.getSize()) { return SlotKind.PLAYER_STORAGE; }
        GridSlot slot = GridSlot.ofIndex(rawSlot);
        if (grid.items().containsKey(slot)) { return SlotKind.ICON; }
        SlotBinding binding = grid.bindings().get(slot);
        if (binding == null) { return SlotKind.EMPTY; }
        return binding.mode() == BindingMode.EDITABLE ? SlotKind.BOUND_EDITABLE : SlotKind.BOUND_VIEW;
    }

    void synchronizeClient() {
        if (closed || syncTask != null) { return; }
        syncTask = scheduler.entity(session.viewer()).run(() -> {
            syncTask = null;
            if (session.viewer().isOnline()) { session.viewer().updateInventory(); }
        });
    }

    void close() {
        closed = true;
        if (syncTask != null) { syncTask.cancel(); }
        if (session.viewer().isOnline() && showing(session.viewer().getOpenInventory())) {
            session.viewer().closeInventory();
        }
    }
}
