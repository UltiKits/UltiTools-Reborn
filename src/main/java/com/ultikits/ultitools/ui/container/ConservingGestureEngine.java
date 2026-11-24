package com.ultikits.ultitools.ui.container;

import static com.ultikits.ultitools.ui.container.StackMath.amount;
import static com.ultikits.ultitools.ui.container.StackMath.count;

import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.ContainerChange;
import com.ultikits.ultitools.ui.ContainerKind;
import com.ultikits.ultitools.ui.Gesture;
import com.ultikits.ultitools.ui.GestureEngine;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.SlotContainer;
import com.ultikits.ultitools.ui.TransferDecision;
import com.ultikits.ultitools.ui.TransferKind;
import com.ultikits.ultitools.ui.ViewerItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The only item-transfer path: decide in order, apply conserved deltas, then publish. */
@ApiStatus.Internal
final class ConservingGestureEngine implements GestureEngine {
    private final BooleanSupplier onContainerThread;
    private final Predicate<SlotContainer> owns;
    private final BiConsumer<Player, Text> notifier;

    ConservingGestureEngine(BooleanSupplier onContainerThread, Predicate<SlotContainer> owns, BiConsumer<Player, Text> notifier) {
        this.onContainerThread = onContainerThread;
        this.owns = owns;
        this.notifier = notifier;
    }
    @Override public boolean apply(Grid grid, Player viewer, ViewerItems side, Gesture gesture) {
        if (!onContainerThread.getAsBoolean()) { throw new IllegalStateException("Gesture called off the container thread"); }
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
        return plan.publish(side);
    }
    private @Nullable SlotBinding editable(Grid grid, GridSlot slot) {
        SlotBinding binding = grid.binding(slot).orElse(null);
        if (binding == null || binding.mode() != BindingMode.EDITABLE || !owns.test(binding.container())) { return null; }
        return container(binding).editable() ? binding : null;
    }
    private static FrameworkSlotContainer container(SlotBinding binding) {
        return (FrameworkSlotContainer) binding.container();
    }
    private int allowed(Plan plan, SlotBinding binding, TransferKind kind, ItemStack item, int requested) {
        if (requested <= 0) { return 0; }
        TransferDecision decision = container(binding).decide(plan.viewer, kind, binding.index(), item, requested);
        decision.message().filter(plan.sent::add).ifPresent(message -> {
            try { notifier.accept(plan.viewer, message); }
            catch (Exception | LinkageError failure) { container(binding).warn("Refusal notification failed", failure); }
        });
        return decision.allowedAmount(requested);
    }
    private void pickup(Grid grid, Plan plan, Gesture.Pickup gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || !plan.side.cursor().isEmpty()) { return; }
        ItemStack held = plan.item(binding);
        int requested = binding.container().kind() == ContainerKind.TEMPLATE || !gesture.half()
                ? count(held) : (count(held) + 1) / 2;
        int moved = allowed(plan, binding, TransferKind.TAKE_OUT, held, requested);
        plan.remove(binding, held, moved);
        if (moved > 0 && binding.container().kind() == ContainerKind.STORAGE) { plan.side.cursor(amount(held, moved)); }
    }
    private void place(Grid grid, Plan plan, Gesture.Place gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null) { return; }
        ItemStack source = plan.side.cursor();
        put(plan, binding, source, gesture.one() ? 1 : count(source), -1);
    }
    private int put(Plan plan, SlotBinding binding, ItemStack source, int requested, int playerSlot) {
        int possible = Math.min(requested, Math.min(count(source), StackMath.room(plan.item(binding), source)));
        int moved = allowed(plan, binding, TransferKind.PUT_IN, source, possible);
        plan.add(binding, source, moved);
        if (moved > 0 && binding.container().kind() == ContainerKind.STORAGE) {
            plan.side.write(playerSlot, amount(source, count(source) - moved));
        }
        return moved;
    }
    private void swapCursor(Grid grid, Plan plan, Gesture.SwapWithCursor gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || plan.side.cursor().isEmpty()) { return; }
        exchange(plan, binding, plan.side.cursor(), -1);
    }
    private void hotbar(Grid grid, Plan plan, Gesture.HotbarSwap gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null || gesture.hotbar() >= plan.side.inventorySize()) { return; }
        ItemStack source = plan.side.slot(gesture.hotbar());
        ItemStack held = plan.item(binding);
        if (binding.container().kind() == ContainerKind.TEMPLATE) {
            exchange(plan, binding, source, gesture.hotbar());
        } else if (held.isEmpty()) {
            put(plan, binding, source, count(source), gesture.hotbar());
        } else if (source.isEmpty()) {
            int moved = allowed(plan, binding, TransferKind.TAKE_OUT, held, count(held));
            plan.remove(binding, held, moved);
            if (moved > 0) { plan.side.slot(gesture.hotbar(), amount(held, moved)); }
        } else {
            exchange(plan, binding, source, gesture.hotbar());
        }
    }
    private void exchange(Plan plan, SlotBinding binding, ItemStack source, int playerSlot) {
        ItemStack held = plan.item(binding);
        if (held.equals(source)) { return; }
        int taken = allowed(plan, binding, TransferKind.TAKE_OUT, held, count(held));
        int put = allowed(plan, binding, TransferKind.PUT_IN, source, count(source));
        if (taken != count(held) || put != count(source)) { return; }
        plan.remove(binding, held, taken);
        plan.add(binding, source, put);
        if (binding.container().kind() == ContainerKind.STORAGE) { plan.side.write(playerSlot, held); }
    }
    private void quickOut(Grid grid, Plan plan, Gesture.QuickMoveOut gesture) {
        SlotBinding binding = editable(grid, gesture.slot());
        if (binding == null) { return; }
        ItemStack held = plan.item(binding);
        int fits = binding.container().kind() == ContainerKind.TEMPLATE ? count(held) : StackMath.fit(plan.side, held);
        int moved = allowed(plan, binding, TransferKind.TAKE_OUT, held, fits);
        plan.remove(binding, held, moved);
        if (binding.container().kind() == ContainerKind.STORAGE) { StackMath.insert(plan.side, held, moved); }
    }
    private void quickIn(Grid grid, Plan plan, Gesture.QuickMoveIn gesture) {
        int sourceSlot = gesture.playerSlot();
        if (sourceSlot < 0 || sourceSlot >= plan.side.inventorySize()) { return; }
        List<SlotBinding> targets = grid.bindings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(GridSlot::index)))
                .map(entry -> editable(grid, entry.getKey())).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(SlotBinding::index)).toList();
        int remaining = quickInPass(plan, targets, sourceSlot, false, count(plan.side.slot(sourceSlot)));
        quickInPass(plan, targets, sourceSlot, true, remaining);
    }
    private int quickInPass(Plan plan, List<SlotBinding> targets, int sourceSlot, boolean empty, int remaining) {
        int left = remaining;
        for (SlotBinding binding : targets) {
            if (left == 0) { break; }
            if (plan.item(binding).isEmpty() == empty) {
                left -= put(plan, binding, plan.side.slot(sourceSlot), left, sourceSlot);
            }
        }
        return left;
    }
    private void spread(Grid grid, Plan plan, Gesture.Spread gesture) {
        if (gesture.slots().isEmpty()) { return; }
        List<SlotBinding> targets = new ArrayList<>();
        for (GridSlot slot : gesture.slots().stream().sorted(Comparator.comparingInt(GridSlot::index)).toList()) {
            SlotBinding binding = editable(grid, slot);
            if (binding == null) { return; }
            targets.add(binding);
        }
        int each = gesture.one() ? 1 : count(plan.side.cursor()) / targets.size();
        for (SlotBinding binding : targets) { put(plan, binding, plan.side.cursor(), each, -1); }
    }

    private static final class Plan {
        final Player viewer;
        final ViewerCopy side;
        final Set<Text> sent = new LinkedHashSet<>();
        private final List<Applied> transfers = new ArrayList<>();
        private final Set<FrameworkSlotContainer> changed = new LinkedHashSet<>();
        Plan(Player viewer, ViewerItems side) { this.viewer = viewer; this.side = new ViewerCopy(side); }
        ItemStack item(SlotBinding binding) { return binding.container().get(binding.index()); }
        void remove(SlotBinding binding, ItemStack item, int moved) {
            if (moved <= 0) { return; }
            container(binding).replace(binding.index(), amount(item, count(item) - moved));
            record(binding, TransferKind.TAKE_OUT, item, moved);
        }
        void add(SlotBinding binding, ItemStack item, int moved) {
            if (moved <= 0) { return; }
            container(binding).replace(binding.index(), amount(item, count(item(binding)) + moved));
            record(binding, TransferKind.PUT_IN, item, moved);
        }
        private void record(SlotBinding binding, TransferKind kind, ItemStack item, int moved) {
            FrameworkSlotContainer target = container(binding);
            target.record(viewer, kind, item, moved);
            transfers.add(new Applied(target, new ContainerChange(viewer, kind, binding.index(), item, moved)));
            changed.add(target);
        }
        boolean publish(ViewerItems target) {
            if (transfers.isEmpty()) { return false; }
            side.publish(target);
            transfers.forEach(applied -> applied.container().changed(applied.change()));
            changed.forEach(FrameworkSlotContainer::redraw);
            return true;
        }
    }
    private record Applied(FrameworkSlotContainer container, ContainerChange change) { }

    private static final class ViewerCopy implements ViewerItems {
        private ItemStack cursor;
        private final List<ItemStack> slots = new ArrayList<>();
        ViewerCopy(ViewerItems source) {
            cursor = StackMath.copy(source.cursor());
            for (int index = 0; index < source.inventorySize(); index++) { slots.add(StackMath.copy(source.slot(index))); }
        }
        @Override public ItemStack cursor() { return StackMath.copy(cursor); }
        @Override public void cursor(ItemStack item) { cursor = StackMath.copy(item); }
        @Override public int inventorySize() { return slots.size(); }
        @Override public ItemStack slot(int index) { return StackMath.copy(slots.get(index)); }
        @Override public void slot(int index, ItemStack item) { slots.set(index, StackMath.copy(item)); }
        void write(int index, ItemStack item) {
            if (index < 0) { cursor(item); } else { slot(index, item); }
        }
        void publish(ViewerItems target) {
            target.cursor(cursor());
            for (int index = 0; index < slots.size(); index++) { target.slot(index, slot(index)); }
        }
    }
}
