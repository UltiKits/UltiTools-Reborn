package com.ultikits.ultitools.ui;

import static com.ultikits.ultitools.ui.UiTestItems.amount;
import static com.ultikits.ultitools.ui.UiTestItems.count;
import static com.ultikits.ultitools.ui.UiTestItems.room;

import com.ultikits.ultitools.text.Text;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Reference arithmetic: decide and apply each transfer, then publish to listeners and viewers. */
final class InMemoryGestureEngine implements GestureEngine {
    private final BiConsumer<Player, Text> messages;
    InMemoryGestureEngine(BiConsumer<Player, Text> messages) { this.messages = messages; }

    @Override public boolean apply(Grid grid, Player viewer, ViewerItems side, Gesture gesture) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(gesture, "gesture");
        Plan plan = new Plan(viewer, side);
        switch (gesture) {
            case Gesture.Pickup pickup -> pickup(grid, plan, pickup);
            case Gesture.Place place -> place(grid, plan, place);
            case Gesture.SwapWithCursor swap -> swapCursor(grid, plan, swap);
            case Gesture.HotbarSwap swap -> hotbar(grid, plan, swap);
            case Gesture.QuickMoveOut move -> quickOut(grid, plan, move);
            case Gesture.QuickMoveIn move -> quickIn(grid, plan, move);
            case Gesture.Spread spread -> spread(grid, plan, spread);
        }
        return plan.apply(side);
    }
    private static SlotBinding editable(Grid grid, GridSlot slot) {
        SlotBinding binding = grid.binding(slot).orElse(null);
        if (binding == null || binding.mode() != BindingMode.EDITABLE) {
            return null;
        }
        return binding.container().state() == ContainerState.EDITABLE ? binding : null;
    }
    private static InMemorySlotContainer container(SlotBinding binding) {
        return (InMemorySlotContainer) binding.container();
    }
    private int allowed(Plan plan, SlotBinding binding, TransferKind kind, ItemStack item, int requested) {
        if (requested <= 0) {
            return 0;
        }
        TransferDecision decision = container(binding).decide(plan.viewer, kind, binding.index(), item, requested);
        decision.message().filter(plan.sent::add).ifPresent(message -> messages.accept(plan.viewer, message));
        return decision.allowedAmount(requested);
    }
    private void pickup(Grid grid, Plan plan, Gesture.Pickup gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || !plan.side.cursor().isEmpty()) {
            return;
        }
        ItemStack item = plan.item(binding);
        if (item.isEmpty()) {
            return;
        }
        if (binding.container().kind() == ContainerKind.TEMPLATE) {
            int moved = allowed(plan, binding, TransferKind.TAKE_OUT, item, count(item));
            plan.remove(binding, item, moved);
            return;
        }
        int requested = gesture.half() ? (count(item) + 1) / 2 : count(item);
        int moved = allowed(plan, binding, TransferKind.TAKE_OUT, item, requested);
        plan.remove(binding, item, moved);
        if (moved > 0) {
            plan.side.cursor(amount(item, moved));
        }
    }
    private void place(Grid grid, Plan plan, Gesture.Place gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null) {
            return;
        }
        put(plan, binding, plan.side.cursor(), gesture.one() ? 1 : count(plan.side.cursor()), true, -1);
    }
    private int put(Plan plan, SlotBinding binding, ItemStack source, int requested, boolean cursor, int slot) {
        ItemStack target = plan.item(binding);
        int moved = allowed(plan, binding, TransferKind.PUT_IN, source,
                Math.min(requested, Math.min(count(source), room(target, source))));
        plan.add(binding, source, moved);
        if (moved > 0 && binding.container().kind() == ContainerKind.STORAGE) {
            ItemStack rest = amount(source, count(source) - moved);
            if (cursor) {
                plan.side.cursor(rest);
            } else {
                plan.side.slot(slot, rest);
            }
        }
        return moved;
    }
    private void swapCursor(Grid grid, Plan plan, Gesture.SwapWithCursor gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || plan.side.cursor().isEmpty()) {
            return;
        }
        exchange(plan, binding, plan.side.cursor(), true, -1);
    }
    private void hotbar(Grid grid, Plan plan, Gesture.HotbarSwap gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || gesture.hotbar() >= plan.side.inventorySize()) {
            return;
        }
        ItemStack hotbar = plan.side.slot(gesture.hotbar());
        ItemStack held = plan.item(binding);
        if (binding.container().kind() == ContainerKind.TEMPLATE) {
            exchange(plan, binding, hotbar, false, gesture.hotbar());
        } else if (held.isEmpty()) {
            put(plan, binding, hotbar, count(hotbar), false, gesture.hotbar());
        } else if (hotbar.isEmpty()) {
            int moved = allowed(plan, binding, TransferKind.TAKE_OUT, held, count(held));
            plan.remove(binding, held, moved);
            if (moved > 0) {
                plan.side.slot(gesture.hotbar(), amount(held, moved));
            }
        } else {
            exchange(plan, binding, hotbar, false, gesture.hotbar());
        }
    }
    private void exchange(Plan plan, SlotBinding binding, ItemStack source, boolean cursor, int slot) {
        ItemStack held = plan.item(binding);
        int take = allowed(plan, binding, TransferKind.TAKE_OUT, held, count(held));
        int put = allowed(plan, binding, TransferKind.PUT_IN, source, count(source));
        if (take != count(held) || put != count(source)) {
            return;
        }
        plan.remove(binding, held, take);
        plan.add(binding, source, put);
        if (binding.container().kind() == ContainerKind.STORAGE) {
            if (cursor) {
                plan.side.cursor(held);
            } else {
                plan.side.slot(slot, held);
            }
        }
    }
    private void quickOut(Grid grid, Plan plan, Gesture.QuickMoveOut gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null) {
            return;
        }
        ItemStack held = plan.item(binding);
        int fits = binding.container().kind() == ContainerKind.TEMPLATE ? count(held) : UiTestItems.fit(plan.side, held);
        int moved = allowed(plan, binding, TransferKind.TAKE_OUT, held, fits);
        plan.remove(binding, held, moved);
        if (binding.container().kind() == ContainerKind.STORAGE) {
            UiTestItems.insert(plan.side, held, moved);
        }
    }
    private void quickIn(Grid grid, Plan plan, Gesture.QuickMoveIn gesture) {
        if (gesture.playerSlot() < 0 || gesture.playerSlot() >= plan.side.inventorySize()) {
            return;
        }
        ItemStack source = plan.side.slot(gesture.playerSlot());
        List<SlotBinding> targets = grid.bindings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(GridSlot::index)))
                .map(entry -> editable(grid, entry.getKey())).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(SlotBinding::index)).toList();
        int remaining = quickInPass(plan, targets, source, gesture.playerSlot(), false, count(source));
        quickInPass(plan, targets, source, gesture.playerSlot(), true, remaining);
    }
    private int quickInPass(Plan plan, List<SlotBinding> targets, ItemStack original, int sourceSlot, boolean empty, int remaining) {
        int left = remaining;
        for (SlotBinding binding : targets) {
            if (left == 0) { break; }
            if (plan.item(binding).isEmpty() == empty) {
                ItemStack source = binding.container().kind() == ContainerKind.TEMPLATE ? amount(original, left) : plan.side.slot(sourceSlot);
                left -= put(plan, binding, source, count(source), false, sourceSlot);
            }
        }
        return left;
    }
    private void spread(Grid grid, Plan plan, Gesture.Spread gesture) {
        if (gesture.slots().isEmpty()) {
            return;
        }
        List<SlotBinding> targets = new ArrayList<>();
        for (GridSlot slot : gesture.slots().stream().sorted(Comparator.comparingInt(GridSlot::index)).toList()) {
            SlotBinding binding = editable(grid, slot);
            if (binding == null) {
                return;
            }
            targets.add(binding);
        }
        int each = gesture.one() ? 1 : count(plan.side.cursor()) / targets.size();
        for (SlotBinding binding : targets) {
            put(plan, binding, plan.side.cursor(), each, true, -1);
        }
    }
    private static final class Plan {
        private final Player viewer;
        private final InMemoryViewerItems side;
        private final Set<Text> sent = new LinkedHashSet<>();
        private final List<Applied> transfers = new ArrayList<>();
        Plan(Player viewer, ViewerItems source) {
            this.viewer = viewer;
            side = new InMemoryViewerItems(source.inventorySize());
            side.cursor(source.cursor());
            for (int i = 0; i < source.inventorySize(); i++) {
                side.slot(i, source.slot(i));
            }
        }
        ItemStack item(SlotBinding binding) { return binding.container().get(binding.index()); }
        void remove(SlotBinding binding, ItemStack item, int moved) {
            if (moved > 0) {
                container(binding).replace(binding.index(), amount(item, count(item) - moved));
                record(binding, TransferKind.TAKE_OUT, item, moved);
            }
        }
        void add(SlotBinding binding, ItemStack item, int moved) {
            if (moved > 0) {
                container(binding).replace(binding.index(), amount(item, count(item(binding)) + moved));
                record(binding, TransferKind.PUT_IN, item, moved);
            }
        }
        void record(SlotBinding binding, TransferKind kind, ItemStack item, int moved) {
            container(binding).record(viewer, kind, binding.index(), item, moved);
            transfers.add(new Applied(container(binding), new ContainerChange(viewer, kind, binding.index(), item, moved)));
        }
        boolean apply(ViewerItems target) {
            if (transfers.isEmpty()) {
                return false;
            }
            target.cursor(side.cursor());
            for (int i = 0; i < side.inventorySize(); i++) {
                target.slot(i, side.slot(i));
            }
            transfers.forEach(transfer -> transfer.container.changed(transfer.change));
            transfers.stream().map(Applied::container).distinct().forEach(InMemorySlotContainer::redraw);
            return true;
        }
    }
    private record Applied(InMemorySlotContainer container, ContainerChange change) { }
}
