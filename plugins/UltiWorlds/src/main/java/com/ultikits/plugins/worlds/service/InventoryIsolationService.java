package com.ultikits.plugins.worlds.service;

import com.ultikits.plugins.worlds.UltiWorlds;
import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldInventory;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.utils.ItemStackUtils;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing per-world inventory isolation.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class InventoryIsolationService {
    
    @Autowired
    private WorldConfig config;
    
    private DataOperator<WorldInventory> dataOperator;
    
    // Cache world groups for fast lookup
    private final Map<String, String> worldGroupCache = new ConcurrentHashMap<>();
    
    public void init() {
        this.dataOperator = UltiWorlds.getInstance().getDataOperator(WorldInventory.class);
        
        // Parse shared world groups
        parseWorldGroups();
    }
    
    /**
     * Parse world groups from config.
     */
    private void parseWorldGroups() {
        worldGroupCache.clear();
        int groupIndex = 0;
        
        for (String groupStr : config.getSharedWorldGroups()) {
            String groupName = "group_" + groupIndex++;
            String[] worlds = groupStr.split(",");
            for (String world : worlds) {
                worldGroupCache.put(world.trim(), groupName);
            }
        }
    }
    
    /**
     * Get the world group for a world.
     * Worlds not in any group are their own group.
     */
    public String getWorldGroup(String worldName) {
        return worldGroupCache.getOrDefault(worldName, worldName);
    }
    
    /**
     * Check if two worlds share the same inventory group.
     */
    public boolean areWorldsShared(String world1, String world2) {
        return getWorldGroup(world1).equals(getWorldGroup(world2));
    }
    
    /**
     * Save player inventory for current world.
     */
    public void saveInventory(Player player, String worldName) {
        if (!config.isInventoryIsolation()) return;
        
        String group = getWorldGroup(worldName);
        WorldInventory inv = getOrCreateInventory(player.getUniqueId(), group);
        
        try {
            // Save inventory
            if (config.isSeparateInventory()) {
                inv.setInventoryData(ItemStackUtils.serializeItems(player.getInventory().getContents()));
                inv.setArmorData(ItemStackUtils.serializeItems(player.getInventory().getArmorContents()));
                inv.setOffHandData(ItemStackUtils.serializeItem(player.getInventory().getItemInOffHand()));
            }
            
            // Save ender chest
            if (config.isSeparateEnderChest()) {
                inv.setEnderChestData(ItemStackUtils.serializeItems(player.getEnderChest().getContents()));
            }
            
            // Save experience
            if (config.isSeparateExperience()) {
                inv.setExperienceLevel(player.getLevel());
                inv.setExperiencePoints(player.getExp());
            }
            
            // Save health
            if (config.isSeparateHealth()) {
                inv.setHealth(player.getHealth());
                inv.setMaxHealth(player.getMaxHealth());
            }
            
            // Save hunger
            if (config.isSeparateHunger()) {
                inv.setFoodLevel(player.getFoodLevel());
                inv.setSaturation(player.getSaturation());
            }
            
            // Save effects
            if (config.isSeparateEffects()) {
                inv.setEffectsData(serializeEffects(player.getActivePotionEffects()));
            }
            
            dataOperator.update(inv);
        } catch (Exception e) {
            UltiWorlds.getInstance().getLogger().error("Failed to save inventory for " + player.getName(), e);
        }
    }
    
    /**
     * Load player inventory for target world.
     */
    public void loadInventory(Player player, String worldName) {
        if (!config.isInventoryIsolation()) return;
        
        String group = getWorldGroup(worldName);
        WorldInventory inv = getOrCreateInventory(player.getUniqueId(), group);
        
        try {
            // Load inventory
            if (config.isSeparateInventory()) {
                if (inv.getInventoryData() != null) {
                    ItemStack[] contents = ItemStackUtils.deserializeItems(inv.getInventoryData());
                    player.getInventory().setContents(contents);
                } else {
                    player.getInventory().clear();
                }
                
                if (inv.getArmorData() != null) {
                    ItemStack[] armor = ItemStackUtils.deserializeItems(inv.getArmorData());
                    player.getInventory().setArmorContents(armor);
                }
                
                if (inv.getOffHandData() != null) {
                    ItemStack offHand = ItemStackUtils.deserializeItem(inv.getOffHandData());
                    player.getInventory().setItemInOffHand(offHand);
                }
            }
            
            // Load ender chest
            if (config.isSeparateEnderChest() && inv.getEnderChestData() != null) {
                ItemStack[] enderContents = ItemStackUtils.deserializeItems(inv.getEnderChestData());
                player.getEnderChest().setContents(enderContents);
            }
            
            // Load experience
            if (config.isSeparateExperience()) {
                player.setLevel(inv.getExperienceLevel());
                player.setExp(inv.getExperiencePoints());
            }
            
            // Load health
            if (config.isSeparateHealth()) {
                player.setMaxHealth(inv.getMaxHealth());
                player.setHealth(Math.min(inv.getHealth(), inv.getMaxHealth()));
            }
            
            // Load hunger
            if (config.isSeparateHunger()) {
                player.setFoodLevel(inv.getFoodLevel());
                player.setSaturation(inv.getSaturation());
            }
            
            // Load effects
            if (config.isSeparateEffects()) {
                // Clear existing effects
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
                // Apply saved effects
                if (inv.getEffectsData() != null) {
                    deserializeAndApplyEffects(player, inv.getEffectsData());
                }
            }
        } catch (Exception e) {
            UltiWorlds.getInstance().getLogger().error("Failed to load inventory for " + player.getName(), e);
        }
    }
    
    /**
     * Get or create inventory record.
     */
    private WorldInventory getOrCreateInventory(UUID playerUuid, String worldGroup) {
        List<WorldInventory> existing = dataOperator.getAll(
            WhereCondition.builder().column("player_uuid").value(playerUuid.toString()).build(),
            WhereCondition.builder().column("world_group").value(worldGroup).build()
        );
        
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        
        WorldInventory inv = WorldInventory.create(playerUuid, worldGroup);
        dataOperator.insert(inv);
        return inv;
    }
    
    /**
     * Serialize potion effects to string.
     */
    private String serializeEffects(Collection<PotionEffect> effects) {
        StringBuilder sb = new StringBuilder();
        for (PotionEffect effect : effects) {
            if (sb.length() > 0) sb.append(";");
            sb.append(effect.getType().getName())
              .append(",")
              .append(effect.getDuration())
              .append(",")
              .append(effect.getAmplifier())
              .append(",")
              .append(effect.isAmbient())
              .append(",")
              .append(effect.hasParticles());
        }
        return sb.toString();
    }
    
    /**
     * Deserialize and apply potion effects.
     */
    private void deserializeAndApplyEffects(Player player, String data) {
        if (data == null || data.isEmpty()) return;
        
        String[] effects = data.split(";");
        for (String effectStr : effects) {
            String[] parts = effectStr.split(",");
            if (parts.length >= 3) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(parts[0]);
                    if (type != null) {
                        int duration = Integer.parseInt(parts[1]);
                        int amplifier = Integer.parseInt(parts[2]);
                        boolean ambient = parts.length > 3 && Boolean.parseBoolean(parts[3]);
                        boolean particles = parts.length <= 4 || Boolean.parseBoolean(parts[4]);
                        
                        player.addPotionEffect(new PotionEffect(type, duration, amplifier, ambient, particles));
                    }
                } catch (Exception ignored) {
                    // Skip invalid effects
                }
            }
        }
    }
    
    /**
     * Handle player world change.
     */
    public void onWorldChange(Player player, World from, World to) {
        if (!config.isInventoryIsolation()) return;
        
        String fromGroup = getWorldGroup(from.getName());
        String toGroup = getWorldGroup(to.getName());
        
        // Only swap if groups are different
        if (!fromGroup.equals(toGroup)) {
            saveInventory(player, from.getName());
            loadInventory(player, to.getName());
        }
    }
}
