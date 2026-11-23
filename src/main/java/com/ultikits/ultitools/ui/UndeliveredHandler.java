package com.ultikits.ultitools.ui;

import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** Receives items owed to a player who is offline or cannot receive them. Exactly once per item batch. */
@FunctionalInterface
public interface UndeliveredHandler {
    void undelivered(UUID player, List<ItemStack> items);
}
