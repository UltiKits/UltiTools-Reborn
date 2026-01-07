package com.ultikits.plugins.essentials.entity;

import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.BaseDataEntity;
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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_homes")
public class HomeData extends BaseDataEntity<UUID> {
    
    @Column("uuid")
    private UUID uuid;
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("name")
    private String name;
    
    @Column("world")
    private String world;
    
    @Column("x")
    private double x;
    
    @Column("y")
    private double y;
    
    @Column("z")
    private double z;
    
    @Column("yaw")
    private float yaw;
    
    @Column("pitch")
    private float pitch;
    
    @Column("created_at")
    private long createdAt;
    
    @Override
    public UUID getId() {
        return uuid;
    }
    
    @Override
    public void setId(UUID id) {
        this.uuid = id;
    }
}
