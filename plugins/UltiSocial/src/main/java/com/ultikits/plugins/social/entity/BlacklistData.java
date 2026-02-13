package com.ultikits.plugins.social.entity;

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
 * Blacklist data entity.
 * Stores blocked player relationships.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("blacklist")
public class BlacklistData extends BaseDataEntity<String> {
    
    /**
     * The UUID of the player who blocked
     */
    @Column("player_uuid")
    private String playerUuid;
    
    /**
     * The UUID of the blocked player
     */
    @Column("blocked_uuid")
    private String blockedUuid;
    
    /**
     * The name of the blocked player (for display)
     */
    @Column("blocked_name")
    private String blockedName;
    
    /**
     * The timestamp when the player was blocked
     */
    @Column("created_time")
    private long createdTime;
    
    /**
     * Optional reason for blocking
     */
    @Column("reason")
    private String reason;
    
    /**
     * Create a new blacklist entry.
     *
     * @param playerUuid UUID of the player who is blocking
     * @param blockedUuid UUID of the player being blocked
     * @param blockedName Name of the player being blocked
     * @return new BlacklistData instance
     */
    public static BlacklistData create(UUID playerUuid, UUID blockedUuid, String blockedName) {
        return create(playerUuid, blockedUuid, blockedName, null);
    }
    
    /**
     * Create a new blacklist entry with reason.
     *
     * @param playerUuid UUID of the player who is blocking
     * @param blockedUuid UUID of the player being blocked
     * @param blockedName Name of the player being blocked
     * @param reason Reason for blocking
     * @return new BlacklistData instance
     */
    public static BlacklistData create(UUID playerUuid, UUID blockedUuid, String blockedName, String reason) {
        return BlacklistData.builder()
            .playerUuid(playerUuid.toString())
            .blockedUuid(blockedUuid.toString())
            .blockedName(blockedName)
            .createdTime(System.currentTimeMillis())
            .reason(reason)
            .build();
    }
}
