package com.ultikits.plugins.backup.entity;

import com.ultikits.plugins.backup.UltiBackup;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.utils.FileUtils;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Backup metadata entity (hot data).
 * Stores only metadata, actual backup content is stored in YAML files.
 * <p>
 * 备份元数据实体（热数据）。
 * 仅存储元数据，实际备份内容存储在 YAML 文件中。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("backup_metadata")
public class BackupMetadata extends BaseDataEntity<String> {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("player_name")
    private String playerName;
    
    @Column("backup_time")
    private long backupTime;
    
    @Column("backup_reason")
    private String backupReason;
    
    @Column("file_path")
    private String filePath;
    
    @Column("checksum")
    private String checksum;
    
    @Column("world_name")
    private String worldName;
    
    @Column(value = "location_x", type = "DOUBLE")
    private double locationX;
    
    @Column(value = "location_y", type = "DOUBLE")
    private double locationY;
    
    @Column(value = "location_z", type = "DOUBLE")
    private double locationZ;
    
    @Column("exp_level")
    private int expLevel;
    
    /**
     * Lifecycle hook: Delete associated cold data file when metadata is deleted.
     * <p>
     * 生命周期钩子：当元数据被删除时，删除关联的冷数据文件。
     */
    @Override
    public void onDelete() {
        File backupFile = getBackupFile();
        if (backupFile != null && backupFile.exists()) {
            if (backupFile.delete()) {
                UltiBackup.getInstance().getLogger().info("Deleted backup file: " + filePath);
            } else {
                UltiBackup.getInstance().getLogger().warn("Failed to delete backup file: " + filePath);
            }
        }
    }
    
    /**
     * Get the backup file.
     * <p>
     * 获取备份文件。
     *
     * @return the backup file, or null if file path is not set
     */
    public File getBackupFile() {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        return new File(UltiTools.getInstance().getDataFolder(), filePath);
    }
    
    /**
     * Generate file path for this backup.
     * Format: backups/{playerUuid}_{timestamp}.yml
     * <p>
     * 为此备份生成文件路径。
     * 格式：backups/{playerUuid}_{timestamp}.yml
     *
     * @return the generated file path
     */
    public String generateFilePath() {
        return "backups/" + playerUuid + "_" + backupTime + ".yml";
    }
    
    /**
     * Get formatted backup time string.
     * <p>
     * 获取格式化的备份时间字符串。
     *
     * @return formatted time string
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(backupTime));
    }
    
    /**
     * Get backup reason display text with i18n support.
     * <p>
     * 获取备份原因显示文本（支持 i18n）。
     *
     * @return reason display text
     */
    public String getReasonDisplay() {
        UltiBackup plugin = UltiBackup.getInstance();
        switch (backupReason) {
            case "DEATH": return plugin.i18n("backup.reason.death");
            case "QUIT": return plugin.i18n("backup.reason.quit");
            case "AUTO": return plugin.i18n("backup.reason.auto");
            case "MANUAL": return plugin.i18n("backup.reason.manual");
            case "ADMIN": return plugin.i18n("backup.reason.admin");
            default: return plugin.i18n("backup.reason.unknown");
        }
    }
    
    /**
     * Create metadata from player.
     * <p>
     * 从玩家创建元数据。
     *
     * @param player the player
     * @param reason the backup reason
     * @return the backup metadata
     */
    public static BackupMetadata fromPlayer(org.bukkit.entity.Player player, String reason) {
        long timestamp = System.currentTimeMillis();
        String uuid = player.getUniqueId().toString();
        
        BackupMetadata metadata = BackupMetadata.builder()
            .playerUuid(uuid)
            .playerName(player.getName())
            .backupTime(timestamp)
            .backupReason(reason)
            .worldName(player.getWorld().getName())
            .locationX(player.getLocation().getX())
            .locationY(player.getLocation().getY())
            .locationZ(player.getLocation().getZ())
            .expLevel(player.getLevel())
            .build();
        
        // Generate file path
        metadata.setFilePath(metadata.generateFilePath());
        
        return metadata;
    }
}
