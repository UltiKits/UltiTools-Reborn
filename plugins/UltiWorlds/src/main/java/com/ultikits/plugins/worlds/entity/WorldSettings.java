package com.ultikits.plugins.worlds.entity;

import java.util.ArrayList;
import java.util.List;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * World settings data entity.
 * Stores per-world configuration including protection, spawn, and visibility settings.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("world_settings")
public class WorldSettings extends BaseDataEntity<Integer> {
    
    @Column("world_name")
    private String worldName;
    
    @Column("display_name")
    private String displayName;
    
    @Column("description")
    private String description;
    
    @Column("icon")
    private String icon;  // Material name
    
    // === World Rules ===
    
    @Column("pvp_enabled")
    private boolean pvpEnabled;
    
    @Column("monsters_enabled")
    private boolean monstersEnabled;
    
    @Column("animals_enabled")
    private boolean animalsEnabled;
    
    @Column("weather_enabled")
    private boolean weatherEnabled;

    // === World Difficulty ===

    @Column("difficulty")
    private String difficulty;  // PEACEFUL, EASY, NORMAL, HARD, or null (use Bukkit default)

    // === Post-Teleport Commands ===

    @Column("post_teleport_commands")
    private String postTeleportCommands;  // Newline-separated commands, or null

    // === Access Control ===
    
    @Column("hidden")
    private boolean hidden;
    
    @Column("locked")
    private boolean locked;
    
    @Column("blocked")
    private boolean blocked;  // Players cannot enter this world
    
    @Column("auto_unload")
    @Builder.Default
    private boolean autoUnload = true;  // Allow auto-unload when empty
    
    // === World Protection ===
    
    @Column("protect_break")
    private boolean protectBreak;  // Prevent block breaking
    
    @Column("protect_place")
    private boolean protectPlace;  // Prevent block placing
    
    @Column("protect_interact")
    private boolean protectInteract;  // Prevent interaction
    
    @Column("protect_explosion")
    private boolean protectExplosion;  // Prevent explosions
    
    // === Spawn Location ===
    
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
    
    // === Timestamps ===
    
    @Column(value = "created_at", type = "BIGINT")
    private long createdAt;
    
    /**
     * Create default settings for a world.
     *
     * @param worldName the world name
     * @return default world settings
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
            .difficulty(null)
            .postTeleportCommands(null)
            .hidden(false)
            .locked(false)
            .blocked(false)
            .autoUnload(true)
            .protectBreak(false)
            .protectPlace(false)
            .protectInteract(false)
            .protectExplosion(false)
            .build();
    }
    
    /**
     * Check if world has any protection enabled.
     *
     * @return true if any protection is enabled
     */
    public boolean hasProtection() {
        return protectBreak || protectPlace || protectInteract || protectExplosion;
    }
    
    /**
     * Enable all protection settings.
     */
    public void enableFullProtection() {
        this.protectBreak = true;
        this.protectPlace = true;
        this.protectInteract = true;
        this.protectExplosion = true;
    }
    
    /**
     * Disable all protection settings.
     */
    public void disableAllProtection() {
        this.protectBreak = false;
        this.protectPlace = false;
        this.protectInteract = false;
        this.protectExplosion = false;
    }
    
    @Override
    public void onCreate() {
        this.createdAt = System.currentTimeMillis();
    }
    
    @Override
    public boolean validate() {
        return worldName != null && !worldName.isEmpty();
    }
    
    @Override
    public List<String> getValidationErrors() {
        List<String> errors = new ArrayList<>();
        if (worldName == null || worldName.isEmpty()) {
            errors.add("World name cannot be empty");
        }
        return errors;
    }
}
