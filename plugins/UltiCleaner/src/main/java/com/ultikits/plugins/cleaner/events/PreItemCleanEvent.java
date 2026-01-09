package com.ultikits.plugins.cleaner.events;

import java.util.List;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired before items are cleaned.
 * Can be cancelled to prevent the cleanup.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class PreItemCleanEvent extends Event implements Cancellable {
    
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final List<UUID> itemUuids;
    private final World world;
    private final CleanTrigger trigger;
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
     * Create a new PreItemCleanEvent.
     * 
     * @param itemUuids list of item UUIDs to be cleaned
     * @param world the world being cleaned (null for all worlds)
     * @param trigger what triggered this cleanup
     */
    public PreItemCleanEvent(List<UUID> itemUuids, World world, CleanTrigger trigger) {
        this.itemUuids = itemUuids;
        this.world = world;
        this.trigger = trigger;
    }
    
    /**
     * Get the list of item UUIDs that will be cleaned.
     * Modifying this list will affect which items are cleaned.
     * 
     * @return mutable list of item UUIDs
     */
    public List<UUID> getItemUuids() {
        return itemUuids;
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
     * Get the number of items to be cleaned.
     * 
     * @return item count
     */
    public int getItemCount() {
        return itemUuids.size();
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
