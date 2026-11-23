package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    public ChestListener(UiRuntime runtime) { this.runtime = Objects.requireNonNull(runtime, "runtime"); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ChestScreen screen)) { return; }
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!accepts(screen, event.getView())) { screen.synchronizeClient(); return; }
        ClickDecision decision = ClickDecoder.decode(input(screen, event));
        if (decision instanceof ClickDecision.Allow) {
            event.setCancelled(previouslyCancelled);
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
        if (!accepts(screen, event.getView())) { screen.synchronizeClient(); return; }
        Map<Integer, SlotKind> targets = new LinkedHashMap<>();
        event.getRawSlots().forEach(raw -> targets.put(raw, screen.kind(raw)));
        ClickDecision decision = DragDecoder.decode(event.getType(), targets);
        if (decision instanceof ClickDecision.Allow) {
            event.setCancelled(previouslyCancelled);
            screen.reopenLater();
            return;
        }
        dispatch(screen, event.getView(), decision);
        screen.synchronizeClient();
        screen.reopenLater();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ChestScreen screen) {
            if (!screen.reopening()) { screen.callbacks().closed(ChestCloseReasons.map(event.getReason())); }
        }
    }

    private static boolean accepts(ChestScreen screen, InventoryView view) {
        return screen.session().isOpen() && screen.showing(view)
                && screen.session().viewer().getUniqueId().equals(view.getPlayer().getUniqueId());
    }

    private void dispatch(ChestScreen screen, InventoryView view, ClickDecision decision) {
        switch (decision) {
            case ClickDecision.Apply apply -> runtime.applyGesture(screen.session(),
                    new PlayerViewerItems(view, (Player) view.getPlayer()), apply.gesture());
            case ClickDecision.Report report -> screen.callbacks().clicked(report.slot(), report.kind());
            default -> { /* Cancel has no side effects; Allow is handled before dispatch. */ }
        }
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
