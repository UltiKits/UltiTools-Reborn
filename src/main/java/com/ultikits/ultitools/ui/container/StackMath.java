package com.ultikits.ultitools.ui.container;

import com.ultikits.ultitools.ui.ViewerItems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Copying and similarity-based item arithmetic; max sizes always come from the stack. */
@ApiStatus.Internal
final class StackMath {
    private StackMath() { /* Static arithmetic only. */ }
    static ItemStack copy(ItemStack item) {
        Objects.requireNonNull(item, "item");
        return item.isEmpty() ? ItemStack.empty() : item.clone();
    }
    static List<ItemStack> copies(List<ItemStack> items) {
        return Objects.requireNonNull(items, "items").stream().map(StackMath::copy).toList();
    }
    static ItemStack amount(ItemStack item, int amount) {
        if (amount <= 0 || item.isEmpty()) { return ItemStack.empty(); }
        ItemStack copy = copy(item);
        copy.setAmount(amount);
        return copy;
    }
    static int count(ItemStack item) { return item.isEmpty() ? 0 : item.getAmount(); }
    static int room(ItemStack target, ItemStack source) {
        if (source.isEmpty()) { return 0; }
        if (target.isEmpty()) { return source.getMaxStackSize(); }
        return target.isSimilar(source) ? Math.max(0, target.getMaxStackSize() - count(target)) : 0;
    }
    static List<ItemStack> empty(int size) { return Collections.nCopies(size, ItemStack.empty()); }
    static List<ItemStack> merged(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack source : items) {
            if (!source.isEmpty()) { mergeOne(result, source); }
        }
        return copies(result);
    }
    private static void mergeOne(List<ItemStack> result, ItemStack source) {
        for (ItemStack item : result) {
            if (item.isSimilar(source)) {
                item.setAmount(count(item) + count(source));
                return;
            }
        }
        result.add(copy(source));
    }
    static List<ItemStack> subtract(List<ItemStack> held, List<ItemStack> removed) {
        List<ItemStack> remaining = new ArrayList<>(copies(held));
        for (ItemStack source : removed) { subtractOne(remaining, source); }
        return merged(remaining);
    }
    private static void subtractOne(List<ItemStack> remaining, ItemStack source) {
        int left = count(source);
        for (int index = 0; index < remaining.size() && left > 0; index++) {
            ItemStack item = remaining.get(index);
            if (item.isSimilar(source)) {
                int consumed = Math.min(left, count(item));
                remaining.set(index, amount(item, count(item) - consumed));
                left -= consumed;
            }
        }
    }
    static int fit(ViewerItems side, ItemStack item) {
        int free = 0;
        for (int index = 0; index < side.inventorySize(); index++) { free += room(side.slot(index), item); }
        return Math.min(count(item), free);
    }
    static void insert(ViewerItems side, ItemStack item, int count) {
        int left = insertPass(side, item, count, false);
        insertPass(side, item, left, true);
    }
    private static int insertPass(ViewerItems side, ItemStack item, int count, boolean empty) {
        int left = count;
        for (int index = 0; index < side.inventorySize() && left > 0; index++) {
            ItemStack previous = side.slot(index);
            if (previous.isEmpty() != empty) { continue; }
            int moved = Math.min(left, room(previous, item));
            if (moved > 0) {
                side.slot(index, amount(item, count(previous) + moved));
                left -= moved;
            }
        }
        return left;
    }
}
