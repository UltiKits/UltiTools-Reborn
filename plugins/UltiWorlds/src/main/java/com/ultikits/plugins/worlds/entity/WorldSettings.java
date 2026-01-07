package com.ultikits.plugins.worlds.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * World settings data entity.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("world_settings")
public class WorldSettings extends AbstractDataEntity {
    
    @Column("world_name")
    private String worldName;
    
    @Column("display_name")
    private String displayName;
    
    @Column("description")
    private String description;
    
    @Column("icon")
    private String icon;  // Material name
    
    @Column("pvp_enabled")
    private boolean pvpEnabled;
    
    @Column("monsters_enabled")
    private boolean monstersEnabled;
    
    @Column("animals_enabled")
    private boolean animalsEnabled;
    
    @Column("weather_enabled")
    private boolean weatherEnabled;
    
    @Column("hidden")
    private boolean hidden;
    
    @Column("locked")
    private boolean locked;
    
    @Column(value = "spawn_x", type = "DOUBLE")
    private double spawnX;
    
    @Column(value = "spawn_y", type = "DOUBLE")
    private double spawnY;
    
    @Column(value = "spawn_z", type = "DOUBLE")
    private double spawnZ;
    
    @Column(value = "spawn_yaw", type = "FLOAT")
    private float spawnYaw;
    
    @Column(value = "spawn_pitch", type = "FLOAT")
    private float spawnPitch;
    
    /**
     * Create default settings for a world.
     */
    public static WorldSettings createDefault(String worldName) {
        return WorldSettings.builder()
            .worldName(worldName)
            .displayName(worldName)
            .description("")
            .icon("GRASS_BLOCK")
            .pvpEnabled(true)
            .monstersEnabled(true)
            .animalsEnabled(true)
            .weatherEnabled(true)
            .hidden(false)
            .locked(false)
            .build();
    }
}
