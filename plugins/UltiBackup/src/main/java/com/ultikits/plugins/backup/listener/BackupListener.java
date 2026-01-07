package com.ultikits.plugins.backup.listener;

import com.ultikits.plugins.backup.entity.BackupData;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for backup events.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class BackupListener implements Listener {
    
    @Autowired
    private BackupService backupService;
    
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
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
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
                
                Player target = org.bukkit.Bukkit.getPlayer(gui.getTargetUuid());
                if (target != null) {
                    backupService.createBackup(target, "MANUAL");
                    player.sendMessage(backupService.getConfig().getBackupCreatedMessage().replace("&", "§"));
                    gui.refresh();
                } else {
                    player.sendMessage("§c目标玩家不在线！");
                }
            }
            return;
        }
        
        // Backup item clicks
        if (slot >= 0 && slot < 45) {
            BackupData backup = gui.getBackupAtSlot(slot);
            if (backup == null) return;
            
            if (event.isLeftClick()) {
                if (event.isShiftClick()) {
                    // Preview - TODO: implement preview GUI
                    player.sendMessage("§e预览功能暂未实现");
                } else {
                    // Restore
                    Player target = org.bukkit.Bukkit.getPlayer(gui.getTargetUuid());
                    if (target != null) {
                        if (backupService.restoreBackup(target, backup)) {
                            player.sendMessage(backupService.getConfig().getBackupRestoredMessage().replace("&", "§"));
                            player.closeInventory();
                        } else {
                            player.sendMessage("§c恢复备份失败！");
                        }
                    } else {
                        player.sendMessage("§c目标玩家不在线！");
                    }
                }
            } else if (event.isRightClick()) {
                // Delete
                if (player.hasPermission("ultibackup.delete") || 
                    player.hasPermission("ultibackup.admin")) {
                    backupService.deleteBackup(backup);
                    player.sendMessage(backupService.getConfig().getBackupDeletedMessage().replace("&", "§"));
                    gui.refresh();
                } else {
                    player.sendMessage("§c你没有权限删除备份！");
                }
            }
        }
    }
}
