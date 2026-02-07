package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * Configuration for spawn point location.
 */
@Getter
@Setter
@ConfigEntity("config/spawn.yml")
public class SpawnConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "spawn.location.world", comment = "出生点世界名称")
    private String world = "world";

    @ConfigEntry(path = "spawn.location.x", comment = "X 坐标")
    private double x = 0.0;

    @ConfigEntry(path = "spawn.location.y", comment = "Y 坐标")
    private double y = 64.0;

    @ConfigEntry(path = "spawn.location.z", comment = "Z 坐标")
    private double z = 0.0;

    @ConfigEntry(path = "spawn.location.yaw", comment = "水平朝向")
    private double yaw = 0.0;

    @ConfigEntry(path = "spawn.location.pitch", comment = "垂直朝向")
    private double pitch = 0.0;

    @ConfigEntry(path = "spawn.teleport-on-first-join", comment = "首次加入时传送到出生点")
    private boolean teleportOnFirstJoin = true;

    @ConfigEntry(path = "spawn.teleport-on-respawn", comment = "重生时传送到出生点")
    private boolean teleportOnRespawn = true;

    public SpawnConfig() {
        super("config/spawn.yml");
    }

    /**
     * Gets the spawn location as a Bukkit Location object.
     *
     * @return the spawn location
     */
    public Location getSpawnLocation() {
        return new Location(
                Bukkit.getWorld(world),
                x, y, z, (float) yaw, (float) pitch
        );
    }

    /**
     * Sets the spawn location from a Bukkit Location object.
     *
     * @param location the location to set as spawn
     */
    public void setSpawnLocation(Location location) {
        if (location.getWorld() != null) {
            this.world = location.getWorld().getName();
        }
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }
}
