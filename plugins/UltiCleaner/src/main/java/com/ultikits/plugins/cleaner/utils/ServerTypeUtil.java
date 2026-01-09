package com.ultikits.plugins.cleaner.utils;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Utility class for detecting server type and providing compatibility methods.
 * Supports Paper, Spigot, and other Bukkit-based servers.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public final class ServerTypeUtil {
    
    private static Boolean isPaper = null;
    private static Boolean isModernPaper = null;
    private static Boolean hasTpsMethod = null;
    private static Method getTpsMethod = null;
    private static Method getChunkAtAsyncMethod = null;
    private static Method isEntitiesLoadedMethod = null;
    
    private ServerTypeUtil() {
        // Utility class
    }
    
    /**
     * Check if the server is running Paper or a Paper fork.
     * 
     * @return true if Paper is detected
     */
    public static boolean isPaper() {
        if (isPaper == null) {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                isPaper = true;
            } catch (ClassNotFoundException e) {
                isPaper = false;
            }
        }
        return isPaper;
    }
    
    /**
     * Check if the server is running modern Paper (1.17+).
     * Modern Paper uses io.papermc package structure.
     * 
     * @return true if modern Paper is detected
     */
    public static boolean isModernPaper() {
        if (isModernPaper == null) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                isModernPaper = true;
            } catch (ClassNotFoundException e) {
                isModernPaper = false;
            }
        }
        return isModernPaper;
    }
    
    /**
     * Check if the server supports getTPS() method.
     * 
     * @return true if getTPS is available
     */
    public static boolean hasTpsMethod() {
        if (hasTpsMethod == null) {
            try {
                getTpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
                hasTpsMethod = true;
            } catch (NoSuchMethodException e) {
                hasTpsMethod = false;
            }
        }
        return hasTpsMethod;
    }
    
    /**
     * Get server TPS using reflection.
     * Returns [1m, 5m, 15m] TPS values if available.
     * 
     * @return TPS array or null if not available
     */
    public static double[] getServerTps() {
        if (!hasTpsMethod()) {
            return null;
        }
        try {
            return (double[]) getTpsMethod.invoke(Bukkit.getServer());
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get chunk asynchronously if Paper is available.
     * Falls back to sync loading on Spigot.
     * 
     * @param world the world
     * @param x chunk X coordinate
     * @param z chunk Z coordinate
     * @return CompletableFuture with the chunk
     */
    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        if (isPaper()) {
            try {
                if (getChunkAtAsyncMethod == null) {
                    getChunkAtAsyncMethod = World.class.getMethod("getChunkAtAsync", int.class, int.class);
                }
                @SuppressWarnings("unchecked")
                CompletableFuture<Chunk> future = (CompletableFuture<Chunk>) getChunkAtAsyncMethod.invoke(world, x, z);
                return future;
            } catch (Exception e) {
                // Fall through to sync loading
            }
        }
        
        // Fallback: sync loading wrapped in CompletableFuture
        return CompletableFuture.completedFuture(world.getChunkAt(x, z));
    }
    
    /**
     * Check if chunk has entities loaded (Paper only).
     * Returns true on Spigot (assume entities are loaded if chunk is loaded).
     * 
     * @param chunk the chunk to check
     * @return true if entities are loaded
     */
    public static boolean isEntitiesLoaded(Chunk chunk) {
        if (isPaper()) {
            try {
                if (isEntitiesLoadedMethod == null) {
                    isEntitiesLoadedMethod = Chunk.class.getMethod("isEntitiesLoaded");
                }
                return (boolean) isEntitiesLoadedMethod.invoke(chunk);
            } catch (Exception e) {
                // Fall through to default
            }
        }
        // Spigot: assume entities are loaded if chunk is loaded
        return chunk.isLoaded();
    }
    
    /**
     * Get server software name for logging.
     * 
     * @return server software name
     */
    public static String getServerSoftware() {
        if (isModernPaper()) {
            return "Paper (Modern)";
        } else if (isPaper()) {
            return "Paper (Legacy)";
        } else {
            return "Spigot/CraftBukkit";
        }
    }
}
