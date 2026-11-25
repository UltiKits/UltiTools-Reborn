package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.ClickKind;
import com.ultikits.ultitools.ui.CloseReason;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.UiBackend.BackendCallbacks;
import com.ultikits.ultitools.ui.runtime.PlayerInventoryPreparation;
import java.util.HashMap;
import java.util.Map;
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
    private static final long DOUBLE_CLICK_NANOS = 250_000_000L;
    private final GridSession session;
    private final BackendCallbacks<?> callbacks;
    private final Scheduler scheduler;
    private final Map<GridSlot, Long> leftClicks = new HashMap<>();
    private final Map<Object, TaskHandle> drags = new HashMap<>();
    private Inventory inventory;
    private Grid grid;
    private TaskHandle syncTask;
    private TaskHandle reopenTask;
    private Component title;
    private Component pendingTitle;
    private boolean reopening;
    private boolean closed;

    ChestScreen(GridSession session, BackendCallbacks<?> callbacks, Scheduler scheduler, Grid grid, Component title) {
        this.session = session;
        this.callbacks = callbacks;
        this.scheduler = scheduler;
        this.grid = grid;
        this.title = title;
        inventory = Bukkit.createInventory(this, grid.rows() * Grid.COLUMNS, title);
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }
    GridSession session() { return session; }
    BackendCallbacks<?> callbacks() { return callbacks; }
    Grid grid() { return grid; }
    void grid(Grid next) { grid = next; }

    boolean showing(InventoryView view) { return inventory.equals(view.getTopInventory()); }

    void open() { openInventory(inventory); }

    private boolean openInventory(Inventory next) {
        try {
            if (!session.viewer().isOnline() || session.viewer().isDead()) {
                callbacks.closed(CloseReason.INTERRUPTED);
                return false;
            }
            PlayerInventoryPreparation.beforeShow(session.viewer());
            InventoryView opened = session.viewer().openInventory(next);
            if (opened == null || !session.viewer().isOnline() || session.viewer().isDead()
                    || !next.equals(session.viewer().getOpenInventory().getTopInventory())) {
                callbacks.closed(CloseReason.INTERRUPTED);
                return false;
            }
            return true;
        } catch (Throwable failure) {
            callbacks.closed(CloseReason.INTERRUPTED);
            throw failure;
        }
    }

    SlotKind kind(int rawSlot) {
        if (rawSlot < 0) { return SlotKind.OUTSIDE; }
        if (rawSlot >= inventory.getSize()) { return SlotKind.PLAYER_STORAGE; }
        GridSlot slot = GridSlot.ofIndex(rawSlot);
        if (grid.items().containsKey(slot)) { return SlotKind.ICON; }
        SlotBinding binding = grid.bindings().get(slot);
        if (binding == null) { return SlotKind.EMPTY; }
        return binding.mode() == BindingMode.EDITABLE ? SlotKind.BOUND_EDITABLE : SlotKind.BOUND_VIEW;
    }

    boolean acceptIconClick(GridSlot slot, ClickKind kind, long now) {
        if (kind != ClickKind.LEFT) { return true; }
        Long previous = leftClicks.get(slot);
        if (previous != null && now - previous < DOUBLE_CLICK_NANOS) { return false; }
        leftClicks.put(slot, now);
        return true;
    }

    void deferDrag(Runnable gesture) {
        if (closed) { return; }
        Object token = new Object();
        TaskHandle handle = scheduler.entity(session.viewer()).run(() -> {
            drags.remove(token);
            if (!closed && session.isOpen() && session.viewer().isOnline()) { gesture.run(); }
        });
        drags.put(token, handle);
    }

    void synchronizeClient() {
        if (closed || syncTask != null) { return; }
        syncTask = scheduler.entity(session.viewer()).run(() -> {
            syncTask = null;
            if (session.viewer().isOnline()) { session.viewer().updateInventory(); }
        });
    }

    boolean reopening() { return reopening; }

    void changeTitle(Component next) {
        pendingTitle = title.equals(next) ? null : next;
        reopenLater();
    }

    void reopenLater() {
        if (closed || pendingTitle == null || reopenTask != null) { return; }
        reopenTask = scheduler.entity(session.viewer()).run(() -> {
            reopenTask = null;
            if (pendingTitle == null || !session.isOpen()) { return; }
            if (!session.viewer().isOnline() || session.viewer().isDead()) {
                pendingTitle = null;
                callbacks.closed(CloseReason.INTERRUPTED);
                return;
            }
            if (!showing(session.viewer().getOpenInventory()) || !session.viewer().getItemOnCursor().isEmpty()) { return; }
            reopen();
        });
    }

    private void reopen() {
        Component nextTitle = pendingTitle;
        pendingTitle = null;
        reopening = true;
        try {
            Inventory next = Bukkit.createInventory(this, grid.rows() * Grid.COLUMNS, nextTitle);
            next.setContents(inventory.getContents());
            if (openInventory(next)) {
                inventory = next;
                title = nextTitle;
            }
        } catch (Throwable failure) {
            callbacks.closed(CloseReason.INTERRUPTED);
            throw failure;
        } finally { reopening = false; }
    }

    void close() {
        closed = true;
        if (syncTask != null) { syncTask.cancel(); }
        if (reopenTask != null) { reopenTask.cancel(); }
        drags.values().forEach(TaskHandle::cancel);
        drags.clear();
        leftClicks.clear();
        if (session.viewer().isOnline() && showing(session.viewer().getOpenInventory())) {
            session.viewer().closeInventory();
        }
    }
}
