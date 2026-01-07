package com.ultikits.plugins.trade.listener;

import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.gui.TradeGUI;
import com.ultikits.plugins.trade.service.TradeService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for trade GUI interactions.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class TradeListener implements Listener {
    
    @Autowired
    private TradeService tradeService;
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeGUI)) {
            return;
        }
        
        TradeGUI gui = (TradeGUI) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        TradeSession session = gui.getSession();
        int slot = event.getRawSlot();
        
        // Click outside the trade GUI
        if (slot >= 54) {
            event.setCancelled(true);
            return;
        }
        
        // Handle confirm button
        if (slot == TradeGUI.CONFIRM_SLOT) {
            event.setCancelled(true);
            if (session.isConfirmed(player.getUniqueId())) {
                tradeService.cancelConfirmation(player);
            } else {
                tradeService.confirmTrade(player);
            }
            updateBothGUIs(session);
            return;
        }
        
        // Handle cancel button
        if (slot == TradeGUI.CANCEL_SLOT) {
            event.setCancelled(true);
            tradeService.cancelTrade(player);
            return;
        }
        
        // Handle money slot click
        if (slot == TradeGUI.YOUR_MONEY_SLOT && tradeService.hasEconomy()) {
            event.setCancelled(true);
            // TODO: Open money input dialog
            player.sendMessage("§e金币交易功能暂未完全实现，请直接放入物品交易");
            return;
        }
        
        // Block other player's side
        for (int s : TradeGUI.THEIR_SLOTS) {
            if (s == slot) {
                event.setCancelled(true);
                return;
            }
        }
        
        // Block status and separator slots
        for (int s : TradeGUI.SEPARATOR_SLOTS) {
            if (s == slot) {
                event.setCancelled(true);
                return;
            }
        }
        if (slot == TradeGUI.YOUR_STATUS_SLOT || slot == TradeGUI.THEIR_STATUS_SLOT ||
            slot == TradeGUI.THEIR_MONEY_SLOT || 
            (slot >= 45 && slot < 54 && slot != TradeGUI.CONFIRM_SLOT && slot != TradeGUI.CANCEL_SLOT)) {
            event.setCancelled(true);
            return;
        }
        
        // Handle your item slots
        if (gui.isYourSlot(slot)) {
            // Allow placing/removing items
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            
            int index = gui.getItemIndex(slot);
            
            // If clicking on glass pane, it's empty - allow placing
            if (current != null && current.getType().name().contains("STAINED_GLASS_PANE")) {
                if (cursor != null && !cursor.getType().isAir()) {
                    // Place item
                    session.setItem(player.getUniqueId(), index, cursor.clone());
                    event.setCancelled(true);
                    event.getView().setCursor(null);
                    updateBothGUIs(session);
                }
            } else if (current != null && !current.getType().isAir()) {
                // Remove item
                session.setItem(player.getUniqueId(), index, null);
                event.setCancelled(true);
                
                // Give item back to player
                player.getInventory().addItem(current);
                updateBothGUIs(session);
            }
            return;
        }
    }
    
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TradeGUI) {
            // Block dragging in trade GUI
            event.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeGUI)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        TradeSession session = tradeService.getSession(player.getUniqueId());
        
        if (session != null && session.getState() == TradeSession.TradeState.TRADING) {
            // Cancel trade when closing GUI
            Bukkit.getScheduler().runTaskLater(
                Bukkit.getPluginManager().getPlugin("UltiTools"),
                () -> {
                    if (tradeService.isTrading(player.getUniqueId())) {
                        tradeService.cancelTrade(player);
                    }
                },
                1L
            );
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (tradeService.isTrading(player.getUniqueId())) {
            tradeService.cancelTrade(player);
        }
    }
    
    /**
     * Update both players' GUIs.
     */
    private void updateBothGUIs(TradeSession session) {
        Player player1 = Bukkit.getPlayer(session.getPlayer1());
        Player player2 = Bukkit.getPlayer(session.getPlayer2());
        
        if (player1 != null && player1.getOpenInventory().getTopInventory().getHolder() instanceof TradeGUI) {
            ((TradeGUI) player1.getOpenInventory().getTopInventory().getHolder()).update();
        }
        if (player2 != null && player2.getOpenInventory().getTopInventory().getHolder() instanceof TradeGUI) {
            ((TradeGUI) player2.getOpenInventory().getTopInventory().getHolder()).update();
        }
    }
}
