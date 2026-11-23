package com.ultikits.ultitools.ui;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** An applied transfer. */
public record ContainerChange(Player viewer, TransferKind kind, int index, ItemStack item, int amount) {
    /** Copies the item and rejects null references. */
    public ContainerChange {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(kind, "kind");
        item = ContainerSpec.copyItem(item, "item");
    }
    /** A fresh copy of the transferred item. */
    @Override public ItemStack item() { return ContainerSpec.copyItem(item, "item"); }
}
