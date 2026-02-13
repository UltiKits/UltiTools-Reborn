package com.ultikits.plugins.backup.listener;

import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.gui.BackupPreviewGUI;
import com.ultikits.plugins.backup.gui.ForceRestoreConfirmPage;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for backup events.
 * Handles death/quit auto-backup and GUI interactions.
 * <p>
 * 备份事件监听器。
 * 处理死亡/退出自动备份和 GUI 交互。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@EventListener
public class BackupListener implements Listener {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private BackupService backupService;
    
    /**
     * Handle player death - create backup if enabled.
     * <p>
     * 处理玩家死亡 - 如果启用则创建备份。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        if (!backupService.getConfig().isBackupOnDeath()) {
            return;
        }
        
        if (!player.hasPermission("ultibackup.auto")) {
            return;
        }
        
        // Create backup before death drops
        backupService.createBackup(player, "DEATH");
    }
    
    /**
     * Handle player quit - create backup if enabled.
     * <p>
     * 处理玩家退出 - 如果启用则创建备份。
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        if (!backupService.getConfig().isBackupOnQuit()) {
            return;
        }
        
        if (!player.hasPermission("ultibackup.auto")) {
            return;
        }
        
        backupService.createBackup(player, "QUIT");
    }
    
    /**
     * Handle BackupGUI clicks.
     * <p>
     * 处理备份 GUI 点击。
     */
    @EventHandler
    public void onBackupGUIClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackupGUI)) {
            return;
        }
        
        event.setCancelled(true);
        
        BackupGUI gui = (BackupGUI) event.getInventory().getHolder();
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        
        // Navigation buttons
        if (slot == 45) { // Previous page
            gui.previousPage();
            return;
        }
        if (slot == 53) { // Next page
            gui.nextPage();
            return;
        }
        if (slot == 47) { // Create new backup
            if (player.hasPermission("ultibackup.admin") || 
                player.getUniqueId().equals(gui.getTargetUuid())) {
                
                Player target = Bukkit.getPlayer(gui.getTargetUuid());
                if (target != null) {
                    BackupMetadata result = backupService.createBackup(target, "MANUAL");
                    if (result != null) {
                        player.sendMessage(i18n("backup.message.created"));
                        gui.refresh();
                    } else {
                        player.sendMessage(i18n("backup.message.create_failed"));
                    }
                } else {
                    player.sendMessage(i18n("backup.message.player_offline")
                        .replace("{PLAYER}", gui.getTargetName()));
                }
            }
            return;
        }
        
        // Backup item clicks
        if (slot >= 0 && slot < 45) {
            BackupMetadata backup = gui.getBackupAtSlot(slot);
            if (backup == null) return;
            
            if (event.isLeftClick()) {
                if (event.isShiftClick()) {
                    // Preview backup content
                    BackupPreviewGUI.open(plugin, player, backup, backupService);
                } else {
                    // Restore backup
                    handleRestore(player, gui.getTargetUuid(), backup);
                }
            } else if (event.isRightClick()) {
                // Delete backup
                handleDelete(player, backup, gui);
            }
        }
    }
    
    /**
     * Handle BackupPreviewGUI clicks.
     * <p>
     * 处理备份预览 GUI 点击。
     */
    @EventHandler
    public void onPreviewGUIClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackupPreviewGUI)) {
            return;
        }
        
        event.setCancelled(true);
        
        BackupPreviewGUI gui = (BackupPreviewGUI) event.getInventory().getHolder();
        int slot = event.getRawSlot();
        
        // Handle tab clicks
        if (gui.isTabSlot(slot)) {
            gui.handleTabClick(slot);
        }
    }
    
    /**
     * Handle restore operation.
     * <p>
     * 处理恢复操作。
     */
    private void handleRestore(Player sender, java.util.UUID targetUuid, BackupMetadata backup) {
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            sender.sendMessage(i18n("backup.message.player_offline")
                .replace("{PLAYER}", backup.getPlayerName()));
            return;
        }
        
        BackupService.RestoreResult result = backupService.restoreBackup(target, backup);
        
        switch (result) {
            case SUCCESS:
                sender.sendMessage(i18n("backup.message.restored"));
                if (!sender.equals(target)) {
                    target.sendMessage(i18n("backup.message.restored_by_admin")
                        .replace("{ADMIN}", sender.getName()));
                }
                sender.closeInventory();
                break;
                
            case CHECKSUM_FAILED:
                sender.sendMessage(i18n("backup.message.checksum_failed"));
                sender.sendMessage(i18n("backup.message.checksum_hint"));
                // Open force restore confirmation
                sender.closeInventory();
                ForceRestoreConfirmPage.open(plugin, sender, backup, backupService);
                break;
                
            case NOT_FOUND:
                sender.sendMessage(i18n("backup.message.not_found"));
                break;
                
            case LOAD_FAILED:
                sender.sendMessage(i18n("backup.message.load_failed"));
                break;
                
            case RESTORE_FAILED:
                sender.sendMessage(i18n("backup.message.restore_failed"));
                break;
        }
    }
    
    /**
     * Handle delete operation.
     * <p>
     * 处理删除操作。
     */
    private void handleDelete(Player player, BackupMetadata backup, BackupGUI gui) {
        if (player.hasPermission("ultibackup.delete") || 
            player.hasPermission("ultibackup.admin")) {
            backupService.deleteBackup(backup);
            player.sendMessage(i18n("backup.message.deleted"));
            gui.refresh();
        } else {
            player.sendMessage(i18n("backup.message.no_permission"));
        }
    }
    
    /**
     * Get i18n message.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
