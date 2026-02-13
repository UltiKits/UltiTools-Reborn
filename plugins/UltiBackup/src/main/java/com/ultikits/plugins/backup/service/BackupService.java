package com.ultikits.plugins.backup.service;

import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupContent;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.ultikits.ultitools.annotations.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Service for inventory backup operations.
 * Implements cold/hot data separation:
 * - Hot data (metadata) stored via DataOperator
 * - Cold data (backup content) stored in YAML files
 * <p>
 * 背包备份操作服务。
 * 实现冷热数据分离：
 * - 热数据（元数据）通过 DataOperator 存储
 * - 冷数据（备份内容）存储在 YAML 文件中
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class BackupService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private BackupConfig config;

    private DataOperator<BackupMetadata> dataOperator;
    private File backupsDirectory;
    private Plugin bukkitPlugin;

    /**
     * Initialize the service.
     * <p>
     * 初始化服务。
     */
    @PostConstruct
    public void init() {
        this.dataOperator = plugin.getDataOperator(BackupMetadata.class);
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

        // Ensure backups directory exists
        this.backupsDirectory = new File(bukkitPlugin.getDataFolder(), "backups");
        if (!backupsDirectory.exists()) {
            backupsDirectory.mkdirs();
        }
    }
    
    /**
     * Create a backup for a player.
     * <p>
     * 为玩家创建备份。
     *
     * @param player the player
     * @param reason the backup reason
     * @return the backup metadata
     */
    public BackupMetadata createBackup(Player player, String reason) {
        // Create metadata
        BackupMetadata metadata = BackupMetadata.fromPlayer(player, reason);
        
        // Create content from player
        BackupContent content = BackupContent.fromPlayer(
            player,
            config.isBackupArmor(),
            config.isBackupEnderchest(),
            config.isBackupExp()
        );
        
        try {
            // Save cold data to file
            File backupFile = new File(bukkitPlugin.getDataFolder(), metadata.getFilePath());
            String checksum = content.saveToFile(backupFile);
            metadata.setChecksum(checksum);
            
            // Save metadata to database
            dataOperator.insert(metadata);
            
            // Clean up old backups
            cleanupOldBackups(UUID.fromString(player.getUniqueId().toString()));
            
            plugin.getLogger().info("Created backup for " + player.getName() + 
                ": " + metadata.getFilePath());
            
            return metadata;
        } catch (IOException e) {
            plugin.getLogger().error(e, 
                "Failed to create backup for " + player.getName());
            return null;
        }
    }
    
    /**
     * Get all backups for a player.
     * <p>
     * 获取玩家的所有备份。
     *
     * @param playerUuid the player UUID
     * @return list of backups sorted by time descending
     */
    public List<BackupMetadata> getBackups(UUID playerUuid) {
        List<BackupMetadata> backups = dataOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        // Sort by time descending
        backups.sort((a, b) -> Long.compare(b.getBackupTime(), a.getBackupTime()));

        return backups;
    }
    
    /**
     * Get a specific backup by ID.
     * <p>
     * 根据 ID 获取备份。
     *
     * @param id the backup ID
     * @return the backup metadata
     */
    public BackupMetadata getBackup(String id) {
        return dataOperator.getById(id);
    }
    
    /**
     * Verify backup checksum.
     * <p>
     * 验证备份校验和。
     *
     * @param metadata the backup metadata
     * @return true if checksum is valid
     */
    public boolean verifyChecksum(BackupMetadata metadata) {
        if (metadata == null || metadata.getFilePath() == null) {
            return false;
        }
        
        File backupFile = metadata.getBackupFile();
        if (backupFile == null || !backupFile.exists()) {
            return false;
        }
        
        try {
            return BackupContent.verifyChecksum(backupFile, metadata.getChecksum());
        } catch (IOException e) {
            plugin.getLogger().warn(e, 
                "Failed to verify checksum for backup: " + metadata.getId());
            return false;
        }
    }
    
    /**
     * Load backup content from file.
     * <p>
     * 从文件加载备份内容。
     *
     * @param metadata the backup metadata
     * @return the backup content, or null if load fails
     */
    public BackupContent loadBackupContent(BackupMetadata metadata) {
        if (metadata == null || metadata.getFilePath() == null) {
            return null;
        }
        
        File backupFile = metadata.getBackupFile();
        if (backupFile == null || !backupFile.exists()) {
            return null;
        }
        
        try {
            return BackupContent.loadFromFile(backupFile);
        } catch (IOException e) {
            plugin.getLogger().warn(e, 
                "Failed to load backup content: " + metadata.getId());
            return null;
        }
    }
    
    /**
     * Restore a backup to a player (with checksum verification).
     * <p>
     * 将备份恢复到玩家（带校验和验证）。
     *
     * @param player the player
     * @param metadata the backup metadata
     * @return RestoreResult indicating success, failure, or checksum error
     */
    public RestoreResult restoreBackup(Player player, BackupMetadata metadata) {
        if (metadata == null) {
            return RestoreResult.NOT_FOUND;
        }
        
        // Verify checksum first
        if (!verifyChecksum(metadata)) {
            return RestoreResult.CHECKSUM_FAILED;
        }
        
        // Load and restore content
        return forceRestore(player, metadata);
    }
    
    /**
     * Force restore a backup without checksum verification.
     * Use this when user confirms to restore a corrupted backup.
     * <p>
     * 强制恢复备份（跳过校验和验证）。
     * 当用户确认恢复损坏的备份时使用。
     *
     * @param player the player
     * @param metadata the backup metadata
     * @return RestoreResult indicating success or failure
     */
    public RestoreResult forceRestore(Player player, BackupMetadata metadata) {
        if (metadata == null) {
            return RestoreResult.NOT_FOUND;
        }
        
        BackupContent content = loadBackupContent(metadata);
        if (content == null) {
            return RestoreResult.LOAD_FAILED;
        }
        
        try {
            content.restoreToPlayer(
                player,
                config.isBackupArmor(),
                config.isBackupEnderchest(),
                config.isBackupExp()
            );
            
            plugin.getLogger().info("Restored backup " + metadata.getId() + 
                " to player " + player.getName());
            
            return RestoreResult.SUCCESS;
        } catch (Exception e) {
            plugin.getLogger().error(e, 
                "Failed to restore backup " + metadata.getId() + " to " + player.getName());
            return RestoreResult.RESTORE_FAILED;
        }
    }
    
    /**
     * Delete a backup.
     * <p>
     * 删除备份。
     *
     * @param metadata the backup metadata
     * @return true if deleted successfully
     */
    public boolean deleteBackup(BackupMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        
        // Trigger onDelete hook which will delete the cold data file
        metadata.onDelete();
        
        // Delete metadata from database
        dataOperator.delById(metadata.getId());
        
        return true;
    }
    
    /**
     * Delete a backup by ID.
     * <p>
     * 根据 ID 删除备份。
     *
     * @param id the backup ID
     * @return true if deleted successfully
     */
    public boolean deleteBackup(String id) {
        BackupMetadata metadata = dataOperator.getById(id);
        return deleteBackup(metadata);
    }
    
    /**
     * Save all online players' backups.
     * <p>
     * 保存所有在线玩家的备份。
     *
     * @return the number of backups created
     */
    public int saveAllOnlinePlayers() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultibackup.auto")) {
                BackupMetadata result = createBackup(player, "ADMIN");
                if (result != null) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Auto backup all online players.
     * Runs every 30 minutes (36000 ticks). Checks config.auto_backup.enabled before executing.
     * <p>
     * 自动备份所有在线玩家。
     * 每 30 分钟运行一次（36000 刻）。执行前检查 config.auto_backup.enabled。
     */
    @Scheduled(period = 36000, async = false)
    public void autoBackupAll() {
        // Check if auto backup is enabled
        if (!config.isAutoBackupEnabled()) {
            return;
        }

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultibackup.auto")) {
                BackupMetadata result = createBackup(player, "AUTO");
                if (result != null) {
                    count++;
                }
            }
        }
        if (count > 0) {
            plugin.getLogger().info("Auto backup completed: " + count + " players");
        }
    }
    
    /**
     * Clean up old backups for a player.
     * <p>
     * 清理玩家的旧备份。
     *
     * @param playerUuid the player UUID
     */
    private void cleanupOldBackups(UUID playerUuid) {
        List<BackupMetadata> backups = getBackups(playerUuid);
        
        if (backups.size() > config.getMaxBackupsPerPlayer()) {
            // Remove oldest backups
            for (int i = config.getMaxBackupsPerPlayer(); i < backups.size(); i++) {
                deleteBackup(backups.get(i));
            }
        }
    }
    
    /**
     * Get the config.
     * <p>
     * 获取配置。
     *
     * @return the backup config
     */
    public BackupConfig getConfig() {
        return config;
    }
    
    /**
     * Get the backups directory.
     * <p>
     * 获取备份目录。
     *
     * @return the backups directory
     */
    public File getBackupsDirectory() {
        return backupsDirectory;
    }
    
    /**
     * Restore result enum.
     * <p>
     * 恢复结果枚举。
     */
    public enum RestoreResult {
        /** Restore successful */
        SUCCESS,
        /** Backup not found */
        NOT_FOUND,
        /** Checksum verification failed */
        CHECKSUM_FAILED,
        /** Failed to load backup content */
        LOAD_FAILED,
        /** Failed to restore to player */
        RESTORE_FAILED
    }
}
