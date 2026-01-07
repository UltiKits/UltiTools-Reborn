package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.entity.BackupData;
import com.ultikits.plugins.backup.service.BackupService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backup selection GUI.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class BackupGUI implements InventoryHolder {
    
    private final BackupService backupService;
    private final Player viewer;
    private final UUID targetUuid;
    private final String targetName;
    private final Inventory inventory;
    private final List<BackupData> backups;
    private int currentPage = 0;
    
    private static final int ITEMS_PER_PAGE = 45;
    
    public BackupGUI(BackupService backupService, Player viewer, UUID targetUuid, String targetName) {
        this.backupService = backupService;
        this.viewer = viewer;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backups = backupService.getBackups(targetUuid);
        
        String title = backupService.getConfig().getGuiTitle()
            .replace("{PLAYER}", targetName)
            .replace("&", "§");
        
        this.inventory = Bukkit.createInventory(this, 54, title);
        updateInventory();
    }
    
    /**
     * Update inventory contents.
     */
    public void updateInventory() {
        inventory.clear();
        
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, backups.size());
        
        for (int i = start; i < end; i++) {
            BackupData backup = backups.get(i);
            inventory.setItem(i - start, createBackupItem(backup));
        }
        
        // Navigation row
        addNavigationRow();
    }
    
    /**
     * Create an item representing a backup.
     */
    private ItemStack createBackupItem(BackupData backup) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + backup.getFormattedTime());
            
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "原因: " + backup.getReasonDisplay());
            lore.add(ChatColor.GRAY + "世界: " + ChatColor.WHITE + backup.getWorldName());
            lore.add(ChatColor.GRAY + "位置: " + ChatColor.WHITE + 
                String.format("%.1f, %.1f, %.1f", 
                    backup.getLocationX(), backup.getLocationY(), backup.getLocationZ()));
            lore.add(ChatColor.GRAY + "等级: " + ChatColor.AQUA + backup.getExpLevel());
            lore.add("");
            lore.add(ChatColor.GREEN + "左键点击: 恢复此备份");
            lore.add(ChatColor.YELLOW + "Shift+左键: 预览备份");
            lore.add(ChatColor.RED + "右键点击: 删除此备份");
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    /**
     * Add navigation row.
     */
    private void addNavigationRow() {
        int totalPages = (int) Math.ceil((double) backups.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;
        
        // Fill bottom row with glass
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        // Previous page
        if (currentPage > 0) {
            inventory.setItem(45, createItem(Material.ARROW, ChatColor.GREEN + "上一页"));
        }
        
        // Page indicator
        inventory.setItem(49, createItem(Material.BOOK, 
            ChatColor.YELLOW + "第 " + (currentPage + 1) + " / " + totalPages + " 页"));
        
        // Next page
        if (currentPage < totalPages - 1) {
            inventory.setItem(53, createItem(Material.ARROW, ChatColor.GREEN + "下一页"));
        }
        
        // Create new backup button
        inventory.setItem(47, createItem(Material.EMERALD, 
            ChatColor.GREEN + "创建新备份",
            ChatColor.GRAY + "点击为此玩家创建新备份"));
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
                List<String> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(line);
                }
                meta.setLore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * Get backup at slot.
     */
    public BackupData getBackupAtSlot(int slot) {
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return null;
        
        int index = currentPage * ITEMS_PER_PAGE + slot;
        if (index >= backups.size()) return null;
        
        return backups.get(index);
    }
    
    /**
     * Go to next page.
     */
    public void nextPage() {
        int totalPages = (int) Math.ceil((double) backups.size() / ITEMS_PER_PAGE);
        if (currentPage < totalPages - 1) {
            currentPage++;
            updateInventory();
        }
    }
    
    /**
     * Go to previous page.
     */
    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
            updateInventory();
        }
    }
    
    /**
     * Refresh backups list.
     */
    public void refresh() {
        backups.clear();
        backups.addAll(backupService.getBackups(targetUuid));
        updateInventory();
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    public UUID getTargetUuid() {
        return targetUuid;
    }
    
    public String getTargetName() {
        return targetName;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
