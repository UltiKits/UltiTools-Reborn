package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.UltiCleaner;
import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing entity and item cleanup.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class CleanerService {
    
    @Autowired
    private CleanerConfig config;
    
    private BukkitTask itemCleanTask;
    private BukkitTask entityCleanTask;
    private Set<String> itemWhitelistCache;
    private Set<EntityType> entityTypesCache;
    private Set<String> worldBlacklistCache;
    
    // Track countdown state
    private int itemCountdown;
    private int entityCountdown;
    
    /**
     * Initialize the cleaner service.
     */
    public void init() {
        loadCaches();
        startTasks();
    }
    
    /**
     * Shutdown the cleaner service.
     */
    public void shutdown() {
        stopTasks();
    }
    
    /**
     * Reload configuration.
     */
    public void reload() {
        stopTasks();
        loadCaches();
        startTasks();
    }
    
    /**
     * Load caches from config.
     */
    private void loadCaches() {
        // Item whitelist
        itemWhitelistCache = new HashSet<>();
        if (config.getItemWhitelist() != null) {
            itemWhitelistCache.addAll(config.getItemWhitelist());
        }
        
        // Entity types to clean
        entityTypesCache = new HashSet<>();
        if (config.getEntityTypes() != null) {
            for (String type : config.getEntityTypes()) {
                try {
                    entityTypesCache.add(EntityType.valueOf(type.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    UltiCleaner.getInstance().getLogger().warning("Unknown entity type: " + type);
                }
            }
        }
        
        // World blacklist
        worldBlacklistCache = new HashSet<>();
        if (config.getWorldBlacklist() != null) {
            worldBlacklistCache.addAll(config.getWorldBlacklist());
        }
        
        // Initialize countdowns
        itemCountdown = config.getItemCleanInterval();
        entityCountdown = config.getEntityCleanInterval();
    }
    
    /**
     * Start cleanup tasks.
     */
    private void startTasks() {
        // Item cleanup task - runs every second for countdown
        if (config.isItemCleanEnabled()) {
            itemCountdown = config.getItemCleanInterval();
            itemCleanTask = Bukkit.getScheduler().runTaskTimer(
                UltiCleaner.getInstance().getPluginInstance(),
                this::tickItemClean,
                20L, 20L // Every second
            );
        }
        
        // Entity cleanup task - runs at interval directly
        if (config.isEntityCleanEnabled()) {
            entityCountdown = config.getEntityCleanInterval();
            entityCleanTask = Bukkit.getScheduler().runTaskTimer(
                UltiCleaner.getInstance().getPluginInstance(),
                this::tickEntityClean,
                20L, 20L // Every second
            );
        }
    }
    
    /**
     * Stop cleanup tasks.
     */
    private void stopTasks() {
        if (itemCleanTask != null) {
            itemCleanTask.cancel();
            itemCleanTask = null;
        }
        if (entityCleanTask != null) {
            entityCleanTask.cancel();
            entityCleanTask = null;
        }
    }
    
    /**
     * Item cleanup tick.
     */
    private void tickItemClean() {
        itemCountdown--;
        
        // Check if we need to warn
        if (config.getItemWarnTimes() != null && config.getItemWarnTimes().contains(itemCountdown)) {
            broadcastWarn(itemCountdown);
        }
        
        // Clean if countdown reached
        if (itemCountdown <= 0) {
            int count = cleanItems();
            broadcastItemCleaned(count);
            itemCountdown = config.getItemCleanInterval();
        }
    }
    
    /**
     * Entity cleanup tick.
     */
    private void tickEntityClean() {
        entityCountdown--;
        
        if (entityCountdown <= 0) {
            int count = cleanEntities();
            broadcastEntityCleaned(count);
            entityCountdown = config.getEntityCleanInterval();
        }
    }
    
    /**
     * Clean all ground items.
     * 
     * @return number of items cleaned
     */
    public int cleanItems() {
        AtomicInteger count = new AtomicInteger(0);
        long now = System.currentTimeMillis();
        
        for (World world : Bukkit.getWorlds()) {
            if (worldBlacklistCache.contains(world.getName())) {
                continue;
            }
            
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item) {
                    Item item = (Item) entity;
                    
                    // Check if in whitelist
                    if (item.getItemStack() != null) {
                        String typeName = item.getItemStack().getType().name();
                        if (itemWhitelistCache.contains(typeName)) {
                            continue;
                        }
                        
                        // Check if named
                        if (config.isItemIgnoreNamed() && 
                            item.getItemStack().hasItemMeta() && 
                            item.getItemStack().getItemMeta().hasDisplayName()) {
                            continue;
                        }
                    }
                    
                    // Check if recently dropped
                    if (config.getItemIgnoreRecentSeconds() > 0) {
                        int ticksAlive = item.getTicksLived();
                        if (ticksAlive < config.getItemIgnoreRecentSeconds() * 20) {
                            continue;
                        }
                    }
                    
                    item.remove();
                    count.incrementAndGet();
                }
            }
        }
        
        return count.get();
    }
    
    /**
     * Clean entities of configured types.
     * 
     * @return number of entities cleaned
     */
    public int cleanEntities() {
        AtomicInteger count = new AtomicInteger(0);
        
        for (World world : Bukkit.getWorlds()) {
            if (worldBlacklistCache.contains(world.getName())) {
                continue;
            }
            
            for (Entity entity : world.getEntities()) {
                if (!entityTypesCache.contains(entity.getType())) {
                    continue;
                }
                
                // Check if named
                if (config.isEntityWhitelistNamed() && entity.getCustomName() != null) {
                    continue;
                }
                
                // Check if living entity specific conditions
                if (entity instanceof LivingEntity) {
                    LivingEntity living = (LivingEntity) entity;
                    
                    // Check if leashed
                    if (config.isEntityWhitelistLeashed() && living.isLeashed()) {
                        continue;
                    }
                    
                    // Check if tamed
                    if (config.isEntityWhitelistTamed() && entity instanceof Tameable) {
                        Tameable tameable = (Tameable) entity;
                        if (tameable.isTamed()) {
                            continue;
                        }
                    }
                }
                
                entity.remove();
                count.incrementAndGet();
            }
        }
        
        return count.get();
    }
    
    /**
     * Broadcast warning message.
     */
    private void broadcastWarn(int seconds) {
        String message = config.getWarnMessage().replace("{TIME}", String.valueOf(seconds));
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
    
    /**
     * Broadcast item cleaned message.
     */
    private void broadcastItemCleaned(int count) {
        String message = config.getItemCleanedMessage().replace("{COUNT}", String.valueOf(count));
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
    
    /**
     * Broadcast entity cleaned message.
     */
    private void broadcastEntityCleaned(int count) {
        if (count > 0) {
            String message = config.getEntityCleanedMessage().replace("{COUNT}", String.valueOf(count));
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }
    
    /**
     * Get remaining seconds until next item cleanup.
     */
    public int getItemCountdown() {
        return itemCountdown;
    }
    
    /**
     * Get remaining seconds until next entity cleanup.
     */
    public int getEntityCountdown() {
        return entityCountdown;
    }
    
    /**
     * Force immediate item cleanup.
     * 
     * @return number of items cleaned
     */
    public int forceCleanItems() {
        int count = cleanItems();
        itemCountdown = config.getItemCleanInterval();
        return count;
    }
    
    /**
     * Force immediate entity cleanup.
     * 
     * @return number of entities cleaned
     */
    public int forceCleanEntities() {
        int count = cleanEntities();
        entityCountdown = config.getEntityCleanInterval();
        return count;
    }
}
