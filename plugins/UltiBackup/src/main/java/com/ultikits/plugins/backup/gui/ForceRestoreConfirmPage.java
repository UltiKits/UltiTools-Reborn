package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import mc.obliviate.inventory.Icon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Force restore confirmation page using BaseConfirmationPage.
 * Shows warning about corrupted backup and requires user confirmation.
 * <p>
 * 强制恢复确认页面，使用 BaseConfirmationPage。
 * 显示损坏备份的警告并要求用户确认。
 *
 * @author wisdomme
 * @version 2.0.0
 */
public class ForceRestoreConfirmPage extends BaseConfirmationPage {

    private final UltiToolsPlugin plugin;
    private final BackupMetadata metadata;
    private final BackupService backupService;
    private final Player target;

    public ForceRestoreConfirmPage(UltiToolsPlugin plugin, Player viewer, BackupMetadata metadata,
            BackupService backupService, Player target) {
        super(viewer, "force_restore_confirm",
            plugin.i18n("backup.confirm.title"), 3);
        this.plugin = plugin;
        this.metadata = metadata;
        this.backupService = backupService;
        this.target = target;
    }

    /**
     * Open the confirmation page.
     * <p>
     * 打开确认页面。
     */
    public static void open(UltiToolsPlugin plugin, Player viewer, BackupMetadata metadata, BackupService backupService) {
        Player target = Bukkit.getPlayer(java.util.UUID.fromString(metadata.getPlayerUuid()));
        if (target == null) {
            viewer.sendMessage(plugin.i18n("backup.message.player_offline")
                .replace("{PLAYER}", metadata.getPlayerName()));
            return;
        }

        new ForceRestoreConfirmPage(plugin, viewer, metadata, backupService, target).open();
    }
    
    @Override
    protected void setupDialogContent(InventoryOpenEvent event) {
        // Warning icon in the center
        ItemStack warningItem = new ItemStack(Material.BARRIER);
        ItemMeta meta = warningItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + 
                i18n("backup.confirm.warning_title"));
            
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.YELLOW + i18n("backup.confirm.warning_line1"));
            lore.add(ChatColor.YELLOW + i18n("backup.confirm.warning_line2"));
            lore.add("");
            lore.add(ChatColor.GRAY + i18n("backup.confirm.backup_info"));
            lore.add(ChatColor.WHITE + "  " + i18n("backup.confirm.backup_time")
                .replace("{TIME}", metadata.getFormattedTime()));
            lore.add(ChatColor.WHITE + "  " + i18n("backup.confirm.backup_player")
                .replace("{PLAYER}", metadata.getPlayerName()));
            lore.add("");
            lore.add(ChatColor.RED + i18n("backup.confirm.risk_warning"));
            
            meta.setLore(lore);
            warningItem.setItemMeta(meta);
        }
        
        // Place warning in center of first row
        addItem(4, new Icon(warningItem));
    }
    
    @Override
    protected void onConfirm(InventoryClickEvent event) {
        Player viewer = (Player) event.getWhoClicked();
        
        // Perform force restore
        BackupService.RestoreResult result = backupService.forceRestore(target, metadata);
        
        switch (result) {
            case SUCCESS:
                viewer.sendMessage(i18n("backup.message.force_restored"));
                if (!viewer.equals(target)) {
                    target.sendMessage(i18n("backup.message.restored_by_admin")
                        .replace("{ADMIN}", viewer.getName()));
                }
                plugin.getLogger().warn(
                    "Player " + viewer.getName() + " force-restored corrupted backup " + 
                    metadata.getId() + " to " + target.getName());
                break;
                
            case LOAD_FAILED:
                viewer.sendMessage(i18n("backup.message.load_failed"));
                break;
                
            case RESTORE_FAILED:
                viewer.sendMessage(i18n("backup.message.restore_failed"));
                break;
                
            default:
                viewer.sendMessage(i18n("backup.message.restore_failed"));
        }
    }
    
    @Override
    protected void onCancel(InventoryClickEvent event) {
        Player viewer = (Player) event.getWhoClicked();
        viewer.sendMessage(i18n("backup.message.restore_cancelled"));
    }
    
    @Override
    protected String getOkButtonName() {
        return i18n("backup.confirm.button_confirm");
    }
    
    @Override
    protected String getCancelButtonName() {
        return i18n("backup.confirm.button_cancel");
    }
    
    /**
     * Get i18n message.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
