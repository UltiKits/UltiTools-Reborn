package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Owns all input touching a framework chest, before any module listener runs. */
@ApiStatus.Internal
public final class ChestListener implements Listener {
    private final UiRuntime runtime;
    private final LongSupplier nanoTime;

    public ChestListener(UiRuntime runtime) { this(runtime, System::nanoTime); }

    /** Accepts a monotonic clock so the icon double-click window can be tested without sleeping. */
    public ChestListener(UiRuntime runtime, LongSupplier nanoTime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ChestScreen screen)) { return; }
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (previouslyCancelled || !accepts(screen, event.getView())) { screen.synchronizeClient(); return; }
        ClickDecision decision = ClickDecoder.decode(input(screen, event));
        if (decision instanceof ClickDecision.Allow) {
            event.setCancelled(false);
            screen.reopenLater();
            return;
        }
        dispatch(screen, event.getView(), decision);
        screen.synchronizeClient();
        screen.reopenLater();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ChestScreen screen)) { return; }
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (previouslyCancelled || !accepts(screen, event.getView())) { screen.synchronizeClient(); return; }
        Map<Integer, SlotKind> targets = new LinkedHashMap<>();
        event.getRawSlots().forEach(raw -> targets.put(raw, screen.kind(raw)));
        ClickDecision decision = DragDecoder.decode(event.getType(), targets);
        if (decision instanceof ClickDecision.Allow) {
            event.setCancelled(false);
            screen.reopenLater();
            return;
        }
        if (decision instanceof ClickDecision.Apply apply) { deferDrag(screen, event, apply.gesture()); }
        screen.synchronizeClient();
        screen.reopenLater();
    }

    private void deferDrag(ChestScreen screen, InventoryDragEvent event, Gesture gesture) {
        InventoryView view = event.getView();
        ItemStack oldCursor = event.getOldCursor().clone();
        Grid snapshot = runtime.rendered(screen.session());
        Map<SlotContainer, Map<GridSlot, SlotBinding>> bindings = new IdentityHashMap<>();
        for (int raw : event.getRawSlots()) {
            GridSlot slot = GridSlot.ofIndex(raw);
            SlotBinding binding = snapshot.bindings().get(slot);
            if (binding == null) { return; }
            bindings.computeIfAbsent(binding.container(), ignored -> new LinkedHashMap<>()).put(slot, binding);
        }
        // Paper restores oldCursor after a cancelled drag; spend it only on the following entity tick.
        screen.deferDrag(() -> {
            if (!accepts(screen, view) || !screen.showing(screen.session().viewer().getOpenInventory())) { return; }
            if (!view.getCursor().equals(oldCursor) || !unchanged(runtime.rendered(screen.session()), bindings)) { return; }
            runtime.applyGesture(screen.session(), new PlayerViewerItems(view, (Player) view.getPlayer()), gesture);
        });
    }

    private static boolean unchanged(Grid snapshot, Map<SlotContainer, Map<GridSlot, SlotBinding>> expected) {
        for (Map<GridSlot, SlotBinding> slots : expected.values()) {
            for (GridSlot slot : slots.keySet()) {
                SlotBinding current = snapshot.bindings().get(slot);
                if (current == null) { return false; }
                Map<GridSlot, SlotBinding> original = expected.get(current.container());
                if (original == null || !current.equals(original.get(slot))) { return false; }
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ChestScreen screen
                && accepts(screen, event.getView()) && !screen.reopening()) {
            screen.callbacks().closed(ChestCloseReasons.map(event.getReason()));
        }
    }

    private static boolean accepts(ChestScreen screen, InventoryView view) {
        return screen.session().isOpen() && screen.showing(view)
                && screen.session().viewer().getUniqueId().equals(view.getPlayer().getUniqueId());
    }

    private void dispatch(ChestScreen screen, InventoryView view, ClickDecision decision) {
        if (decision instanceof ClickDecision.Apply apply) {
            runtime.applyGesture(screen.session(),
                    new PlayerViewerItems(view, (Player) view.getPlayer()), apply.gesture());
        } else if (decision instanceof ClickDecision.Report report
                && screen.acceptIconClick(report.slot(), report.kind(), nanoTime.getAsLong())) {
            screen.callbacks().clicked(report.slot(), report.kind());
        }
        // Cancel has no side effects; Allow is handled before dispatch.
    }

    private static ClickInput input(ChestScreen screen, InventoryClickEvent event) {
        SlotKind kind = screen.kind(event.getRawSlot());
        GridSlot slot = GridSlot.ofIndex(kind == SlotKind.OUTSIDE || kind == SlotKind.PLAYER_STORAGE
                ? 0 : event.getRawSlot());
        SlotBinding binding = screen.grid().bindings().get(slot);
        ItemStack contents = binding == null ? ItemStack.empty() : binding.container().get(binding.index());
        ItemStack cursor = event.getView().getCursor();
        CursorState state = cursor.isEmpty() ? CursorState.EMPTY
                : cursor.isSimilar(contents) ? CursorState.SIMILAR : CursorState.DIFFERENT;
        return new ClickInput(event.getAction(), event.getClick(), kind, state,
                contents.isEmpty(), slot, event.getSlot(), event.getHotbarButton());
    }
}
