package com.ultikits.ultitools.config.convert;

import org.bukkit.configuration.serialization.DelegateDeserialization;
import org.bukkit.inventory.ItemStack;

/** Models Paper's item subclass identity without reflecting into server internals. */
@DelegateDeserialization(ItemStack.class)
public class DelegatingItemStackDouble extends ItemStack {
    public DelegatingItemStackDouble(ItemStack source) { super(source); }
}
