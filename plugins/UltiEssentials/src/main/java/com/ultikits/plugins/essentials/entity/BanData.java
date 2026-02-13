package com.ultikits.plugins.essentials.entity;

import java.util.UUID;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Entity representing a player ban record.
 * <p>
 * 表示玩家封禁记录的实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_bans")
public class BanData extends BaseDataEntity<UUID> {

    /**
     * Unique identifier for this ban record.
     */
    @Column("uuid")
    private UUID uuid;
    
    /**
     * UUID of the banned player.
     */
    @Column("player_uuid")
    private String playerUuid;
    
    /**
     * Name of the banned player (for display purposes).
     */
    @Column("player_name")
    private String playerName;
    
    /**
     * Reason for the ban.
     */
    @Column("reason")
    private String reason;
    
    /**
     * UUID of the operator who issued the ban.
     * Null if banned by console.
     */
    @Column("banned_by")
    private String bannedBy;
    
    /**
     * Name of the operator who issued the ban.
     */
    @Column("banned_by_name")
    private String bannedByName;
    
    /**
     * Timestamp when the ban was issued.
     */
    @Column("ban_time")
    private long banTime;
    
    /**
     * Timestamp when the ban expires.
     * -1 means permanent ban.
     */
    @Column("expire_time")
    private long expireTime;
    
    /**
     * Whether this ban is currently active.
     */
    @Column("active")
    private boolean active;
    
    /**
     * IP address banned (optional, for IP bans).
     */
    @Column("ip_address")
    private String ipAddress;
    
    /**
     * Checks if this is a permanent ban.
     */
    public boolean isPermanent() {
        return expireTime == -1;
    }
    
    /**
     * Checks if this ban has expired.
     */
    public boolean hasExpired() {
        if (isPermanent()) {
            return false;
        }
        return System.currentTimeMillis() > expireTime;
    }
    
    /**
     * Gets remaining time in milliseconds.
     * Returns -1 for permanent bans, 0 if expired.
     */
    public long getRemainingTime() {
        if (isPermanent()) {
            return -1;
        }
        long remaining = expireTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    @Override
    public UUID getId() {
        return uuid;
    }

    @Override
    public void setId(UUID id) {
        this.uuid = id;
    }
}
