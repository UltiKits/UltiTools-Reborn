package com.ultikits.plugins.home.listeners;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.ultitools.abstracts.AbstractPagesListener;
import com.ultikits.ultitools.entities.CancelResult;
import com.ultikits.ultitools.proxy.InventoryProxy;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class HomeListPageListener extends AbstractPagesListener {

    @Override
    public CancelResult onItemClick(InventoryClickEvent event, Player player, InventoryProxy inventoryProxy, ItemStack clickedItem) {
        if (PluginMain.getViewManager().clickOnValidView(player, inventoryProxy, PluginMain.getPluginMain().i18n("====家列表===="))) {
            String homeName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
            player.performCommand("home tp " + homeName);
            PluginMain.getViewManager().closeAllViews(player);
            return CancelResult.TRUE;
        }
        return CancelResult.NONE;
    }
}
