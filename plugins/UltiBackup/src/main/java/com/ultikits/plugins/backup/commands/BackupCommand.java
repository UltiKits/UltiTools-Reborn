package com.ultikits.plugins.backup.commands;

import com.ultikits.plugins.backup.entity.BackupData;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Backup command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"backup", "invbackup", "bk"},
    permission = "ultibackup.use",
    description = "背包备份系统"
)
public class BackupCommand extends AbstractCommendExecutor {
    
    private final BackupService backupService;
    
    public BackupCommand(BackupService backupService) {
        this.backupService = backupService;
    }
    
    @CmdMapping(format = "")
    public void openBackups(@CmdSender Player player) {
        BackupGUI gui = new BackupGUI(backupService, player, player.getUniqueId(), player.getName());
        player.openInventory(gui.getInventory());
    }
    
    @CmdMapping(format = "list")
    public void listBackups(@CmdSender Player player) {
        List<BackupData> backups = backupService.getBackups(player.getUniqueId());
        
        if (backups.isEmpty()) {
            player.sendMessage(backupService.getConfig().getNoBackupsMessage().replace("&", "§"));
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== 你的备份 ===");
        for (int i = 0; i < Math.min(5, backups.size()); i++) {
            BackupData backup = backups.get(i);
            player.sendMessage(ChatColor.YELLOW + (i + 1) + ". " + ChatColor.WHITE + 
                backup.getFormattedTime() + " " + backup.getReasonDisplay());
        }
        if (backups.size() > 5) {
            player.sendMessage(ChatColor.GRAY + "... 还有 " + (backups.size() - 5) + " 个备份");
        }
    }
    
    @CmdMapping(format = "create")
    public void createBackup(@CmdSender Player player) {
        if (!player.hasPermission("ultibackup.create")) {
            player.sendMessage(ChatColor.RED + "你没有权限创建备份！");
            return;
        }
        
        backupService.createBackup(player, "MANUAL");
        player.sendMessage(backupService.getConfig().getBackupCreatedMessage().replace("&", "§"));
    }
    
    @CmdMapping(format = "restore <number>")
    public void restoreBackup(@CmdSender Player player, @CmdParam("number") int number) {
        List<BackupData> backups = backupService.getBackups(player.getUniqueId());
        
        if (backups.isEmpty() || number < 1 || number > backups.size()) {
            player.sendMessage(ChatColor.RED + "无效的备份编号！");
            return;
        }
        
        BackupData backup = backups.get(number - 1);
        if (backupService.restoreBackup(player, backup)) {
            player.sendMessage(backupService.getConfig().getBackupRestoredMessage().replace("&", "§"));
        } else {
            player.sendMessage(ChatColor.RED + "恢复备份失败！");
        }
    }
    
    @CmdMapping(format = "admin <player>")
    public void adminBackups(@CmdSender Player sender, @CmdParam("player") String targetName) {
        if (!sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限查看其他玩家的备份！");
            return;
        }
        
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + "找不到玩家: " + targetName);
            return;
        }
        
        BackupGUI gui = new BackupGUI(backupService, sender, target.getUniqueId(), targetName);
        sender.openInventory(gui.getInventory());
    }
    
    @CmdMapping(format = "admin create <player>")
    public void adminCreateBackup(@CmdSender Player sender, @CmdParam("player") String targetName) {
        if (!sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限为其他玩家创建备份！");
            return;
        }
        
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不在线！");
            return;
        }
        
        backupService.createBackup(target, "ADMIN");
        sender.sendMessage(ChatColor.GREEN + "已为 " + targetName + " 创建备份！");
    }
    
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== UltiBackup 帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/backup" + ChatColor.WHITE + " - 打开备份GUI");
        player.sendMessage(ChatColor.YELLOW + "/backup list" + ChatColor.WHITE + " - 列出备份");
        player.sendMessage(ChatColor.YELLOW + "/backup create" + ChatColor.WHITE + " - 创建备份");
        player.sendMessage(ChatColor.YELLOW + "/backup restore <编号>" + ChatColor.WHITE + " - 恢复备份");
        if (player.hasPermission("ultibackup.admin")) {
            player.sendMessage(ChatColor.YELLOW + "/backup admin <玩家>" + ChatColor.WHITE + " - 查看玩家备份");
            player.sendMessage(ChatColor.YELLOW + "/backup admin create <玩家>" + ChatColor.WHITE + " - 为玩家创建备份");
        }
    }
}
