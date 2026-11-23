package com.ultikits.ultitools.ui;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** The viewer's side: cursor and inventory. Implemented over a Player by the backend, in memory by tests. */
@ApiStatus.Internal
public interface ViewerItems {
    ItemStack cursor();
    void cursor(ItemStack item);
    int inventorySize();
    ItemStack slot(int index);
    void slot(int index, ItemStack item);
}
