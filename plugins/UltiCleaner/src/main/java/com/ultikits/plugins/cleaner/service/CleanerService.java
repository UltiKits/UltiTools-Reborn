package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.plugins.cleaner.events.CleanCompleteEvent;
import com.ultikits.plugins.cleaner.events.PreEntityCleanEvent;
import com.ultikits.plugins.cleaner.events.PreItemCleanEvent;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing entity and item cleanup.
 * Supports batch processing, smart cleanup, TPS-adaptive thresholds,
 * and custom events for extensibility.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class CleanerService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private CleanerConfig config;

    @Autowired
    private TpsAwareScheduler tpsScheduler;

    private final Plugin bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

    private Set<String> itemWhitelistCache;
    private Set<EntityType> entityTypesCache;
    private Set<String> worldBlacklistCache;

    // Track countdown state
    private int itemCountdown;
    private int entityCountdown;

    // Smart clean tracking
    private long lastSmartCleanTime = 0;

    // Batch processing state
    private boolean isCleaningInProgress = false;
    
    /**
     * Initialize the cleaner service.
     * Note: Tasks are now automatically scheduled via @Scheduled annotations.
     */
    public void init() {
        loadCaches();
    }

    /**
     * Shutdown the cleaner service.
     * Note: Tasks are now automatically cancelled by the framework.
     */
    public void shutdown() {
        // No manual task cancellation needed - framework handles @Scheduled tasks
    }

    /**
     * Reload configuration.
     */
    public void reload() {
        loadCaches();
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
                    plugin.getLogger().warn("Unknown entity type: " + type);
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
     * Check if smart cleanup should be triggered.
     * Runs every 5 seconds (100 ticks).
     */
    @Scheduled(period = 100, async = false)
    public void checkSmartClean() {
        if (!config.isSmartCleanEnabled() || isCleaningInProgress) {
            return;
        }
        
        // Check cooldown
        long now = System.currentTimeMillis();
        if (now - lastSmartCleanTime < config.getSmartCleanCooldown() * 1000L) {
            return;
        }
        
        // Get thresholds (adjusted by TPS if enabled)
        int itemThreshold = tpsScheduler != null ? 
            tpsScheduler.applyThresholdReduction(config.getItemMaxThreshold()) : 
            config.getItemMaxThreshold();
        int mobThreshold = tpsScheduler != null ? 
            tpsScheduler.applyThresholdReduction(config.getMobMaxThreshold()) : 
            config.getMobMaxThreshold();
        
        // Count current entities
        int itemCount = 0;
        int mobCount = 0;
        
        for (World world : Bukkit.getWorlds()) {
            if (worldBlacklistCache.contains(world.getName())) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item) {
                    itemCount++;
                } else if (entityTypesCache.contains(entity.getType())) {
                    mobCount++;
                }
            }
        }
        
        // Trigger smart clean if thresholds exceeded
        boolean shouldCleanItems = itemCount > itemThreshold;
        boolean shouldCleanMobs = mobCount > mobThreshold;
        
        if (shouldCleanItems || shouldCleanMobs) {
            lastSmartCleanTime = now;
            broadcastMessage(config.getSmartCleanTriggeredMessage());
            
            if (shouldCleanItems) {
                cleanItemsWithBatch(PreItemCleanEvent.CleanTrigger.SMART);
            }
            if (shouldCleanMobs) {
                cleanEntitiesWithBatch(PreEntityCleanEvent.CleanTrigger.SMART);
            }
        }
    }
    
    /**
     * Item cleanup tick.
     * Runs every second (20 ticks) to countdown and trigger cleanup.
     */
    @Scheduled(period = 20, async = false)
    public void tickItemClean() {
        if (!config.isItemCleanEnabled()) {
            return;
        }
        itemCountdown--;
        
        // Check if we need to warn
        if (config.getItemWarnTimes() != null && config.getItemWarnTimes().contains(itemCountdown)) {
            broadcastWarn(itemCountdown);
        }
        
        // Clean if countdown reached
        if (itemCountdown <= 0) {
            cleanItemsWithBatch(PreItemCleanEvent.CleanTrigger.SCHEDULED);
            itemCountdown = config.getItemCleanInterval();
        }
    }
    
    /**
     * Entity cleanup tick.
     * Runs every second (20 ticks) to countdown and trigger cleanup.
     */
    @Scheduled(period = 20, async = false)
    public void tickEntityClean() {
        if (!config.isEntityCleanEnabled()) {
            return;
        }
        entityCountdown--;
        
        // Check if we need to warn for entities
        if (config.getEntityWarnTimes() != null && config.getEntityWarnTimes().contains(entityCountdown)) {
            broadcastEntityWarn(entityCountdown);
        }
        
        if (entityCountdown <= 0) {
            cleanEntitiesWithBatch(PreEntityCleanEvent.CleanTrigger.SCHEDULED);
            entityCountdown = config.getEntityCleanInterval();
        }
    }
    
    /**
     * Clean items with batch processing and event support.
     */
    private void cleanItemsWithBatch(PreItemCleanEvent.CleanTrigger trigger) {
        if (isCleaningInProgress) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        List<UUID> itemsToClean = collectItemsToClean();
        
        // Fire pre-clean event
        PreItemCleanEvent preEvent = new PreItemCleanEvent(itemsToClean, null, trigger);
        Bukkit.getPluginManager().callEvent(preEvent);
        
        if (preEvent.isCancelled()) {
            broadcastMessage(config.getCleanCancelledMessage());
            return;
        }
        
        // Use modified list from event
        List<UUID> finalItems = preEvent.getItemUuids();
        
        if (finalItems.isEmpty()) {
            broadcastItemCleaned(0);
            return;
        }
        
        // Batch remove
        removeEntitiesInBatches(finalItems, config.getCleanBatchSize(), count -> {
            long duration = System.currentTimeMillis() - startTime;
            broadcastItemCleaned(count);
            
            // Fire complete event (async)
            Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
                CleanCompleteEvent completeEvent = new CleanCompleteEvent(
                    CleanCompleteEvent.CleanType.ITEMS,
                    count,
                    duration,
                    convertTrigger(trigger)
                );
                Bukkit.getPluginManager().callEvent(completeEvent);
            });
        });
    }
    
    /**
     * Clean entities with batch processing and event support.
     */
    private void cleanEntitiesWithBatch(PreEntityCleanEvent.CleanTrigger trigger) {
        if (isCleaningInProgress) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        Map<EntityType, Integer> typeCounts = new HashMap<>();
        List<UUID> entitiesToClean = collectEntitiesToClean(typeCounts);
        
        // Fire pre-clean event
        PreEntityCleanEvent preEvent = new PreEntityCleanEvent(entitiesToClean, null, trigger, typeCounts);
        Bukkit.getPluginManager().callEvent(preEvent);
        
        if (preEvent.isCancelled()) {
            broadcastMessage(config.getCleanCancelledMessage());
            return;
        }
        
        // Use modified list from event
        List<UUID> finalEntities = preEvent.getEntityUuids();
        
        if (finalEntities.isEmpty()) {
            return;
        }
        
        // Batch remove
        removeEntitiesInBatches(finalEntities, config.getCleanBatchSize(), count -> {
            long duration = System.currentTimeMillis() - startTime;
            broadcastEntityCleaned(count);
            
            // Fire complete event (async)
            Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
                CleanCompleteEvent completeEvent = new CleanCompleteEvent(
                    CleanCompleteEvent.CleanType.ENTITIES,
                    count,
                    duration,
                    convertTrigger(trigger)
                );
                Bukkit.getPluginManager().callEvent(completeEvent);
            });
        });
    }
    
    /**
     * Collect items that should be cleaned.
     */
    private List<UUID> collectItemsToClean() {
        List<UUID> items = new ArrayList<>();
        
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
                    
                    items.add(entity.getUniqueId());
                }
            }
        }
        
        return items;
    }
    
    /**
     * Collect entities that should be cleaned.
     */
    private List<UUID> collectEntitiesToClean(Map<EntityType, Integer> typeCounts) {
        List<UUID> entities = new ArrayList<>();
        
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
                
                entities.add(entity.getUniqueId());
                typeCounts.merge(entity.getType(), 1, Integer::sum);
            }
        }
        
        return entities;
    }
    
    /**
     * Remove entities in batches to avoid lag spikes.
     */
    private void removeEntitiesInBatches(List<UUID> uuids, int batchSize, java.util.function.Consumer<Integer> onComplete) {
        if (uuids.isEmpty()) {
            onComplete.accept(0);
            return;
        }
        
        isCleaningInProgress = true;
        AtomicInteger removedCount = new AtomicInteger(0);
        AtomicInteger currentIndex = new AtomicInteger(0);
        int totalCount = uuids.size();
        
        Bukkit.getScheduler().runTaskTimer(bukkitPlugin, task -> {
            int processed = 0;
            
            while (processed < batchSize && currentIndex.get() < uuids.size()) {
                UUID uuid = uuids.get(currentIndex.getAndIncrement());
                Entity entity = Bukkit.getEntity(uuid);
                
                if (entity != null && entity.isValid() && !(entity instanceof Player)) {
                    entity.remove();
                    removedCount.incrementAndGet();
                }
                processed++;
            }
            
            // Show progress if enabled
            if (config.isShowCleanProgress() && currentIndex.get() < uuids.size()) {
                String progressMsg = config.getCleanProgressMessage()
                    .replace("{CURRENT}", String.valueOf(currentIndex.get()))
                    .replace("{TOTAL}", String.valueOf(totalCount));
                
                Bukkit.getOnlinePlayers().stream()
                    .filter(Player::isOp)
                    .forEach(op -> op.sendMessage(ChatColor.translateAlternateColorCodes('&', progressMsg)));
            }
            
            // Check if done
            if (currentIndex.get() >= uuids.size()) {
                task.cancel();
                isCleaningInProgress = false;
                onComplete.accept(removedCount.get());
            }
        }, 0L, 1L);
    }
    
    /**
     * Convert PreItemCleanEvent trigger to CleanCompleteEvent trigger.
     */
    private CleanCompleteEvent.CleanTrigger convertTrigger(PreItemCleanEvent.CleanTrigger trigger) {
        switch (trigger) {
            case SMART: return CleanCompleteEvent.CleanTrigger.SMART;
            case MANUAL: return CleanCompleteEvent.CleanTrigger.MANUAL;
            default: return CleanCompleteEvent.CleanTrigger.SCHEDULED;
        }
    }
    
    /**
     * Convert PreEntityCleanEvent trigger to CleanCompleteEvent trigger.
     */
    private CleanCompleteEvent.CleanTrigger convertTrigger(PreEntityCleanEvent.CleanTrigger trigger) {
        switch (trigger) {
            case SMART: return CleanCompleteEvent.CleanTrigger.SMART;
            case MANUAL: return CleanCompleteEvent.CleanTrigger.MANUAL;
            default: return CleanCompleteEvent.CleanTrigger.SCHEDULED;
        }
    }
    
    /**
     * Broadcast a message.
     */
    private void broadcastMessage(String message) {
        String formatted = ChatColor.translateAlternateColorCodes('&', message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(formatted);
        }
    }
    
    /**
     * Broadcast warning message.
     */
    private void broadcastWarn(int seconds) {
        String message = config.getWarnMessage().replace("{TIME}", String.valueOf(seconds));
        broadcastMessage(message);
    }
    
    /**
     * Broadcast entity warning message.
     */
    private void broadcastEntityWarn(int seconds) {
        String message = config.getEntityWarnMessage().replace("{TIME}", String.valueOf(seconds));
        broadcastMessage(message);
    }
    
    /**
     * Broadcast item cleaned message.
     */
    private void broadcastItemCleaned(int count) {
        String message = config.getItemCleanedMessage().replace("{COUNT}", String.valueOf(count));
        broadcastMessage(message);
    }
    
    /**
     * Broadcast entity cleaned message.
     */
    private void broadcastEntityCleaned(int count) {
        if (count > 0) {
            String message = config.getEntityCleanedMessage().replace("{COUNT}", String.valueOf(count));
            broadcastMessage(message);
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
     * @return number of items collected for cleaning (actual removal is async)
     */
    public int forceCleanItems() {
        List<UUID> items = collectItemsToClean();
        cleanItemsWithBatch(PreItemCleanEvent.CleanTrigger.MANUAL);
        itemCountdown = config.getItemCleanInterval();
        return items.size();
    }
    
    /**
     * Force immediate entity cleanup.
     * 
     * @return number of entities collected for cleaning (actual removal is async)
     */
    public int forceCleanEntities() {
        Map<EntityType, Integer> typeCounts = new HashMap<>();
        List<UUID> entities = collectEntitiesToClean(typeCounts);
        cleanEntitiesWithBatch(PreEntityCleanEvent.CleanTrigger.MANUAL);
        entityCountdown = config.getEntityCleanInterval();
        return entities.size();
    }
    
    /**
     * Get current entity counts for status display.
     */
    public Map<String, Integer> getEntityCounts() {
        Map<String, Integer> counts = new HashMap<>();
        int itemCount = 0;
        int mobCount = 0;
        int totalEntities = 0;
        
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                totalEntities++;
                if (entity instanceof Item) {
                    itemCount++;
                } else if (entityTypesCache.contains(entity.getType())) {
                    mobCount++;
                }
            }
        }
        
        counts.put("items", itemCount);
        counts.put("mobs", mobCount);
        counts.put("total", totalEntities);
        return counts;
    }
    
    /**
     * Check if cleanup is currently in progress.
     */
    public boolean isCleaningInProgress() {
        return isCleaningInProgress;
    }
    
    /**
     * Get TPS scheduler for status display.
     */
    public TpsAwareScheduler getTpsScheduler() {
        return tpsScheduler;
    }
}
