package com.ultikits.plugins.remotebag.listener;

import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for remote bag GUI interactions.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class BagListener implements Listener {
    
    @Autowired
    private RemoteBagService bagService;
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        
        if (!bagService.hasBagOpen(player.getUniqueId())) {
            return;
        }
        
        String title = event.getView().getTitle();
        if (!title.contains("远程背包")) {
            return;
        }
        
        int slot = event.getRawSlot();
        int contentSize = bagService.getConfig().getRowsPerPage() * 9;
        
        // Navigation row clicks
        if (slot >= contentSize && slot < contentSize + 9) {
            event.setCancelled(true);
            
            Integer currentPage = bagService.getCurrentPage(player.getUniqueId());
            if (currentPage == null) return;
            
            int maxPages = bagService.getPlayerMaxPages(player);
            ItemStack clicked = event.getCurrentItem();
            
            if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                return;
            }
            
            // Previous page
            if (slot == contentSize && currentPage > 1) {
                // Save current page first
                saveCurrentPage(player, event.getInventory(), currentPage, contentSize);
                bagService.openBag(player, currentPage - 1);
            }
            // Next page
            else if (slot == contentSize + 8 && currentPage < maxPages) {
                saveCurrentPage(player, event.getInventory(), currentPage, contentSize);
                bagService.openBag(player, currentPage + 1);
            }
            // Page indicator - left/right click navigation
            else if (slot == contentSize + 4) {
                saveCurrentPage(player, event.getInventory(), currentPage, contentSize);
                if (event.isLeftClick() && currentPage > 1) {
                    bagService.openBag(player, currentPage - 1);
                } else if (event.isRightClick() && currentPage < maxPages) {
                    bagService.openBag(player, currentPage + 1);
                }
            }
            return;
        }
        
        // Block clicking outside content area (but in top inventory)
        if (slot >= contentSize + 9) {
            event.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        
        if (!bagService.hasBagOpen(player.getUniqueId())) {
            return;
        }
        
        bagService.closeBag(player, event.getInventory());
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Save and clear cache
        if (bagService.hasBagOpen(player.getUniqueId())) {
            bagService.saveBag(player.getUniqueId());
        }
        bagService.clearCache(player.getUniqueId());
    }
    
    /**
     * Save current page contents before switching.
     */
    private void saveCurrentPage(Player player, org.bukkit.inventory.Inventory inv, int page, int contentSize) {
        ItemStack[] contents = new ItemStack[contentSize];
        for (int i = 0; i < contentSize; i++) {
            contents[i] = inv.getItem(i);
        }
        bagService.setBagPage(player.getUniqueId(), page, contents);
    }
}
