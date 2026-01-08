package com.ultikits.plugins.essentials.entity.base;

import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Base entity for data that contains location information.
 * <p>
 * 包含位置信息的数据实体基类。
 * 提取自 HomeData 和 WarpData 的公共位置字段。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public abstract class LocationDataEntity extends BaseDataEntity<UUID> {
    
    @Column("world")
    protected String world;
    
    @Column("x")
    protected double x;
    
    @Column("y")
    protected double y;
    
    @Column("z")
    protected double z;
    
    @Column("yaw")
    protected float yaw;
    
    @Column("pitch")
    protected float pitch;
    
    /**
     * Creates a LocationDataEntity with location fields.
     *
     * @param world the world name
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @param yaw   the yaw angle
     * @param pitch the pitch angle
     */
    protected LocationDataEntity(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }
    
    /**
     * Converts this entity to a Bukkit Location.
     *
     * @return the Location, or null if the world doesn't exist
     */
    @Nullable
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x, y, z, yaw, pitch);
    }
    
    /**
     * Updates this entity's location fields from a Bukkit Location.
     *
     * @param location the source location
     */
    public void fromLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }
    
    /**
     * Checks if the world exists.
     *
     * @return true if the world exists
     */
    public boolean isWorldValid() {
        return Bukkit.getWorld(world) != null;
    }
}
