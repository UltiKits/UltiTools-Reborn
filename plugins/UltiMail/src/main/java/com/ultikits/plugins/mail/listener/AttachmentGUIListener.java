package com.ultikits.plugins.mail.listener;

import com.ultikits.plugins.mail.gui.AttachmentSelectorPage;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Listener for attachment selector GUI.
 * <p>
 * Allows players to freely place and remove items in the content area,
 * while restricting actions in the toolbar area.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class AttachmentGUIListener implements Listener {
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AttachmentSelectorPage)) {
            return;
        }
        
        AttachmentSelectorPage page = (AttachmentSelectorPage) event.getInventory().getHolder();
        int slot = event.getRawSlot();
        int contentSize = AttachmentSelectorPage.getContentSize();
        
        // Allow free item manipulation in content area (slots 0-44)
        if (slot >= 0 && slot < contentSize) {
            // Don't cancel - allow normal item operations
            return;
        }
        
        // Bottom toolbar area (slots 45-53) - handled by base class onClick
        // Cancel shift-click from player inventory to prevent putting items in toolbar
        if (event.isShiftClick() && slot >= event.getView().getTopInventory().getSize()) {
            // Check if target slot would be in toolbar
            int targetSlot = event.getView().getTopInventory().firstEmpty();
            if (targetSlot >= contentSize) {
                event.setCancelled(true);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof AttachmentSelectorPage)) {
            return;
        }
        
        int contentSize = AttachmentSelectorPage.getContentSize();
        
        // Cancel if any slot is in the toolbar area
        for (int slot : event.getRawSlots()) {
            if (slot >= contentSize && slot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof AttachmentSelectorPage)) {
            return;
        }
        
        AttachmentSelectorPage page = (AttachmentSelectorPage) event.getInventory().getHolder();

        // If not confirmed (closed by ESC or other means), return items
        if (!page.isConfirmed()) {
            page.returnAllItems();
        }
    }
}
