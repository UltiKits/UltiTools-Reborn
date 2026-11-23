package com.ultikits.ultitools.ui;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item arithmetic shared only by the test doubles, not by contract assertions. */
final class UiTestItems {
    private UiTestItems() { /* Static test arithmetic. */ }
    static ItemStack copy(ItemStack item) { return ContainerSpec.copyItem(item, "item"); }
    static List<ItemStack> copies(List<ItemStack> items) { return ContainerSpec.copyItems(items, "items"); }
    static ItemStack amount(ItemStack item, int amount) {
        if (amount <= 0 || item.isEmpty()) {
            return ItemStack.empty();
        }
        ItemStack result = copy(item);
        result.setAmount(amount);
        return result;
    }
    static ItemStack stone(int amount) { return amount(new ItemStack(Material.STONE), amount); }
    static ItemStack dirt(int amount) { return amount(new ItemStack(Material.DIRT), amount); }
    static int count(ItemStack item) { return item.isEmpty() ? 0 : item.getAmount(); }
    static int room(ItemStack target, ItemStack source) {
        if (source.isEmpty()) {
            return 0;
        }
        if (target.isEmpty()) {
            return source.getMaxStackSize();
        }
        return target.isSimilar(source) ? Math.max(0, target.getMaxStackSize() - target.getAmount()) : 0;
    }
    static List<ItemStack> empty(int size) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(ItemStack.empty());
        }
        return result;
    }
    static List<ItemStack> merged(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                mergeOne(result, item);
            }
        }
        return copies(result);
    }
    private static void mergeOne(List<ItemStack> result, ItemStack source) {
        for (ItemStack item : result) {
            if (item.isSimilar(source)) {
                item.setAmount(item.getAmount() + source.getAmount());
                return;
            }
        }
        result.add(copy(source));
    }
    static int fit(ViewerItems side, ItemStack item) {
        int room = 0;
        for (int i = 0; i < side.inventorySize(); i++) {
            room += room(side.slot(i), item);
        }
        return Math.min(count(item), room);
    }
    static void insert(ViewerItems side, ItemStack item, int amount) {
        int left = insertPass(side, item, amount, false);
        insertPass(side, item, left, true);
    }
    private static int insertPass(ViewerItems side, ItemStack item, int amount, boolean empty) {
        int left = amount;
        for (int i = 0; i < side.inventorySize() && left > 0; i++) {
            ItemStack previous = side.slot(i);
            if (previous.isEmpty() == empty) {
                int moved = Math.min(left, room(previous, item));
                if (moved > 0) {
                    side.slot(i, amount(item, count(previous) + moved));
                    left -= moved;
                }
            }
        }
        return left;
    }
}
