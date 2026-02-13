package com.ultikits.plugins.worlds.service;

import com.ultikits.plugins.worlds.config.WorldConfig;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.Difficulty;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for world management operations.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class WorldService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private WorldConfig config;

    private DataOperator<WorldSettings> dataOperator;
    
    // Cache for world settings
    private final Map<String, WorldSettings> settingsCache = new ConcurrentHashMap<>();
    
    // Teleport cooldowns
    private final Map<UUID, Long> tpCooldowns = new ConcurrentHashMap<>();

    // Empty world timer (tracks how long a world has been empty)
    private final Map<String, Long> emptyWorldTimers = new ConcurrentHashMap<>();

    /**
     * Initialize the service with @PostConstruct.
     */
    @PostConstruct
    public void init() {
        this.dataOperator = plugin.getDataOperator(WorldSettings.class);

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
     * Auto-unload empty worlds periodically.
     * Scheduled task runs every 60 seconds (1200 ticks).
     */
    @Scheduled(period = 1200, async = false)
    public void checkAutoUnloadEmptyWorlds() {
        if (!config.isAutoUnloadEmptyWorlds()) {
            return;
        }

        long now = System.currentTimeMillis();
        int unloadAfter = config.getEmptyWorldUnloadAfter(); // in seconds

        for (World world : Bukkit.getWorlds()) {
            String worldName = world.getName();
            WorldSettings settings = getOrCreateSettings(worldName);

            // Skip if world should not auto-unload
            if (!settings.isAutoUnload() || config.getProtectedWorlds().contains(worldName)) {
                emptyWorldTimers.remove(worldName);
                continue;
            }

            if (world.getPlayers().isEmpty()) {
                // Start or check timer
                Long emptyStart = emptyWorldTimers.get(worldName);
                if (emptyStart == null) {
                    emptyWorldTimers.put(worldName, now);
                } else if (now - emptyStart > unloadAfter * 1000L) {
                    // World has been empty long enough, unload it
                    plugin.getLogger().info(
                        "Auto-unloading empty world: " + worldName
                    );
                    unloadWorld(worldName, true);
                    emptyWorldTimers.remove(worldName);
                }
            } else {
                // World has players, reset timer
                emptyWorldTimers.remove(worldName);
            }
        }
    }
    
    /**
     * Get or create world settings.
     */
    public WorldSettings getOrCreateSettings(String worldName) {
        if (settingsCache.containsKey(worldName)) {
            return settingsCache.get(worldName);
        }

        WorldSettings settings = dataOperator.query()
            .where("world_name").eq(worldName)
            .first();

        if (settings == null) {
            settings = WorldSettings.createDefault(worldName);
            dataOperator.insert(settings);
        }

        settingsCache.put(worldName, settings);

        // Apply difficulty if configured
        if (settings.getDifficulty() != null) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                try {
                    world.setDifficulty(Difficulty.valueOf(settings.getDifficulty()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warn("Invalid difficulty for world " + worldName + ": " + settings.getDifficulty());
                }
            }
        }

        return settings;
    }
    
    /**
     * Update world settings.
     */
    public void updateSettings(WorldSettings settings) {
        try {
            dataOperator.update(settings);
        } catch (IllegalAccessException e) {
            plugin.getLogger().error("Failed to update world settings", e);
        }
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
     * Check if player can enter world.
     */
    public boolean canEnterWorld(Player player, String worldName) {
        WorldSettings settings = getOrCreateSettings(worldName);
        
        // Check if blocked
        if (settings.isBlocked() && !player.hasPermission("ultiworlds.bypass.blocked")) {
            return false;
        }
        
        // Check if locked
        if (settings.isLocked() && !player.hasPermission("ultiworlds.bypass.locked")) {
            return false;
        }
        
        // Check permission
        if (config.isPermissionPerWorld() && 
            !player.hasPermission("ultiworlds.world." + worldName) &&
            !player.hasPermission("ultiworlds.world.*")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Teleport player to world.
     */
    public boolean teleportToWorld(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(plugin.i18n("error.world_not_found")
                .replace("%world%", worldName));
            return false;
        }

        WorldSettings settings = getOrCreateSettings(worldName);

        if (!checkTeleportPermissions(player, worldName, settings)) {
            return false;
        }

        Location destination = resolveDestination(world, settings);
        player.teleport(destination);
        setTpCooldown(player.getUniqueId());

        String displayName = settings.getDisplayName() != null ? settings.getDisplayName() : worldName;
        player.sendMessage(plugin.i18n("success.teleported")
            .replace("%world%", displayName));

        sendDescription(player, displayName, settings);
        executePostTeleportCommands(player.getName(), worldName, settings);

        return true;
    }

    private boolean checkTeleportPermissions(Player player, String worldName, WorldSettings settings) {
        if (settings.isBlocked() && !player.hasPermission("ultiworlds.bypass.blocked")) {
            player.sendMessage(plugin.i18n("error.world_blocked"));
            return false;
        }
        if (settings.isLocked() && !player.hasPermission("ultiworlds.bypass.locked")) {
            player.sendMessage(plugin.i18n("error.world_locked"));
            return false;
        }
        if (config.isPermissionPerWorld()
                && !player.hasPermission("ultiworlds.world." + worldName)
                && !player.hasPermission("ultiworlds.world.*")) {
            player.sendMessage(plugin.i18n("error.no_permission"));
            return false;
        }
        if (!canTeleport(player.getUniqueId())) {
            int remaining = getRemainingCooldown(player.getUniqueId());
            player.sendMessage(plugin.i18n("error.cooldown")
                .replace("%time%", String.valueOf(remaining)));
            return false;
        }
        return true;
    }

    private Location resolveDestination(World world, WorldSettings settings) {
        if (config.isUseSpawnLocation() && settings.getSpawnX() != 0) {
            return new Location(world,
                settings.getSpawnX(), settings.getSpawnY(), settings.getSpawnZ(),
                settings.getSpawnYaw(), settings.getSpawnPitch());
        }
        return world.getSpawnLocation();
    }

    private void sendDescription(Player player, String displayName, WorldSettings settings) {
        if (!config.isShowDescriptionOnTeleport()) {
            return;
        }
        String description = settings.getDescription();
        if (description == null || description.isEmpty()) {
            return;
        }
        for (String line : description.split("\\n")) {
            String parsed = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                line.replace("{player}", player.getName())
                    .replace("{world}", displayName));
            player.sendMessage(parsed);
        }
    }

    private void executePostTeleportCommands(String playerName, String worldName, WorldSettings settings) {
        String commands = settings.getPostTeleportCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }
        for (String cmd : commands.split("\\n")) {
            String parsed = cmd.trim()
                .replace("{player}", playerName)
                .replace("{world}", worldName);
            if (!parsed.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        }
    }
    
    /**
     * Create a new world with all options.
     */
    public World createWorld(String name, World.Environment environment, WorldType type, 
                             Boolean generateStructures, String seed) {
        if (Bukkit.getWorld(name) != null) {
            return null;
        }
        
        WorldCreator creator = new WorldCreator(name);
        creator.environment(environment);
        creator.type(type);
        
        if (generateStructures != null) {
            creator.generateStructures(generateStructures);
        }
        
        if (seed != null && !seed.isEmpty()) {
            try {
                creator.seed(Long.parseLong(seed));
            } catch (NumberFormatException e) {
                // Use string hash as seed
                creator.seed(seed.hashCode());
            }
        }
        
        World world = creator.createWorld();
        if (world != null) {
            getOrCreateSettings(name);
        }
        return world;
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
        dataOperator.query()
            .where("world_name").eq(name)
            .delete();
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
