package com.ultikits.ultitools.listeners;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

public class InventoryListener implements Listener {
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        HumanEntity player = event.getPlayer();
        if (!(player instanceof Player)){
            return;
        }
        UltiTools.getInstance().getViewManager().openView((Player) player,
                UltiTools.getInstance().getViewManager().getViewByInventory(event.getInventory()));
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        HumanEntity player = event.getPlayer();
        if (!(player instanceof Player)){
            return;
        }
//        UltiTools.getInstance().getViewManager().closeView((Player) player,
//                UltiTools.getInstance().getViewManager().getViewByInventory(event.getInventory()));
    }
}
