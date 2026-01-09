package com.ultikits.plugins.cleaner.events;

import org.bukkit.Chunk;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event fired before a chunk is unloaded by UltiCleaner.
 * Can be cancelled to prevent the unload.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class PreChunkUnloadEvent extends Event implements Cancellable {
    
    private static final HandlerList HANDLERS = new HandlerList();
    
    private final Chunk chunk;
    private final UnloadReason reason;
    private boolean cancelled = false;
    
    /**
     * Reason for chunk unload.
     */
    public enum UnloadReason {
        /** Chunk is far from all players */
        DISTANCE,
        /** Chunk has been idle for too long */
        IDLE,
        /** Manual unload by command */
        MANUAL
    }
    
    /**
     * Create a new PreChunkUnloadEvent.
     * 
     * @param chunk the chunk to be unloaded
     * @param reason why the chunk is being unloaded
     */
    public PreChunkUnloadEvent(Chunk chunk, UnloadReason reason) {
        this.chunk = chunk;
        this.reason = reason;
    }
    
    /**
     * Get the chunk that will be unloaded.
     * 
     * @return the chunk
     */
    public Chunk getChunk() {
        return chunk;
    }
    
    /**
     * Get the reason for unloading.
     * 
     * @return the unload reason
     */
    public UnloadReason getReason() {
        return reason;
    }
    
    /**
     * Get the world name of the chunk.
     * 
     * @return world name
     */
    public String getWorldName() {
        return chunk.getWorld().getName();
    }
    
    /**
     * Get chunk X coordinate.
     * 
     * @return chunk X
     */
    public int getChunkX() {
        return chunk.getX();
    }
    
    /**
     * Get chunk Z coordinate.
     * 
     * @return chunk Z
     */
    public int getChunkZ() {
        return chunk.getZ();
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
