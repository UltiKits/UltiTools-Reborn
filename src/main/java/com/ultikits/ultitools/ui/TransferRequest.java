package com.ultikits.ultitools.ui;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** One proposed transfer of {@code amount} of {@code item} at {@code index}. {@code item} is a copy. */
public record TransferRequest(Player viewer, TransferKind kind, int index, ItemStack item, int amount) {
    /** Copies the item and rejects null references. */
    public TransferRequest {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(kind, "kind");
        item = ContainerSpec.copyItem(item, "item");
    }
    /** A fresh copy of the proposed item. */
    @Override public ItemStack item() { return ContainerSpec.copyItem(item, "item"); }
}
