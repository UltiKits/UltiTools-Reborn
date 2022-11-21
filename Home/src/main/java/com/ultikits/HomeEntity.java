package com.ultikits;


import com.ultikits.ultitools.abstracts.DataEntity;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Date;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = false)
@Table("res/home")
public class HomeEntity extends DataEntity {
    private Long id = new Date().getTime();
    private UUID playerId;
    private String name;
    private WorldLocation location;

    public Location getHomeLocation() {
        return new Location(Bukkit.getWorld(location.getWorld()), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public String toString() {
        return "{"
                + "\"id\":"
                + id
                + ",\"playerId\":"
                + playerId
                + ",\"name\":\""
                + name + '\"'
                + ",\"location\":"
                + location
                + "}";
    }
}
