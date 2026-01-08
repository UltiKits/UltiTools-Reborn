package com.ultikits.plugins.backup.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.ConfigEntity;

import lombok.Getter;
import lombok.Setter;

/**
 * Backup configuration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/backup.yml")
public class BackupConfig extends AbstractConfigEntity {
    
    public BackupConfig(String configFilePath) {
        super(configFilePath);
    }
    
    @ConfigEntry(path = "auto_backup.enabled", comment = "Enable automatic backups")
    private boolean autoBackupEnabled = true;
    
    @ConfigEntry(path = "auto_backup.interval", comment = "Auto backup interval in minutes")
    private int autoBackupInterval = 30;
    
    @ConfigEntry(path = "auto_backup.on_death", comment = "Backup inventory on player death")
    private boolean backupOnDeath = true;
    
    @ConfigEntry(path = "auto_backup.on_quit", comment = "Backup inventory when player quits")
    private boolean backupOnQuit = true;
    
    @ConfigEntry(path = "max_backups_per_player", comment = "Maximum number of backups to keep per player")
    private int maxBackupsPerPlayer = 10;
    
    @ConfigEntry(path = "backup_armor", comment = "Include armor in backups")
    private boolean backupArmor = true;
    
    @ConfigEntry(path = "backup_enderchest", comment = "Include ender chest in backups")
    private boolean backupEnderchest = true;
    
    @ConfigEntry(path = "backup_exp", comment = "Include experience levels in backups")
    private boolean backupExp = true;
    
    @ConfigEntry(path = "gui_title", comment = "Title of the backup selection GUI")
    private String guiTitle = "&6背包备份 &7- {PLAYER}";
    
    @ConfigEntry(path = "messages.backup_created", comment = "Backup created message")
    private String backupCreatedMessage = "&a已创建背包备份！";
    
    @ConfigEntry(path = "messages.backup_restored", comment = "Backup restored message")
    private String backupRestoredMessage = "&a已恢复背包备份！";
    
    @ConfigEntry(path = "messages.no_backups", comment = "No backups available message")
    private String noBackupsMessage = "&c没有可用的备份！";
    
    @ConfigEntry(path = "messages.backup_deleted", comment = "Backup deleted message")
    private String backupDeletedMessage = "&a备份已删除！";
}
