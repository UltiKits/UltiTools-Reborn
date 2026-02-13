package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.entity.BackupContent;
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

/**
 * Backup preview GUI for viewing backup contents without restoring.
 * <p>
 * 备份预览 GUI，用于查看备份内容而不恢复。
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class BackupPreviewGUI implements InventoryHolder {

    private final UltiToolsPlugin plugin;
    private final Player viewer;
    private final BackupMetadata metadata;
    private final BackupContent content;
    private final Inventory inventory;
    private int currentView = 0; // 0: inventory, 1: armor, 2: enderchest

    public BackupPreviewGUI(UltiToolsPlugin plugin, Player viewer, BackupMetadata metadata, BackupContent content) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.metadata = metadata;
        this.content = content;
        
        String title = i18n("backup.preview.title")
            .replace("{TIME}", metadata.getFormattedTime());
        
        this.inventory = Bukkit.createInventory(this, 54, title);
        updateInventory();
    }
    
    /**
     * Open preview GUI for a backup.
     * <p>
     * 打开备份的预览 GUI。
     */
    public static void open(UltiToolsPlugin plugin, Player viewer, BackupMetadata metadata, BackupService backupService) {
        BackupContent content = backupService.loadBackupContent(metadata);
        if (content == null) {
            viewer.sendMessage(plugin.i18n("backup.message.load_failed"));
            return;
        }

        BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
        viewer.openInventory(gui.getInventory());
    }
    
    /**
     * Update inventory contents.
     * <p>
     * 更新物品栏内容。
     */
    public void updateInventory() {
        inventory.clear();
        
        // Display items based on current view
        ItemStack[] items;
        switch (currentView) {
            case 0: // Inventory
                items = content.getInventoryItems();
                break;
            case 1: // Armor
                items = content.getArmorItems();
                break;
            case 2: // Enderchest
                items = content.getEnderchestItems();
                break;
            default:
                items = null;
        }
        
        if (items != null) {
            for (int i = 0; i < Math.min(items.length, 45); i++) {
                if (items[i] != null) {
                    inventory.setItem(i, items[i]);
                }
            }
        }
        
        // Add offhand item for armor view
        if (currentView == 1) {
            ItemStack offhand = content.getOffhandItemStack();
            if (offhand != null) {
                inventory.setItem(44, offhand);
            }
        }
        
        // Navigation row
        addNavigationRow();
    }
    
    /**
     * Add navigation row.
     * <p>
     * 添加导航行。
     */
    private void addNavigationRow() {
        // Fill bottom row with gray glass
        ItemStack filler = XVersionUtils.getColoredPlaneGlass(Colors.GRAY);
        setItemName(filler, " ");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
        
        // Inventory tab (slot 45)
        ItemStack invTab = currentView == 0 
            ? XVersionUtils.getColoredPlaneGlass(Colors.LIME)
            : XVersionUtils.getColoredPlaneGlass(Colors.WHITE);
        setItemName(invTab, i18n("backup.preview.tab_inventory"));
        inventory.setItem(45, invTab);
        
        // Armor tab (slot 46)
        ItemStack armorTab = currentView == 1 
            ? XVersionUtils.getColoredPlaneGlass(Colors.LIME)
            : XVersionUtils.getColoredPlaneGlass(Colors.WHITE);
        setItemName(armorTab, i18n("backup.preview.tab_armor"));
        inventory.setItem(46, armorTab);
        
        // Enderchest tab (slot 47)
        ItemStack enderTab = currentView == 2 
            ? XVersionUtils.getColoredPlaneGlass(Colors.LIME)
            : XVersionUtils.getColoredPlaneGlass(Colors.WHITE);
        setItemName(enderTab, i18n("backup.preview.tab_enderchest"));
        inventory.setItem(47, enderTab);
        
        // Info panel (slot 49)
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(ChatColor.GOLD + i18n("backup.preview.info"));
            List<String> lore = new ArrayList<>();
            lore.add(i18n("backup.preview.info_time").replace("{TIME}", metadata.getFormattedTime()));
            lore.add(i18n("backup.preview.info_reason").replace("{REASON}", metadata.getReasonDisplay()));
            lore.add(i18n("backup.preview.info_level").replace("{LEVEL}", String.valueOf(content.getExpLevel())));
            lore.add(i18n("backup.preview.info_world").replace("{WORLD}", metadata.getWorldName()));
            infoMeta.setLore(lore);
            info.setItemMeta(infoMeta);
        }
        inventory.setItem(49, info);
        
        // Close button (slot 53)
        ItemStack closeBtn = XVersionUtils.getColoredPlaneGlass(Colors.RED);
        setItemName(closeBtn, i18n("backup.preview.close"));
        inventory.setItem(53, closeBtn);
    }
    
    /**
     * Set item display name.
     */
    private void setItemName(ItemStack item, String name) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
    }
    
    /**
     * Handle tab click.
     * <p>
     * 处理标签页点击。
     */
    public void handleTabClick(int slot) {
        if (slot == 45 && currentView != 0) {
            currentView = 0;
            updateInventory();
        } else if (slot == 46 && currentView != 1) {
            currentView = 1;
            updateInventory();
        } else if (slot == 47 && currentView != 2) {
            currentView = 2;
            updateInventory();
        } else if (slot == 53) {
            viewer.closeInventory();
        }
    }
    
    /**
     * Check if slot is a tab button.
     * <p>
     * 检查槽位是否是标签页按钮。
     */
    public boolean isTabSlot(int slot) {
        return slot == 45 || slot == 46 || slot == 47 || slot == 53;
    }
    
    public Player getViewer() {
        return viewer;
    }
    
    public BackupMetadata getMetadata() {
        return metadata;
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
