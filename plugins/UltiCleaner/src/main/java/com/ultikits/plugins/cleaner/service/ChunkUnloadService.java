package com.ultikits.plugins.cleaner.service;

import com.ultikits.plugins.cleaner.config.CleanerConfig;
import com.ultikits.plugins.cleaner.events.PreChunkUnloadEvent;
import com.ultikits.plugins.cleaner.utils.ServerTypeUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for safe chunk unloading with Paper compatibility.
 * Unloads chunks that are far from players to improve server performance.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class ChunkUnloadService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private CleanerConfig config;

    @Autowired
    private TpsAwareScheduler tpsScheduler;

    private final Plugin bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    
    /**
     * Initialize the chunk unload service.
     * Note: Task is now automatically scheduled via @Scheduled annotation.
     */
    public void init() {
        if (config.isChunkUnloadEnabled()) {
            plugin.getLogger().info("Chunk unload service initialized.");
        }
    }

    /**
     * Shutdown the chunk unload service.
     * Note: @Scheduled tasks are automatically cancelled by the framework.
     */
    public void shutdown() {
        // No manual task cancellation needed
    }
    
    /**
     * Check and unload far chunks.
     * Runs every 30 seconds (600 ticks).
     */
    @Scheduled(period = 600, async = false)
    public void checkAndUnloadChunks() {
        if (!config.isChunkUnloadEnabled()) {
            return;
        }

        List<Chunk> chunksToUnload = collectChunksToUnload();
        
        if (!chunksToUnload.isEmpty()) {
            unloadChunksInBatches(chunksToUnload);
        }
    }
    
    /**
     * Collect chunks that are candidates for unloading.
     */
    private List<Chunk> collectChunksToUnload() {
        List<Chunk> chunks = new ArrayList<>();
        int maxDistance = config.getMaxChunkDistance();
        
        for (World world : Bukkit.getWorlds()) {
            // Skip blacklisted worlds
            if (config.getWorldBlacklist() != null && 
                config.getWorldBlacklist().contains(world.getName())) {
                continue;
            }
            
            List<Player> players = world.getPlayers();
            
            // If no players, all loaded chunks are candidates
            if (players.isEmpty()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    if (isSafeToUnload(chunk)) {
                        chunks.add(chunk);
                    }
                }
                continue;
            }
            
            // Check each loaded chunk
            for (Chunk chunk : world.getLoadedChunks()) {
                if (isChunkFarFromAllPlayers(chunk, players, maxDistance) && isSafeToUnload(chunk)) {
                    chunks.add(chunk);
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * Check if chunk is far from all players.
     * Uses chunk coordinates (not block coordinates) for accurate distance.
     */
    private boolean isChunkFarFromAllPlayers(Chunk chunk, List<Player> players, int maxDistance) {
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        
        for (Player player : players) {
            // Convert player location to chunk coordinates
            int playerChunkX = player.getLocation().getBlockX() >> 4;
            int playerChunkZ = player.getLocation().getBlockZ() >> 4;
            
            // Use Chebyshev distance (max of x and z difference)
            int distance = Math.max(
                Math.abs(chunkX - playerChunkX),
                Math.abs(chunkZ - playerChunkZ)
            );
            
            if (distance <= maxDistance) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Check if a chunk is safe to unload.
     */
    private boolean isSafeToUnload(Chunk chunk) {
        // Check if force loaded
        if (chunk.isForceLoaded()) {
            return false;
        }
        
        // Check if in use (has ticket)
        World world = chunk.getWorld();
        if (world.isChunkInUse(chunk.getX(), chunk.getZ())) {
            return false;
        }
        
        // Check if entities are loaded (Paper only)
        if (!ServerTypeUtil.isEntitiesLoaded(chunk)) {
            return false;
        }
        
        // Check for players in chunk
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Unload chunks in batches to avoid lag spikes.
     */
    private void unloadChunksInBatches(List<Chunk> chunks) {
        int batchSize = config.getChunkUnloadBatchSize();
        AtomicInteger unloadedCount = new AtomicInteger(0);
        AtomicInteger index = new AtomicInteger(0);
        int timeoutSeconds = config.getChunkUnloadTimeout();
        
        Bukkit.getScheduler().runTaskTimer(bukkitPlugin, task -> {
            int processed = 0;
            
            while (processed < batchSize && index.get() < chunks.size()) {
                Chunk chunk = chunks.get(index.getAndIncrement());
                
                // Re-check safety before unloading
                if (!isSafeToUnload(chunk)) {
                    continue;
                }
                
                // Fire pre-unload event
                PreChunkUnloadEvent event = new PreChunkUnloadEvent(chunk, PreChunkUnloadEvent.UnloadReason.DISTANCE);
                Bukkit.getPluginManager().callEvent(event);
                
                if (event.isCancelled()) {
                    continue;
                }
                
                // Use Paper async unload if available
                if (ServerTypeUtil.isPaper()) {
                    unloadChunkAsync(chunk, timeoutSeconds).thenAccept(success -> {
                        if (success) {
                            unloadedCount.incrementAndGet();
                        }
                    });
                } else {
                    // Sync unload for Spigot
                    if (chunk.unload(true)) {
                        unloadedCount.incrementAndGet();
                    }
                }
                
                processed++;
            }
            
            // Cancel task when done
            if (index.get() >= chunks.size()) {
                task.cancel();
                
                if (unloadedCount.get() > 0 && config.isShowCleanProgress()) {
                    Bukkit.getOnlinePlayers().stream()
                        .filter(Player::isOp)
                        .forEach(op -> op.sendMessage("§a[清理] §f已卸载 §e" + unloadedCount.get() + " §f个区块"));
                }
            }
        }, 0L, 1L);
    }
    
    /**
     * Unload chunk asynchronously with timeout (Paper only).
     */
    private CompletableFuture<Boolean> unloadChunkAsync(Chunk chunk, int timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        try {
            // Schedule unload on main thread
            Bukkit.getScheduler().runTask(bukkitPlugin, () -> {
                boolean success = chunk.unload(true);
                future.complete(success);
            });
            
            // Apply timeout
            return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    plugin.getLogger().warn(
                        "Chunk unload timeout at " + chunk.getX() + ", " + chunk.getZ()
                    );
                    return false;
                });
        } catch (Exception e) {
            future.complete(false);
            return future;
        }
    }
    
    /**
     * Force unload all far chunks immediately.
     * 
     * @return number of chunks unloaded
     */
    public int forceUnloadChunks() {
        List<Chunk> chunks = collectChunksToUnload();
        AtomicInteger count = new AtomicInteger(0);
        
        for (Chunk chunk : chunks) {
            if (isSafeToUnload(chunk)) {
                PreChunkUnloadEvent event = new PreChunkUnloadEvent(chunk, PreChunkUnloadEvent.UnloadReason.MANUAL);
                Bukkit.getPluginManager().callEvent(event);
                
                if (!event.isCancelled() && chunk.unload(true)) {
                    count.incrementAndGet();
                }
            }
        }
        
        return count.get();
    }
    
    /**
     * Get count of chunks that could be unloaded.
     */
    public int getUnloadableChunkCount() {
        return collectChunksToUnload().size();
    }
    
    /**
     * Get total loaded chunks across all worlds.
     */
    public int getTotalLoadedChunks() {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            total += world.getLoadedChunks().length;
        }
        return total;
    }
}
