package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.HomeData;
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
 * Service for managing player homes.
 * <p>
 * 管理玩家家位置的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class HomeService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    @Autowired
    private TeleportService teleportService;

    private DataOperator<HomeData> homeOperator;

    /**
     * Initializes the service with the data operator.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.homeOperator = plugin.getDataOperator(HomeData.class);
    }
    
    /**
     * Gets all homes for a player.
     *
     * @param playerUuid the player's UUID
     * @return list of homes
     */
    public List<HomeData> getHomes(UUID playerUuid) {
        return homeOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();
    }
    
    /**
     * Gets a specific home by name.
     *
     * @param playerUuid the player's UUID
     * @param name       the home name
     * @return the home data, or null if not found
     */
    @Nullable
    public HomeData getHome(UUID playerUuid, String name) {
        return homeOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .where("name").eq(name.toLowerCase())
            .first();
    }
    
    /**
     * Gets the number of homes a player has.
     *
     * @param playerUuid the player's UUID
     * @return the number of homes
     */
    public int getHomeCount(UUID playerUuid) {
        return getHomes(playerUuid).size();
    }
    
    /**
     * Gets the maximum number of homes allowed for a player.
     * Can be extended with permission-based limits.
     *
     * @param player the player
     * @return the maximum number of homes
     */
    public int getMaxHomes(Player player) {
        // Check for permission-based limits (ultiessentials.home.max.<number>)
        for (int i = 100; i >= 1; i--) {
            if (player.hasPermission("ultiessentials.home.max." + i)) {
                return i;
            }
        }
        // Check for unlimited permission
        if (player.hasPermission("ultiessentials.home.unlimited")) {
            return Integer.MAX_VALUE;
        }
        return config.getHomeDefaultMaxHomes();
    }
    
    /**
     * Creates or updates a home.
     *
     * @param player the player
     * @param name   the home name
     * @return result of the operation
     */
    public SetHomeResult setHome(Player player, String name) {
        if (!config.isHomeEnabled()) {
            return SetHomeResult.DISABLED;
        }
        
        String normalizedName = name.toLowerCase().trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 32) {
            return SetHomeResult.INVALID_NAME;
        }
        
        UUID playerUuid = player.getUniqueId();
        HomeData existingHome = getHome(playerUuid, normalizedName);
        
        if (existingHome != null) {
            // Update existing home
            updateHomeLocation(existingHome, player.getLocation());
            try {
                homeOperator.update(existingHome);
            } catch (IllegalAccessException e) {
                log.error("Failed to update home", e);
            }
            return SetHomeResult.UPDATED;
        }
        
        // Check limit
        int currentCount = getHomeCount(playerUuid);
        int maxHomes = getMaxHomes(player);
        
        if (currentCount >= maxHomes) {
            return SetHomeResult.LIMIT_REACHED;
        }
        
        // Create new home
        Location loc = player.getLocation();
        HomeData newHome = HomeData.builder()
            .uuid(UUID.randomUUID())
            .playerUuid(playerUuid.toString())
            .name(normalizedName)
            .world(loc.getWorld().getName())
            .x(loc.getX())
            .y(loc.getY())
            .z(loc.getZ())
            .yaw(loc.getYaw())
            .pitch(loc.getPitch())
            .createdAt(System.currentTimeMillis())
            .build();
        
        homeOperator.insert(newHome);
        return SetHomeResult.CREATED;
    }
    
    /**
     * Deletes a home.
     *
     * @param playerUuid the player's UUID
     * @param name       the home name
     * @return true if deleted, false if not found
     */
    public boolean deleteHome(UUID playerUuid, String name) {
        HomeData home = getHome(playerUuid, name.toLowerCase().trim());
        if (home == null) {
            return false;
        }
        homeOperator.delById(home.getId());
        return true;
    }
    
    /**
     * Teleports a player to their home with warmup support.
     *
     * @param player the player
     * @param name   the home name
     * @return result of the operation
     */
    public TeleportResult teleportToHome(Player player, String name) {
        if (!config.isHomeEnabled()) {
            return TeleportResult.DISABLED;
        }
        
        // Check if already teleporting
        if (teleportService.isTeleporting(player.getUniqueId())) {
            return TeleportResult.ALREADY_TELEPORTING;
        }
        
        HomeData home = getHome(player.getUniqueId(), name.toLowerCase().trim());
        if (home == null) {
            return TeleportResult.NOT_FOUND;
        }
        
        Location targetLocation = home.toLocation();
        if (targetLocation == null) {
            return TeleportResult.WORLD_NOT_FOUND;
        }
        
        int warmup = config.getHomeTeleportWarmup();
        boolean skipWarmup = player.hasPermission("ultiessentials.home.nowarmup");
        
        return teleportService.teleport(
            player, 
            targetLocation, 
            skipWarmup ? 0 : warmup, 
            config.isHomeCancelOnMove()
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
    
    private void updateHomeLocation(HomeData home, Location loc) {
        home.fromLocation(loc);
    }
    
    public enum SetHomeResult {
        CREATED,
        UPDATED,
        LIMIT_REACHED,
        INVALID_NAME,
        DISABLED
    }
}
