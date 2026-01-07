package com.ultikits.plugins.backup.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

/**
 * Inventory backup data entity.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("inventory_backups")
public class BackupData extends AbstractDataEntity {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("player_name")
    private String playerName;
    
    @Column("backup_time")
    private long backupTime;
    
    @Column("backup_reason")
    private String backupReason;
    
    @Column(value = "inventory_contents", type = "TEXT")
    private String inventoryContents;
    
    @Column(value = "armor_contents", type = "TEXT")
    private String armorContents;
    
    @Column(value = "offhand_item", type = "TEXT")
    private String offhandItem;
    
    @Column(value = "enderchest_contents", type = "TEXT")
    private String enderchestContents;
    
    @Column("exp_level")
    private int expLevel;
    
    @Column(value = "exp_progress", type = "FLOAT")
    private float expProgress;
    
    @Column("world_name")
    private String worldName;
    
    @Column(value = "location_x", type = "DOUBLE")
    private double locationX;
    
    @Column(value = "location_y", type = "DOUBLE")
    private double locationY;
    
    @Column(value = "location_z", type = "DOUBLE")
    private double locationZ;
    
    /**
     * Get formatted backup time string.
     */
    public String getFormattedTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new java.util.Date(backupTime));
    }
    
    /**
     * Get backup reason display text.
     */
    public String getReasonDisplay() {
        switch (backupReason) {
            case "DEATH": return "§c死亡备份";
            case "QUIT": return "§e退出备份";
            case "AUTO": return "§a自动备份";
            case "MANUAL": return "§b手动备份";
            case "ADMIN": return "§d管理员备份";
            default: return "§7未知";
        }
    }
    
    /**
     * Create backup from player.
     */
    public static BackupData fromPlayer(org.bukkit.entity.Player player, String reason) {
        return BackupData.builder()
            .playerUuid(player.getUniqueId().toString())
            .playerName(player.getName())
            .backupTime(System.currentTimeMillis())
            .backupReason(reason)
            .expLevel(player.getLevel())
            .expProgress(player.getExp())
            .worldName(player.getWorld().getName())
            .locationX(player.getLocation().getX())
            .locationY(player.getLocation().getY())
            .locationZ(player.getLocation().getZ())
            .build();
    }
}
