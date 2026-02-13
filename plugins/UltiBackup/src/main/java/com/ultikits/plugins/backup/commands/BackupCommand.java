package com.ultikits.plugins.backup.commands;

import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.gui.ForceRestoreConfirmPage;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Backup command executor.
 * Uses BaseCommandExecutor with @CmdCD, @RunAsync annotations.
 * <p>
 * 备份命令执行器。
 * 使用 BaseCommandExecutor，带有 @CmdCD、@RunAsync 注解。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"backup", "invbackup", "bk"},
    permission = "ultibackup.use",
    description = "backup.command.description"
)
public class BackupCommand extends BaseCommandExecutor {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private BackupService backupService;
    
    /**
     * Open backup GUI (default command).
     * <p>
     * 打开备份 GUI（默认命令）。
     */
    @CmdMapping(format = "")
    public void openBackups(@CmdSender Player player) {
        BackupGUI gui = new BackupGUI(plugin, backupService, player, player.getUniqueId(), player.getName());
        player.openInventory(gui.getInventory());
    }
    
    /**
     * List backups.
     * <p>
     * 列出备份。
     */
    @CmdMapping(format = "list")
    public void listBackups(@CmdSender Player player) {
        List<BackupMetadata> backups = backupService.getBackups(player.getUniqueId());
        
        if (backups.isEmpty()) {
            player.sendMessage(i18n("backup.message.no_backups"));
            return;
        }
        
        player.sendMessage(i18n("backup.message.list_header"));
        for (int i = 0; i < Math.min(5, backups.size()); i++) {
            BackupMetadata backup = backups.get(i);
            player.sendMessage(i18n("backup.message.list_item")
                .replace("{NUMBER}", String.valueOf(i + 1))
                .replace("{TIME}", backup.getFormattedTime())
                .replace("{REASON}", backup.getReasonDisplay()));
        }
        if (backups.size() > 5) {
            player.sendMessage(i18n("backup.message.list_more")
                .replace("{COUNT}", String.valueOf(backups.size() - 5)));
        }
    }
    
    /**
     * Create a manual backup.
     * <p>
     * 创建手动备份。
     */
    @CmdMapping(format = "create")
    @CmdCD(30)
    @RunAsync
    public void createBackup(@CmdSender Player player) {
        if (!player.hasPermission("ultibackup.create")) {
            player.sendMessage(i18n("backup.message.no_permission"));
            return;
        }
        
        BackupMetadata result = backupService.createBackup(player, "MANUAL");
        if (result != null) {
            player.sendMessage(i18n("backup.message.created"));
        } else {
            player.sendMessage(i18n("backup.message.create_failed"));
        }
    }
    
    /**
     * Restore a backup by number.
     * <p>
     * 按编号恢复备份。
     */
    @CmdMapping(format = "restore <number>")
    @CmdCD(30)
    public void restoreBackup(@CmdSender Player player, @CmdParam("number") int number) {
        List<BackupMetadata> backups = backupService.getBackups(player.getUniqueId());
        
        if (backups.isEmpty() || number < 1 || number > backups.size()) {
            player.sendMessage(i18n("backup.message.invalid_number"));
            return;
        }
        
        BackupMetadata backup = backups.get(number - 1);
        handleRestore(player, player, backup);
    }
    
    /**
     * Force restore a backup (skip checksum verification).
     * <p>
     * 强制恢复备份（跳过校验和验证）。
     */
    @CmdMapping(format = "restore <number> force")
    @CmdCD(30)
    public void forceRestoreBackup(@CmdSender Player player, @CmdParam("number") int number) {
        List<BackupMetadata> backups = backupService.getBackups(player.getUniqueId());
        
        if (backups.isEmpty() || number < 1 || number > backups.size()) {
            player.sendMessage(i18n("backup.message.invalid_number"));
            return;
        }
        
        BackupMetadata backup = backups.get(number - 1);
        
        // Open confirmation GUI
        ForceRestoreConfirmPage.open(plugin, player, backup, backupService);
    }
    
