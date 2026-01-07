package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.WarpData;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing server warp points.
 * <p>
 * 管理服务器地标点的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class WarpService {
    
    @Autowired
    private EssentialsConfig config;
    
    private DataOperator<WarpData> warpOperator;
    
    // Pending teleports for warmup
    private final Map<UUID, BukkitTask> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportStartLocations = new ConcurrentHashMap<>();
    
    /**
     * Initializes the service with the data operator.
     */
    public void init() {
        this.warpOperator = UltiEssentials.getInstance().getDataOperator(WarpData.class);
    }
    
    /**
     * Gets all warps.
     *
     * @return list of all warps
     */
    public List<WarpData> getAllWarps() {
        return warpOperator.getAll();
    }
    
    /**
     * Gets warps that a player can access.
     *
     * @param player the player
     * @return list of accessible warps
     */
    public List<WarpData> getAccessibleWarps(Player player) {
        List<WarpData> allWarps = getAllWarps();
        List<WarpData> accessible = new ArrayList<>();
        
        for (WarpData warp : allWarps) {
            if (canAccess(player, warp)) {
                accessible.add(warp);
            }
        }
        
        return accessible;
    }
    
    /**
     * Checks if a player can access a warp.
     */
    public boolean canAccess(Player player, WarpData warp) {
        if (warp.getPermission() == null || warp.getPermission().isEmpty()) {
            return true;
        }
        return player.hasPermission(warp.getPermission());
    }
    
    /**
     * Gets a warp by name.
     *
     * @param name the warp name (case-insensitive)
     * @return the warp, or null if not found
     */
    @Nullable
    public WarpData getWarp(String name) {
        List<WarpData> warps = warpOperator.getAll(
            WhereCondition.builder()
                .column("name")
                .value(name.toLowerCase())
                .build()
        );
        return warps.isEmpty() ? null : warps.get(0);
    }
    
    /**
     * Creates a new warp.
     *
     * @param name       the warp name
     * @param location   the warp location
     * @param createdBy  the UUID of the creator
     * @param permission optional permission required to use this warp
     * @return result of the operation
     */
    public WarpResult createWarp(String name, Location location, UUID createdBy, @Nullable String permission) {
        if (!config.isWarpEnabled()) {
            return WarpResult.DISABLED;
        }
        
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) {
            return WarpResult.INVALID_NAME;
        }
        
        // Check if warp already exists
        if (getWarp(normalizedName) != null) {
            return WarpResult.ALREADY_EXISTS;
        }
        
        WarpData warp = WarpData.builder()
            .uuid(UUID.randomUUID())
            .name(normalizedName)
            .world(location.getWorld().getName())
            .x(location.getX())
            .y(location.getY())
            .z(location.getZ())
            .yaw(location.getYaw())
            .pitch(location.getPitch())
            .permission(permission)
            .createdBy(createdBy.toString())
            .createdAt(System.currentTimeMillis())
            .build();
        
        warpOperator.insert(warp);
        return WarpResult.CREATED;
    }
    
    /**
     * Deletes a warp.
     *
     * @param name the warp name
     * @return true if deleted, false if not found
     */
    public boolean deleteWarp(String name) {
        WarpData warp = getWarp(name.toLowerCase().trim());
        if (warp == null) {
            return false;
        }
        warpOperator.delete(warp);
        return true;
    }
    
    /**
     * Teleports a player to a warp with warmup support.
     *
     * @param player the player
     * @param name   the warp name
     * @return result of the operation
     */
    public TeleportResult teleportToWarp(Player player, String name) {
        if (!config.isWarpEnabled()) {
            return TeleportResult.DISABLED;
        }
        
        // Check if already teleporting
        if (pendingTeleports.containsKey(player.getUniqueId())) {
            return TeleportResult.ALREADY_TELEPORTING;
        }
        
        WarpData warp = getWarp(name.toLowerCase().trim());
        if (warp == null) {
            return TeleportResult.NOT_FOUND;
        }
        
        // Check permission
        if (!canAccess(player, warp)) {
            return TeleportResult.NO_PERMISSION;
        }
        
        World world = Bukkit.getWorld(warp.getWorld());
        if (world == null) {
            return TeleportResult.WORLD_NOT_FOUND;
        }
        
        Location targetLocation = new Location(
            world,
            warp.getX(),
            warp.getY(),
            warp.getZ(),
            warp.getYaw(),
            warp.getPitch()
        );
        
        int warmup = config.getWarpTeleportWarmup();
        
        if (warmup <= 0 || player.hasPermission("ultiessentials.warp.nowarmup")) {
            player.teleport(targetLocation);
            return TeleportResult.SUCCESS;
        }
        
        return startWarmupTeleport(player, targetLocation, warmup);
    }
    
    /**
     * Starts a warmup teleport.
     */
    private TeleportResult startWarmupTeleport(Player player, Location target, int warmupSeconds) {
        UUID uuid = player.getUniqueId();
        
        teleportStartLocations.put(uuid, player.getLocation().clone());
        
        BukkitTask task = new BukkitRunnable() {
            int countdown = warmupSeconds;
            
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelTeleport(uuid);
                    cancel();
                    return;
                }
                
                // Check movement (reuse home config setting)
                if (config.isHomeCancelOnMove()) {
                    Location startLoc = teleportStartLocations.get(uuid);
                    if (startLoc != null && hasMovedTooFar(player.getLocation(), startLoc)) {
                        player.sendMessage(UltiEssentials.getInstance().i18n("传送已取消：你移动了"));
                        cancelTeleport(uuid);
                        cancel();
                        return;
                    }
                }
                
                if (countdown <= 0) {
                    player.teleport(target);
                    player.sendMessage(UltiEssentials.getInstance().i18n("传送成功！"));
                    cancelTeleport(uuid);
                    cancel();
                    return;
                }
                
                player.sendMessage(UltiEssentials.getInstance().i18n("传送中...") + " " + countdown + "s");
                countdown--;
            }
        }.runTaskTimer(UltiTools.getInstance(), 0L, 20L);
        
        pendingTeleports.put(uuid, task);
        return TeleportResult.WARMUP_STARTED;
    }
    
    /**
     * Cancels a pending teleport.
     */
    public void cancelTeleport(UUID uuid) {
        BukkitTask task = pendingTeleports.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        teleportStartLocations.remove(uuid);
    }
    
    /**
     * Checks if a player is currently teleporting.
     */
    public boolean isTeleporting(UUID uuid) {
        return pendingTeleports.containsKey(uuid);
    }
    
    private boolean hasMovedTooFar(Location current, Location start) {
        if (!Objects.equals(current.getWorld(), start.getWorld())) {
            return true;
        }
        return current.distanceSquared(start) > 1;
    }
    
    public enum WarpResult {
        CREATED,
        ALREADY_EXISTS,
        INVALID_NAME,
        DISABLED
    }
    
    public enum TeleportResult {
        SUCCESS,
        WARMUP_STARTED,
        NOT_FOUND,
        WORLD_NOT_FOUND,
        NO_PERMISSION,
        ALREADY_TELEPORTING,
        DISABLED
    }
}
