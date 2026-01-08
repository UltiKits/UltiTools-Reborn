package com.ultikits.plugins.essentials.entity;

import com.ultikits.plugins.essentials.entity.base.LocationDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

import java.util.UUID;

/**
 * Entity representing a player's home location.
 * <p>
 * 表示玩家家位置的实体。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_homes")
public class HomeData extends LocationDataEntity {
    
    @Column("uuid")
    private UUID uuid;
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("name")
    private String name;
    
    @Column("created_at")
    private long createdAt;
    
    @Builder
    public HomeData(UUID uuid, String playerUuid, String name, String world,
                    double x, double y, double z, float yaw, float pitch, long createdAt) {
        super(world, x, y, z, yaw, pitch);
        this.uuid = uuid;
        this.playerUuid = playerUuid;
        this.name = name;
        this.createdAt = createdAt;
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