    /**
     * Save all online players' backups.
     * <p>
     * 保存所有在线玩家的备份。
     */
    @CmdMapping(format = "saveall")
    @CmdCD(60)
    @RunAsync
    public void saveAllPlayers(@CmdSender Player sender) {
        if (!sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(i18n("backup.message.no_permission"));
            return;
        }
        
        int count = backupService.saveAllOnlinePlayers();
        sender.sendMessage(i18n("backup.message.saveall_complete")
            .replace("{COUNT}", String.valueOf(count)));
    }
    
    /**
     * View another player's backups (admin).
     * <p>
     * 查看其他玩家的备份（管理员）。
     */
    @CmdMapping(format = "admin <player>")
    public void adminBackups(
            @CmdSender Player sender, 
            @CmdParam(value = "player", suggest = "suggestOnlinePlayers") String targetName) {
        if (!sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(i18n("backup.message.no_permission"));
            return;
        }
        
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(i18n("backup.message.player_not_found")
                .replace("{PLAYER}", targetName));
            return;
        }
        
        BackupGUI gui = new BackupGUI(plugin, backupService, sender, target.getUniqueId(), targetName);
        sender.openInventory(gui.getInventory());
    }
    
    /**
     * Create backup for another player (admin).
     * <p>
     * 为其他玩家创建备份（管理员）。
     */
    @CmdMapping(format = "admin create <player>")
    @CmdCD(30)
    @RunAsync
    public void adminCreateBackup(
            @CmdSender Player sender, 
            @CmdParam(value = "player", suggest = "suggestOnlinePlayers") String targetName) {
        if (!sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(i18n("backup.message.no_permission"));
            return;
        }
        
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(i18n("backup.message.player_offline")
                .replace("{PLAYER}", targetName));
            return;
        }
        
        BackupMetadata result = backupService.createBackup(target, "ADMIN");
        if (result != null) {
            sender.sendMessage(i18n("backup.message.admin_created")
                .replace("{PLAYER}", targetName));
        } else {
            sender.sendMessage(i18n("backup.message.create_failed"));
        }
    }
    
    /**
     * Show help message.
     * <p>
     * 显示帮助信息。
     */
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("backup.help.header"));
        sender.sendMessage(i18n("backup.help.open"));
        sender.sendMessage(i18n("backup.help.list"));
        sender.sendMessage(i18n("backup.help.create"));
        sender.sendMessage(i18n("backup.help.restore"));
        sender.sendMessage(i18n("backup.help.restore_force"));
        if (sender.hasPermission("ultibackup.admin")) {
            sender.sendMessage(i18n("backup.help.saveall"));
            sender.sendMessage(i18n("backup.help.admin"));
            sender.sendMessage(i18n("backup.help.admin_create"));
        }
    }
    
    /**
     * Handle restore operation with checksum verification.
     * <p>
     * 处理恢复操作（带校验和验证）。
     */
    private void handleRestore(Player sender, Player target, BackupMetadata backup) {
        BackupService.RestoreResult result = backupService.restoreBackup(target, backup);
        
        switch (result) {
            case SUCCESS:
                sender.sendMessage(i18n("backup.message.restored"));
                if (!sender.equals(target)) {
                    target.sendMessage(i18n("backup.message.restored_by_admin")
                        .replace("{ADMIN}", sender.getName()));
                }
                break;
                
            case CHECKSUM_FAILED:
                sender.sendMessage(i18n("backup.message.checksum_failed"));
                sender.sendMessage(i18n("backup.message.checksum_hint"));
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
     * Suggest online player names for tab completion.
     * <p>
     * 为 Tab 补全建议在线玩家名称。
     */
    public List<String> suggestOnlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .collect(Collectors.toList());
    }
    
    /**
     * Suggest subcommands for tab completion.
     * <p>
     * 为 Tab 补全建议子命令。
     */
    public List<String> suggestSubcommands() {
        return Arrays.asList("list", "create", "restore", "help", "admin", "saveall");
    }
    
    /**
     * Get i18n message.
     * <p>
     * 获取 i18n 消息。
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
