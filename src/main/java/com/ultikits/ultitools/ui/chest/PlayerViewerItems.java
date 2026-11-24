package com.ultikits.ultitools.ui.chest;

import com.ultikits.ultitools.ui.ViewerItems;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** The viewer's cursor and storage only; armour and off-hand are never addressed. */
@ApiStatus.Internal
public final class PlayerViewerItems implements ViewerItems {
    private final InventoryView view;
    private final Player player;

    public PlayerViewerItems(InventoryView view, Player player) {
        this.view = Objects.requireNonNull(view, "view");
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override public ItemStack cursor() { return copy(view.getCursor()); }
    @Override public void cursor(ItemStack item) { view.setCursor(copy(Objects.requireNonNull(item, "item"))); }
    @Override public int inventorySize() { return 36; }
    @Override public ItemStack slot(int index) {
        return copy(player.getInventory().getItem(Objects.checkIndex(index, inventorySize())));
    }
    @Override public void slot(int index, ItemStack item) {
        player.getInventory().setItem(Objects.checkIndex(index, inventorySize()), copy(Objects.requireNonNull(item, "item")));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? ItemStack.empty() : item.clone();
    }
}
