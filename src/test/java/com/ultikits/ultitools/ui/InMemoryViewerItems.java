package com.ultikits.ultitools.ui;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/** Copying test-only cursor and storage inventory. */
final class InMemoryViewerItems implements ViewerItems {
    private ItemStack cursor = ItemStack.empty();
    private final List<ItemStack> inventory;
    InMemoryViewerItems(int size) { inventory = UiTestItems.empty(size); }
    @Override public ItemStack cursor() { return UiTestItems.copy(cursor); }
    @Override public void cursor(ItemStack item) { cursor = UiTestItems.copy(item); }
    @Override public int inventorySize() { return inventory.size(); }
    @Override public ItemStack slot(int index) { return UiTestItems.copy(inventory.get(index)); }
    @Override public void slot(int index, ItemStack item) { inventory.set(index, UiTestItems.copy(item)); }
}
