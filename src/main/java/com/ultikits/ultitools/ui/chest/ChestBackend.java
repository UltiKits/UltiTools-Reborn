package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridItem;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.UiBackend;
import com.ultikits.ultitools.ui.UiIntent;
import com.ultikits.ultitools.ui.UiSession;
import com.ultikits.ultitools.ui.runtime.BoundSlotRenderer;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** The framework's grid-only backend. All screen items are copies, never the source of real item moves. */
@ApiStatus.Internal
public final class ChestBackend implements UiBackend, BoundSlotRenderer {
    private final UiRuntime runtime;
    private final ItemRenderer items;
    private final Messages messages;
    private final Scheduler scheduler;
    private final Map<UiSession<?>, ChestScreen> screens = new IdentityHashMap<>();

    public ChestBackend(UiRuntime runtime, ItemRenderer items, Messages messages, Scheduler scheduler) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.items = Objects.requireNonNull(items, "items");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override public Key id() { return Key.key("ultitools", "chest"); }
    @Override public int priority() { return 0; }
    @Override public boolean supports(UiIntent<?> intent, Player viewer) { return intent instanceof Grid; }

    @Override public <R> void open(UiSession<R> session, BackendCallbacks<R> callbacks) {
        if (!(session instanceof GridSession gridSession)) {
            throw new IllegalArgumentException("The chest backend only supports grid sessions");
        }
        Grid grid = runtime.rendered(gridSession);
        ChestScreen screen = new ChestScreen(gridSession, callbacks, scheduler, grid,
                messages.render(grid.title(), session.viewer()));
        screens.put(session, screen);
        render(screen, grid);
        screen.open();
    }

    @Override public void update(GridSession session, Grid previous, Grid next) {
        ChestScreen screen = screens.get(session);
        if (screen == null) { return; }
        screen.grid(next);
        render(screen, next);
        if (!previous.title().equals(next.title())) {
            screen.changeTitle(messages.render(next.title(), session.viewer()));
        }
    }

    @Override public void redraw(GridSession session, Map<GridSlot, ItemStack> picture) {
        ChestScreen screen = screens.get(session);
        if (screen == null || !session.isOpen()) { return; }
        screen.grid().bindings().forEach((slot, binding) -> put(screen.getInventory(), slot,
                binding.container().state() == ContainerState.ENDED
                        ? ItemStack.empty() : picture.getOrDefault(slot, ItemStack.empty())));
    }

    @Override public void close(UiSession<?> session) {
        ChestScreen screen = screens.remove(session);
        if (screen != null) { screen.close(); }
    }

    private void render(ChestScreen screen, Grid grid) {
        for (int index = 0; index < grid.rows() * Grid.COLUMNS; index++) {
            GridSlot slot = GridSlot.ofIndex(index);
            put(screen.getInventory(), slot, item(grid, slot, screen.session().viewer()));
        }
    }

    private ItemStack item(Grid grid, GridSlot slot, Player viewer) {
        GridItem icon = grid.items().get(slot);
        if (icon != null) { return items.render(icon.icon(), viewer); }
        SlotBinding binding = grid.bindings().get(slot);
        if (binding == null || binding.container().state() == ContainerState.ENDED) { return ItemStack.empty(); }
        return binding.container().get(binding.index());
    }

    private static void put(Inventory inventory, GridSlot slot, ItemStack item) {
        ItemStack current = inventory.getItem(slot.index());
        ItemStack normalized = current == null || current.isEmpty() ? ItemStack.empty() : current;
        if (!normalized.equals(item)) { inventory.setItem(slot.index(), item.clone()); }
    }
}
