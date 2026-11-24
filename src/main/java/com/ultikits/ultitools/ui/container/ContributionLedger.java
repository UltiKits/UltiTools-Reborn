package com.ultikits.ultitools.ui.container;

import com.ultikits.ultitools.ui.ContainerChanges;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Net contributor credit in transfer order, independent of container layout. */
@ApiStatus.Internal
final class ContributionLedger {
    private final List<Contribution> entries = new ArrayList<>();
    private final List<ItemStack> taken = new ArrayList<>();

    void put(UUID player, ItemStack item) {
        if (!item.isEmpty()) { entries.add(new Contribution(player, StackMath.copy(item))); }
    }
    void take(UUID player, ItemStack item, int amount) {
        int left = takePass(player, item, amount, true);
        left = takePass(player, item, left, false);
        entries.removeIf(entry -> entry.item.isEmpty());
        if (left > 0) { taken.add(StackMath.amount(item, left)); }
    }
    private int takePass(UUID player, ItemStack item, int count, boolean own) {
        int left = count;
        for (Contribution entry : entries) {
            if (left == 0) { break; }
            if (entry.player.equals(player) == own && entry.item.isSimilar(item)) {
                int removed = Math.min(left, StackMath.count(entry.item));
                entry.item = StackMath.amount(entry.item, StackMath.count(entry.item) - removed);
                left -= removed;
            }
        }
        return left;
    }
    ContainerChanges snapshot() {
        Map<UUID, List<ItemStack>> by = new LinkedHashMap<>();
        entries.forEach(entry -> by.computeIfAbsent(entry.player, ignored -> new ArrayList<>()).add(entry.item));
        return new Changes(entries.stream().map(entry -> entry.item).toList(), taken, by);
    }
    void clear() { entries.clear(); taken.clear(); }

    private static final class Contribution {
        final UUID player;
        ItemStack item;
        Contribution(UUID player, ItemStack item) { this.player = player; this.item = item; }
    }
    private static final class Changes implements ContainerChanges {
        private final List<ItemStack> put;
        private final List<ItemStack> taken;
        private final Map<UUID, List<ItemStack>> by = new LinkedHashMap<>();
        Changes(List<ItemStack> put, List<ItemStack> taken, Map<UUID, List<ItemStack>> by) {
            this.put = StackMath.merged(put);
            this.taken = StackMath.merged(taken);
            by.forEach((player, items) -> this.by.put(player, StackMath.merged(items)));
        }
        @Override public boolean isEmpty() { return put.isEmpty() && taken.isEmpty(); }
        @Override public List<ItemStack> putIn() { return StackMath.copies(put); }
        @Override public List<ItemStack> takenOut() { return StackMath.copies(taken); }
        @Override public Map<UUID, List<ItemStack>> putInBy() {
            Map<UUID, List<ItemStack>> result = new LinkedHashMap<>();
            by.forEach((player, items) -> result.put(player, StackMath.copies(items)));
            return Collections.unmodifiableMap(result);
        }
    }
}
