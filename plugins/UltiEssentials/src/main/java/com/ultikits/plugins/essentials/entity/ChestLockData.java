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
 * Entity representing a chest lock.
 * <p>
 * 表示箱子锁的实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_chest_locks")
public class ChestLockData extends BaseDataEntity<UUID> {

    /**
     * Unique identifier for this lock.
     */
    @Column("uuid")
    private UUID uuid;
    
    /**
     * World name where the chest is located.
     */
    @Column("world")
    private String world;
    
    /**
     * X coordinate.
     */
    @Column("x")
    private int x;
    
    /**
     * Y coordinate.
     */
    @Column("y")
    private int y;
    
    /**
     * Z coordinate.
     */
    @Column("z")
    private int z;
    
    /**
     * UUID of the player who owns this lock.
     */
    @Column("owner_uuid")
    private String ownerUuid;
    
    /**
     * Name of the owner (for display).
     */
    @Column("owner_name")
    private String ownerName;
    
    /**
     * Timestamp when the lock was created.
     */
    @Column("created_at")
    private long createdAt;
    
    /**
     * Creates a location key for quick lookup.
     */
    public String getLocationKey() {
        return world + ":" + x + ":" + y + ":" + z;
    }
    
    /**
     * Static method to create location key.
     */
    public static String createLocationKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
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
