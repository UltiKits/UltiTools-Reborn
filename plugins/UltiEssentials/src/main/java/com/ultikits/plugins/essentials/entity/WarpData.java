package com.ultikits.plugins.essentials.entity;

import com.ultikits.plugins.essentials.entity.base.LocationDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

import java.util.UUID;

/**
 * Entity representing a server warp point.
 * <p>
 * 表示服务器地标点的实体。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_warps")
public class WarpData extends LocationDataEntity {
    
    @Column("uuid")
    private UUID uuid;
    
    @Column("name")
    private String name;
    
    @Column("permission")
    private String permission;  // Optional permission to use this warp
    
    @Column("created_by")
    private String createdBy;  // UUID of the player who created this warp
    
    @Column("created_at")
    private long createdAt;
    
    @Builder
    public WarpData(UUID uuid, String name, String world, double x, double y, double z,
                    float yaw, float pitch, String permission, String createdBy, long createdAt) {
        super(world, x, y, z, yaw, pitch);
        this.uuid = uuid;
        this.name = name;
        this.permission = permission;
        this.createdBy = createdBy;
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
