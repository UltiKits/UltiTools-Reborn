package com.ultikits.plugins.worlds.service;

import com.ultikits.plugins.worlds.UltiWorlds;
import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for world management operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class WorldService {
    
    @Autowired
    private WorldConfig config;
    
    private DataOperator<WorldSettings> dataOperator;
    
    // Cache for world settings
    private final Map<String, WorldSettings> settingsCache = new ConcurrentHashMap<>();
    
    // Teleport cooldowns
    private final Map<UUID, Long> tpCooldowns = new ConcurrentHashMap<>();
    
    /**
     * Initialize the service.
     */
    public void init() {
        this.dataOperator = UltiWorlds.getInstance().getDataOperator(WorldSettings.class);
        
        // Load configured worlds on start
        for (String worldName : config.getLoadWorldsOnStart()) {
            loadWorld(worldName);
        }
        
        // Initialize settings for existing worlds
        for (World world : Bukkit.getWorlds()) {
            getOrCreateSettings(world.getName());
        }
    }
    
    /**
     * Get or create world settings.
     */
    public WorldSettings getOrCreateSettings(String worldName) {
        if (settingsCache.containsKey(worldName)) {
            return settingsCache.get(worldName);
        }
        
        List<WorldSettings> existing = dataOperator.getAll(
            WhereCondition.builder()
                .column("world_name")
                .value(worldName)
                .build()
        );
        
        WorldSettings settings;
        if (existing.isEmpty()) {
            settings = WorldSettings.createDefault(worldName);
            dataOperator.insert(settings);
        } else {
            settings = existing.get(0);
        }
        
        settingsCache.put(worldName, settings);
        return settings;
    }
    
    /**
     * Update world settings.
     */
    public void updateSettings(WorldSettings settings) {
        dataOperator.update(settings);
        settingsCache.put(settings.getWorldName(), settings);
    }
    
    /**
     * Get all worlds.
     */
    public List<World> getAllWorlds() {
        return new ArrayList<>(Bukkit.getWorlds());
    }
    
    /**
     * Get all visible worlds.
     */
    public List<World> getVisibleWorlds() {
        List<World> visible = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            WorldSettings settings = getOrCreateSettings(world.getName());
            if (!settings.isHidden()) {
                visible.add(world);
            }
        }
        return visible;
    }
    
    /**
     * Teleport player to world.
     */
    public boolean teleportToWorld(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(config.getWorldNotFoundMessage()
                .replace("{WORLD}", worldName)
                .replace("&", "§"));
            return false;
        }
        
        WorldSettings settings = getOrCreateSettings(worldName);
        
        // Check if locked
        if (settings.isLocked() && !player.hasPermission("ultiworlds.bypass.locked")) {
            player.sendMessage("§c这个世界已被锁定，无法进入！");
            return false;
        }
        
        // Check permission
        if (config.isPermissionPerWorld() && 
            !player.hasPermission("ultiworlds.world." + worldName) &&
            !player.hasPermission("ultiworlds.world.*")) {
            player.sendMessage(config.getNoPermissionMessage()
                .replace("{WORLD}", worldName)
                .replace("&", "§"));
            return false;
        }
        
        // Check cooldown
        if (!canTeleport(player.getUniqueId())) {
            int remaining = getRemainingCooldown(player.getUniqueId());
            player.sendMessage("§c传送冷却中！请等待 " + remaining + " 秒");
            return false;
        }
        
        // Determine location
        Location destination;
        if (config.isUseSpawnLocation() && settings.getSpawnX() != 0) {
            destination = new Location(world, 
                settings.getSpawnX(), 
                settings.getSpawnY(), 
                settings.getSpawnZ(),
                settings.getSpawnYaw(),
                settings.getSpawnPitch());
        } else if (config.isUseSpawnLocation()) {
            destination = world.getSpawnLocation();
        } else {
            destination = world.getSpawnLocation();
        }
        
        player.teleport(destination);
        setTpCooldown(player.getUniqueId());
        
        player.sendMessage(config.getWorldTeleportMessage()
            .replace("{WORLD}", settings.getDisplayName() != null ? settings.getDisplayName() : worldName)
            .replace("&", "§"));
        
        return true;
    }
    
    /**
     * Create a new world.
     */
    public boolean createWorld(String name, World.Environment environment, WorldType type, String generator) {
        if (Bukkit.getWorld(name) != null) {
            return false;
        }
        
        WorldCreator creator = new WorldCreator(name);
        creator.environment(environment);
        creator.type(type);
        
        if (generator != null && !generator.isEmpty()) {
            creator.generator(generator);
        }
        
        World world = creator.createWorld();
        if (world != null) {
            getOrCreateSettings(name);
            return true;
        }
        return false;
    }
    
    /**
     * Load an existing world.
     */
    public boolean loadWorld(String name) {
        if (Bukkit.getWorld(name) != null) {
            return true; // Already loaded
        }
        
        File worldFolder = new File(Bukkit.getWorldContainer(), name);
        if (!worldFolder.exists()) {
            return false;
        }
        
        WorldCreator creator = new WorldCreator(name);
        World world = creator.createWorld();
        
        if (world != null) {
            getOrCreateSettings(name);
            return true;
        }
        return false;
    }
    
    /**
     * Unload a world.
     */
    public boolean unloadWorld(String name, boolean save) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            return false;
        }
        
        // Move players to default world first
        World defaultWorld = Bukkit.getWorld(config.getDefaultWorld());
        if (defaultWorld == null) {
            defaultWorld = Bukkit.getWorlds().get(0);
        }
        
        for (Player player : world.getPlayers()) {
            player.teleport(defaultWorld.getSpawnLocation());
            player.sendMessage("§e世界正在卸载，你已被传送到主世界");
        }
        
        return Bukkit.unloadWorld(world, save);
    }
    
    /**
     * Delete a world (unload and delete files).
     */
    public boolean deleteWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) {
            if (!unloadWorld(name, false)) {
                return false;
            }
        }
        
        File worldFolder = new File(Bukkit.getWorldContainer(), name);
        if (worldFolder.exists()) {
            deleteFolder(worldFolder);
        }
        
        // Remove from database
        List<WorldSettings> settings = dataOperator.getAll(
            WhereCondition.builder().column("world_name").value(name).build()
        );
        for (WorldSettings s : settings) {
            dataOperator.delete(s);
        }
        settingsCache.remove(name);
        
        return true;
    }
    
    /**
     * Delete folder recursively.
     */
    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        folder.delete();
    }
    
    /**
     * Set world spawn.
     */
    public void setWorldSpawn(String worldName, Location location) {
        WorldSettings settings = getOrCreateSettings(worldName);
        settings.setSpawnX(location.getX());
        settings.setSpawnY(location.getY());
        settings.setSpawnZ(location.getZ());
        settings.setSpawnYaw(location.getYaw());
        settings.setSpawnPitch(location.getPitch());
        updateSettings(settings);
        
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            world.setSpawnLocation(location);
        }
    }
    
    /**
     * Check teleport cooldown.
     */
    public boolean canTeleport(UUID playerUuid) {
        Long lastTp = tpCooldowns.get(playerUuid);
        if (lastTp == null) {
            return true;
        }
        return System.currentTimeMillis() - lastTp > config.getTpCooldown() * 1000L;
    }
    
    /**
     * Set teleport cooldown.
     */
    public void setTpCooldown(UUID playerUuid) {
        tpCooldowns.put(playerUuid, System.currentTimeMillis());
    }
    
    /**
     * Get remaining cooldown.
     */
    public int getRemainingCooldown(UUID playerUuid) {
        Long lastTp = tpCooldowns.get(playerUuid);
        if (lastTp == null) {
            return 0;
        }
        long remaining = (config.getTpCooldown() * 1000L) - (System.currentTimeMillis() - lastTp);
        return Math.max(0, (int) (remaining / 1000));
    }
    
    public WorldConfig getConfig() {
        return config;
    }
}
