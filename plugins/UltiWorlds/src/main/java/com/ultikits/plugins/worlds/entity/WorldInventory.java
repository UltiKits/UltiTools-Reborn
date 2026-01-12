package com.ultikits.plugins.worlds.entity;

import java.util.UUID;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Entity for storing player inventory data per world group.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table("ulti_world_inventory")
public class WorldInventory extends BaseDataEntity<Integer> {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("world_group")
    private String worldGroup;
    
    @Column(value = "inventory_data", type = "TEXT")
    private String inventoryData;
    
    @Column(value = "armor_data", type = "TEXT")
    private String armorData;
    
    @Column(value = "off_hand_data", type = "TEXT")
    private String offHandData;
    
    @Column(value = "ender_chest_data", type = "TEXT")
    private String enderChestData;
    
    @Column(value = "experience_level")
    private int experienceLevel;
    
    @Column(value = "experience_points", type = "FLOAT")
    private float experiencePoints;
    
    @Column(value = "health", type = "FLOAT")
    private double health;
    
    @Column(value = "max_health", type = "FLOAT")
    private double maxHealth;
    
    @Column(value = "food_level")
    private int foodLevel;
    
    @Column(value = "saturation", type = "FLOAT")
    private float saturation;
    
    @Column(value = "effects_data", type = "TEXT")
    private String effectsData;
    
    @Column(value = "last_updated")
    private long lastUpdated;
    
    /**
     * Create a new empty inventory record.
     */
    public static WorldInventory create(UUID playerUuid, String worldGroup) {
        WorldInventory inv = new WorldInventory();
        inv.setPlayerUuid(playerUuid.toString());
        inv.setWorldGroup(worldGroup);
        inv.setExperienceLevel(0);
        inv.setExperiencePoints(0);
        inv.setHealth(20.0);
        inv.setMaxHealth(20.0);
        inv.setFoodLevel(20);
        inv.setSaturation(5.0f);
        inv.setLastUpdated(System.currentTimeMillis());
        return inv;
    }
    
    @Override
    public void onCreate() {
        this.lastUpdated = System.currentTimeMillis();
    }
    
    @Override
    public void onUpdate() {
        this.lastUpdated = System.currentTimeMillis();
    }
}
