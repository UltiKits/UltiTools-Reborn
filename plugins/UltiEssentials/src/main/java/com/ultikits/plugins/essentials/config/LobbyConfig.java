package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * Configuration for lobby/hub location.
 */
@Getter
@Setter
@ConfigEntity("config/lobby.yml")
public class LobbyConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "lobby.location.world", comment = "主城世界名称")
    private String world = "world";

    @ConfigEntry(path = "lobby.location.x", comment = "X 坐标")
    private double x = 0.0;

    @ConfigEntry(path = "lobby.location.y", comment = "Y 坐标")
    private double y = 64.0;

    @ConfigEntry(path = "lobby.location.z", comment = "Z 坐标")
    private double z = 0.0;

    @ConfigEntry(path = "lobby.location.yaw", comment = "水平朝向")
    private double yaw = 0.0;

    @ConfigEntry(path = "lobby.location.pitch", comment = "垂直朝向")
    private double pitch = 0.0;

    public LobbyConfig() {
        super("config/lobby.yml");
    }

    /**
     * Gets the lobby location as a Bukkit Location object.
     *
     * @return the lobby location
     */
    public Location getLobbyLocation() {
        return new Location(
                Bukkit.getWorld(world),
                x, y, z, (float) yaw, (float) pitch
        );
    }

    /**
     * Sets the lobby location from a Bukkit Location object.
     *
     * @param location the location to set as lobby
     */
    public void setLobbyLocation(Location location) {
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
