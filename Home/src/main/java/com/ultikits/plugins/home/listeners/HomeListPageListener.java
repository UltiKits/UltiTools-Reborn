package com.ultikits.plugins.home.listeners;

import com.ultikits.beans.CancelResult;
import com.ultikits.inventoryapi.InventoryManager;
import com.ultikits.inventoryapi.PagesListener;
import com.ultikits.plugins.home.PluginMain;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class HomeListPageListener extends PagesListener {

    @Override
    public CancelResult onItemClick(InventoryClickEvent event, Player player, InventoryManager inventoryManager, ItemStack clickedItem) {
        if (inventoryManager.getTitle().contains(player.getName() + PluginMain.getPluginMain().i18n("的家列表"))) {
            String homeName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
            player.performCommand("home tp " + homeName);
            player.closeInventory();
            return CancelResult.TRUE;
        }
        return CancelResult.NONE;
    }
}
