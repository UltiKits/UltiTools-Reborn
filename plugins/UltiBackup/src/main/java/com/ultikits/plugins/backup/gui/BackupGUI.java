package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backup selection GUI with cross-version compatibility.
 * Uses XVersionUtils for material handling.
 * <p>
 * 备份选择 GUI（跨版本兼容）。
 * 使用 XVersionUtils 处理材料。
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class BackupGUI implements InventoryHolder {

    private final UltiToolsPlugin plugin;
    private final BackupService backupService;
    private final Player viewer;
    private final UUID targetUuid;
    private final String targetName;
    private final Inventory inventory;
    private final List<BackupMetadata> backups;
    private int currentPage = 0;

    private static final int ITEMS_PER_PAGE = 45;

    public BackupGUI(UltiToolsPlugin plugin, BackupService backupService, Player viewer, UUID targetUuid, String targetName) {
        this.plugin = plugin;
        this.backupService = backupService;
        this.viewer = viewer;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backups = backupService.getBackups(targetUuid);
        
        String title = i18n("backup.gui.title")
            .replace("{PLAYER}", targetName);
        
        this.inventory = Bukkit.createInventory(this, 54, title);
        updateInventory();
    }
    
    /**
     * Update inventory contents.
     * <p>
     * 更新物品栏内容。
     */
    public void updateInventory() {
        inventory.clear();
        
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, backups.size());
        
        for (int i = start; i < end; i++) {
            BackupMetadata backup = backups.get(i);
            inventory.setItem(i - start, createBackupItem(backup));
        }
        
        // Navigation row
        addNavigationRow();
    }
    
    /**
     * Create an item representing a backup.
     * <p>
     * 创建表示备份的物品。
     */
    private ItemStack createBackupItem(BackupMetadata backup) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + backup.getFormattedTime());
            
            List<String> lore = new ArrayList<>();
            lore.add(i18n("backup.gui.reason").replace("{REASON}", backup.getReasonDisplay()));
            lore.add(i18n("backup.gui.world").replace("{WORLD}", backup.getWorldName()));
            lore.add(i18n("backup.gui.location").replace("{LOCATION}", 
                String.format("%.1f, %.1f, %.1f", 
                    backup.getLocationX(), backup.getLocationY(), backup.getLocationZ())));
            lore.add(i18n("backup.gui.level").replace("{LEVEL}", String.valueOf(backup.getExpLevel())));
            lore.add("");
            lore.add(i18n("backup.gui.click_restore"));
            lore.add(i18n("backup.gui.click_preview"));
            lore.add(i18n("backup.gui.click_delete"));
            
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }
    
    /**
     * Add navigation row with cross-version compatible materials.
     * <p>
     * 添加导航行（使用跨版本兼容材料）。
     */
    private void addNavigationRow() {
        int totalPages = (int) Math.ceil((double) backups.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;
        
        // Fill bottom row with gray glass - using XVersionUtils for cross-version compatibility
        ItemStack filler = XVersionUtils.getColoredPlaneGlass(Colors.GRAY);
        setFillerName(filler, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        // Previous page
        if (currentPage > 0) {
            ItemStack prevBtn = XVersionUtils.getColoredPlaneGlass(Colors.LIME);
            setFillerName(prevBtn, i18n("backup.gui.previous_page"));
            inventory.setItem(45, prevBtn);
        }
        
        // Page indicator
        ItemStack pageIndicator = new ItemStack(Material.BOOK);
        ItemMeta pageMeta = pageIndicator.getItemMeta();
        if (pageMeta != null) {
            pageMeta.setDisplayName(i18n("backup.gui.page_indicator")
                .replace("{CURRENT}", String.valueOf(currentPage + 1))
                .replace("{TOTAL}", String.valueOf(totalPages)));
            pageIndicator.setItemMeta(pageMeta);
        }
        inventory.setItem(49, pageIndicator);
        
        // Next page
        if (currentPage < totalPages - 1) {
            ItemStack nextBtn = XVersionUtils.getColoredPlaneGlass(Colors.LIME);
            setFillerName(nextBtn, i18n("backup.gui.next_page"));
            inventory.setItem(53, nextBtn);
        }
        
        // Create new backup button
        ItemStack createBtn = new ItemStack(Material.EMERALD);
        ItemMeta createMeta = createBtn.getItemMeta();
        if (createMeta != null) {
            createMeta.setDisplayName(i18n("backup.gui.create_new"));
            List<String> lore = new ArrayList<>();
            lore.add(i18n("backup.gui.create_new_lore"));
            createMeta.setLore(lore);
            createBtn.setItemMeta(createMeta);
        }
        inventory.setItem(47, createBtn);
    }
    
    /**
     * Set display name for filler item.
     */
    private void setFillerName(ItemStack item, String name) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
    }
    
    /**
     * Get backup at slot.
     * <p>
     * 获取指定槽位的备份。
     */
    public BackupMetadata getBackupAtSlot(int slot) {
        if (slot < 0 || slot >= ITEMS_PER_PAGE) return null;
        
        int index = currentPage * ITEMS_PER_PAGE + slot;
        if (index >= backups.size()) return null;
        
        return backups.get(index);
    }
    
    /**
     * Go to next page.
     * <p>
     * 下一页。
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
     * <p>
     * 上一页。
     */
    public void previousPage() {
        if (currentPage > 0) {
            currentPage--;
            updateInventory();
        }
    }
    
    /**
     * Refresh backups list.
     * <p>
     * 刷新备份列表。
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
    
    public BackupService getBackupService() {
        return backupService;
    }
    
    @Override
    public Inventory getInventory() {
        return inventory;
    }
    
    /**
     * Get i18n message.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
