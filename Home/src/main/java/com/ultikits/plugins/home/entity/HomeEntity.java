package com.ultikits.plugins.home.entity;

import com.alibaba.fastjson.annotation.JSONField;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.common.WorldLocation;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Date;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = false)
@Table("home")
public class HomeEntity extends AbstractDataEntity {
    @Column("id")
    private Long id = new Date().getTime();
    @Column("playerId")
    private UUID playerId;
    @Column("name")
    private String name;
    @Column(value = "location", type = "JSON")
    private WorldLocation location;

    @JSONField(serialize = false)
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
                + location.toString()
                + "}";
    }
}
