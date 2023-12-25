package com.ultikits.ultitools.entities.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;

import java.io.Serializable;

@Data
@NoArgsConstructor
public class WorldLocation implements Serializable {
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public WorldLocation(Location location) {
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }

    public WorldLocation(String world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public String toString() {
        return "{"
                + "\"world\":\""
                + world + '\"'
                + ",\"x\":"
                + x
                + ",\"y\":"
                + y
                + ",\"z\":"
                + z
                + ",\"yaw\":"
                + yaw
                + ",\"pitch\":"
                + pitch
                + "}";
    }
}
