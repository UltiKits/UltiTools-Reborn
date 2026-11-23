package com.ultikits.ultitools.ui;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * The viewer's side: cursor and the 36 storage slots of their inventory (Bukkit slot numbers 0..35: hotbar 0..8,
 * main 9..35); armour and off-hand are never part of it. {@link #inventorySize()} is 36 on Paper.
 * Implemented over a Player by the backend, in memory by tests.
 */
@ApiStatus.Internal
public interface ViewerItems {
    ItemStack cursor();
    void cursor(ItemStack item);
    int inventorySize();
    ItemStack slot(int index);
    void slot(int index, ItemStack item);
}
