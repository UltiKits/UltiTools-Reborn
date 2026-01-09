package com.ultikits.plugins.cleaner.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired before entities are cleaned.
 * Can be cancelled to prevent the cleanup.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class PreEntityCleanEvent extends Event implements Cancellable {
    
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final List<UUID> entityUuids;
    private final World world;
    private final CleanTrigger trigger;
    private final Map<EntityType, Integer> entityTypeCounts;
    private boolean cancelled = false;
    
    /**
     * Cleanup trigger type.
     */
    public enum CleanTrigger {
        /** Scheduled automatic cleanup */
        SCHEDULED,
        /** Smart cleanup triggered by threshold */
        SMART,
        /** Manual cleanup by command */
        MANUAL
    }
    
    /**
     * Create a new PreEntityCleanEvent.
     * 
     * @param entityUuids list of entity UUIDs to be cleaned
     * @param world the world being cleaned (null for all worlds)
     * @param trigger what triggered this cleanup
     * @param entityTypeCounts count of each entity type being cleaned
     */
    public PreEntityCleanEvent(List<UUID> entityUuids, World world, CleanTrigger trigger, 
                               Map<EntityType, Integer> entityTypeCounts) {
        this.entityUuids = entityUuids;
        this.world = world;
        this.trigger = trigger;
        this.entityTypeCounts = entityTypeCounts;
    }
    
    /**
     * Get the list of entity UUIDs that will be cleaned.
     * Modifying this list will affect which entities are cleaned.
     * 
     * @return mutable list of entity UUIDs
     */
    public List<UUID> getEntityUuids() {
        return entityUuids;
    }
    
    /**
     * Get the world being cleaned.
     * 
     * @return the world, or null if cleaning all worlds
     */
    public World getWorld() {
        return world;
    }
    
    /**
     * Get what triggered this cleanup.
     * 
     * @return the trigger type
     */
    public CleanTrigger getTrigger() {
        return trigger;
    }
    
    /**
     * Get the count of each entity type being cleaned.
     * 
     * @return map of entity type to count
     */
    public Map<EntityType, Integer> getEntityTypeCounts() {
        return entityTypeCounts;
    }
    
    /**
     * Get the total number of entities to be cleaned.
     * 
     * @return entity count
     */
    public int getEntityCount() {
        return entityUuids.size();
    }
    
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
    
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
    
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
    
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
