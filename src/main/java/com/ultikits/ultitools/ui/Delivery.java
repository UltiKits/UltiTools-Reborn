package com.ultikits.ultitools.ui;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/** given + dropped + undelivered equal the input, by item and amount. Copies. */
public record Delivery(List<ItemStack> given, List<ItemStack> dropped, List<ItemStack> undelivered) {
    /** Copies all three partitions. */
    public Delivery {
        given = ContainerSpec.copyItems(given, "given");
        dropped = ContainerSpec.copyItems(dropped, "dropped");
        undelivered = ContainerSpec.copyItems(undelivered, "undelivered");
    }
    /** Items inserted into the inventory; copies. */
    @Override public List<ItemStack> given() { return ContainerSpec.copyItems(given, "given"); }
    /** Items dropped at the player's feet; copies. */
    @Override public List<ItemStack> dropped() { return ContainerSpec.copyItems(dropped, "dropped"); }
    /** Items that could not be delivered; copies. */
    @Override public List<ItemStack> undelivered() { return ContainerSpec.copyItems(undelivered, "undelivered"); }
}
