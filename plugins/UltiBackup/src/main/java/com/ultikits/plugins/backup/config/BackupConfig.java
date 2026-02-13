package com.ultikits.plugins.backup.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Backup configuration entity.
 * <p>
 * 备份配置实体。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Getter
@Setter
@ConfigEntity("config/backup.yml")
public class BackupConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "auto_backup.enabled", comment = "Enable automatic backups")
    private boolean autoBackupEnabled = true;

    @Range(min = 1, max = 1440)
    @ConfigEntry(path = "auto_backup.interval", comment = "Auto backup interval in minutes (1-1440)")
    private int autoBackupInterval = 30;

    @ConfigEntry(path = "auto_backup.on_death", comment = "Backup inventory on player death")
    private boolean backupOnDeath = true;

    @ConfigEntry(path = "auto_backup.on_quit", comment = "Backup inventory when player quits")
    private boolean backupOnQuit = true;

    @Range(min = 1, max = 1000)
    @ConfigEntry(path = "max_backups_per_player", comment = "Maximum number of backups to keep per player (1-1000)")
    private int maxBackupsPerPlayer = 10;

    @ConfigEntry(path = "backup_armor", comment = "Include armor in backups")
    private boolean backupArmor = true;

    @ConfigEntry(path = "backup_enderchest", comment = "Include ender chest in backups")
    private boolean backupEnderchest = true;

    @ConfigEntry(path = "backup_exp", comment = "Include experience levels in backups")
    private boolean backupExp = true;

    public BackupConfig(String configFilePath) {
        super(configFilePath);
    }
}
