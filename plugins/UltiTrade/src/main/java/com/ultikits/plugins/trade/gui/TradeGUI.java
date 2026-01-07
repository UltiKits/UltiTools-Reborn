package com.ultikits.plugins.trade.gui;

import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.service.TradeService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Trade GUI implementation.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TradeGUI implements InventoryHolder {
    
    private final TradeService tradeService;
    private final TradeSession session;
    private final Player viewer;
    private final Inventory inventory;
    
    // GUI layout constants
    // Left side (0-3 columns): Your items (slots 0-3, 9-12, 18-21, 27-30)
    // Middle (column 4): Separator and buttons
    // Right side (5-8 columns): Their items (slots 5-8, 14-17, 23-26, 32-35)
    // Bottom row: Status and confirm button
    
    public static final int[] YOUR_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    public static final int[] THEIR_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    public static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40};
    public static final int CONFIRM_SLOT = 49;
    public static final int CANCEL_SLOT = 45;
    public static final int YOUR_MONEY_SLOT = 36;
    public static final int THEIR_MONEY_SLOT = 44;
    public static final int YOUR_STATUS_SLOT = 37;
    public static final int THEIR_STATUS_SLOT = 43;
    
    public TradeGUI(TradeService tradeService, TradeSession session, Player viewer) {
        this.tradeService = tradeService;
        this.session = session;
        this.viewer = viewer;
        
        Player other = Bukkit.getPlayer(session.getOtherPlayer(viewer.getUniqueId()));
        String title = tradeService.getConfig().getGuiTitle()
            .replace("{PLAYER}", other != null ? other.getName() : "???");
        
        this.inventory = Bukkit.createInventory(this, 54, 
            ChatColor.translateAlternateColorCodes('&', title));
        
        initializeGUI();
    }
    
    /**
     * Initialize GUI elements.
     */
    private void initializeGUI() {
        // Fill separators
        ItemStack separator = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot : SEPARATOR_SLOTS) {
            inventory.setItem(slot, separator);
        }
        
        // Fill empty slots with glass
        ItemStack yourGlass = createItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "你的物品");
        ItemStack theirGlass = createItem(Material.CYAN_STAINED_GLASS_PANE, ChatColor.AQUA + "对方物品");
        
        for (int slot : YOUR_SLOTS) {
            inventory.setItem(slot, yourGlass);
        }
        for (int slot : THEIR_SLOTS) {
            inventory.setItem(slot, theirGlass);
        }
        
        // Bottom row
        ItemStack bottomFiller = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, bottomFiller);
        }
        
        // Confirm button
        updateConfirmButton();
        
        // Cancel button
        ItemStack cancelBtn = createItem(Material.BARRIER, ChatColor.RED + "取消交易");
        inventory.setItem(CANCEL_SLOT, cancelBtn);
        
        // Money display
        updateMoneyDisplay();
        
        // Status display
        updateStatusDisplay();
    }
    
    /**
     * Update the GUI with current trade state.
     */
    public void update() {
        UUID viewerUuid = viewer.getUniqueId();
        
        // Update your items
        Map<Integer, ItemStack> yourItems = session.getPlayerItems(viewerUuid);
        for (int i = 0; i < YOUR_SLOTS.length; i++) {
            ItemStack item = yourItems.get(i);
            if (item != null) {
                inventory.setItem(YOUR_SLOTS[i], item);
            } else {
                inventory.setItem(YOUR_SLOTS[i], createItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "你的物品"));
            }
        }
        
        // Update their items (display only)
        Map<Integer, ItemStack> theirItems = session.getOtherPlayerItems(viewerUuid);
        for (int i = 0; i < THEIR_SLOTS.length; i++) {
            ItemStack item = theirItems.get(i);
            if (item != null) {
                inventory.setItem(THEIR_SLOTS[i], item.clone());
            } else {
                inventory.setItem(THEIR_SLOTS[i], createItem(Material.CYAN_STAINED_GLASS_PANE, ChatColor.AQUA + "对方物品"));
            }
        }
        
        updateMoneyDisplay();
        updateStatusDisplay();
        updateConfirmButton();
    }
    
    /**
     * Update money display.
     */
    private void updateMoneyDisplay() {
        UUID viewerUuid = viewer.getUniqueId();
        
        if (tradeService.hasEconomy()) {
            double yourMoney = session.getPlayerMoney(viewerUuid);
            double theirMoney = session.getOtherPlayerMoney(viewerUuid);
            
            ItemStack yourMoneyItem = createItem(Material.GOLD_NUGGET, 
                ChatColor.GOLD + "你的金币: " + ChatColor.WHITE + yourMoney,
                ChatColor.GRAY + "点击修改金额");
            inventory.setItem(YOUR_MONEY_SLOT, yourMoneyItem);
            
            ItemStack theirMoneyItem = createItem(Material.GOLD_NUGGET,
                ChatColor.GOLD + "对方金币: " + ChatColor.WHITE + theirMoney);
            inventory.setItem(THEIR_MONEY_SLOT, theirMoneyItem);
        } else {
            ItemStack disabled = createItem(Material.BARRIER, ChatColor.RED + "金币交易未启用");
            inventory.setItem(YOUR_MONEY_SLOT, disabled);
            inventory.setItem(THEIR_MONEY_SLOT, disabled);
        }
    }
    
    /**
     * Update status display.
     */
    private void updateStatusDisplay() {
        UUID viewerUuid = viewer.getUniqueId();
        
        boolean yourConfirmed = session.isConfirmed(viewerUuid);
        boolean theirConfirmed = session.isConfirmed(session.getOtherPlayer(viewerUuid));
        
        ItemStack yourStatus = createItem(
            yourConfirmed ? Material.LIME_WOOL : Material.RED_WOOL,
            yourConfirmed ? ChatColor.GREEN + "你已确认" : ChatColor.RED + "你未确认"
        );
        inventory.setItem(YOUR_STATUS_SLOT, yourStatus);
        
        ItemStack theirStatus = createItem(
            theirConfirmed ? Material.LIME_WOOL : Material.RED_WOOL,
            theirConfirmed ? ChatColor.GREEN + "对方已确认" : ChatColor.RED + "对方未确认"
        );
        inventory.setItem(THEIR_STATUS_SLOT, theirStatus);
    }
    
    /**
     * Update confirm button.
     */
    private void updateConfirmButton() {
        boolean confirmed = session.isConfirmed(viewer.getUniqueId());
        
        ItemStack confirmBtn = createItem(
            confirmed ? Material.LIME_CONCRETE : Material.GREEN_CONCRETE,
            confirmed ? ChatColor.YELLOW + "点击取消确认" : ChatColor.GREEN + "确认交易",
            confirmed ? ChatColor.GRAY + "已锁定，等待对方确认" : ChatColor.GRAY + "确认后交易将进行"
        );
        inventory.setItem(CONFIRM_SLOT, confirmBtn);
    }
    
    /**
     * Create an item with name and lore.
     */
    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Check if slot is a "your items" slot.
     */
    public boolean isYourSlot(int slot) {
        for (int s : YOUR_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }
    
    /**
     * Get item index from slot.
     */
    public int getItemIndex(int slot) {
        for (int i = 0; i < YOUR_SLOTS.length; i++) {
            if (YOUR_SLOTS[i] == slot) return i;
        }
        return -1;
    }
    
    public TradeSession getSession() {
        return session;
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
