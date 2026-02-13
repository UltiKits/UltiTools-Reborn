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
 * Entity representing a kit claim record for tracking player kit usage.
 * <p>
 * 记录玩家领取礼包的实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_kit_claims")
public class KitClaimData extends BaseDataEntity<UUID> {

    /**
     * Unique identifier for this claim record.
     */
    @Column("uuid")
    private UUID uuid;
    
    /**
     * UUID of the player who claimed the kit.
     */
    @Column("player_uuid")
    private String playerUuid;
    
    /**
     * Name of the kit that was claimed.
     */
    @Column("kit_name")
    private String kitName;
    
    /**
     * Timestamp of the last claim.
     */
    @Column("last_claim")
    private long lastClaim;
    
    /**
     * Total number of times this kit was claimed by this player.
     */
    @Column("claim_count")
    private int claimCount;

    @Override
    public UUID getId() {
        return uuid;
    }

    @Override
    public void setId(UUID id) {
        this.uuid = id;
    }
}
