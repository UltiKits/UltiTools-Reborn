package com.ultikits.ultitools.ui.runtime;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Returns backpack-menu items through vanilla cleanup before either UI backend replaces the screen. */
@ApiStatus.Internal
public final class PlayerInventoryPreparation {
    private PlayerInventoryPreparation() { /* Static preflight. */ }

    /** Server thread only. Foreign containers retain Paper's own opening and closing behaviour. */
    public static void beforeShow(Player viewer) {
        InventoryView current = viewer.getOpenInventory();
        InventoryType type = current.getType();
        if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) { return; }
        if (!viewer.getItemOnCursor().isEmpty() || hasCraftingInputs(current)) { viewer.closeInventory(); }
    }

    private static boolean hasCraftingInputs(InventoryView view) {
        if (!(view.getTopInventory() instanceof CraftingInventory crafting)) { return false; }
        for (ItemStack item : crafting.getMatrix()) {
            if (item != null && !item.isEmpty()) { return true; }
        }
        return false;
    }
}
