package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.WarpData;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import com.ultikits.ultitools.annotations.PostConstruct;
import java.util.*;

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
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    @Autowired
    private TeleportService teleportService;

    private DataOperator<WarpData> warpOperator;

    /**
     * Initializes the service with the data operator.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.warpOperator = plugin.getDataOperator(WarpData.class);
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
        return warpOperator.query()
            .where("name").eq(name.toLowerCase())
            .first();
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
        warpOperator.delById(warp.getId());
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
        if (teleportService.isTeleporting(player.getUniqueId())) {
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
        
        Location targetLocation = warp.toLocation();
        if (targetLocation == null) {
            return TeleportResult.WORLD_NOT_FOUND;
        }
        
        int warmup = config.getWarpTeleportWarmup();
        boolean skipWarmup = player.hasPermission("ultiessentials.warp.nowarmup");
        
        return teleportService.teleport(
            player, 
            targetLocation, 
            skipWarmup ? 0 : warmup, 
            config.isHomeCancelOnMove()  // Reuse home cancel on move setting
        );
    }
    
    /**
     * Cancels a pending teleport for a player.
     *
     * @param uuid the player's UUID
     */
    public void cancelTeleport(UUID uuid) {
        teleportService.cancelTeleport(uuid);
    }
    
    /**
     * Checks if a player is currently teleporting.
     *
     * @param uuid the player's UUID
     * @return true if teleporting
     */
    public boolean isTeleporting(UUID uuid) {
        return teleportService.isTeleporting(uuid);
    }
    
    public enum WarpResult {
        CREATED,
        ALREADY_EXISTS,
        INVALID_NAME,
        DISABLED
    }
}
