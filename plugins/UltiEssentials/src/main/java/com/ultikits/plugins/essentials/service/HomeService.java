package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.HomeData;
import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
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
    private EssentialsConfig config;
    
    @Autowired
    private TeleportService teleportService;
    
    private DataOperator<HomeData> homeOperator;
    
    /**
     * Initializes the service with the data operator.
     * Called by the plugin during startup.
     */
    public void init() {
        this.homeOperator = UltiEssentials.getInstance().getDataOperator(HomeData.class);
    }
    
    /**
     * Gets all homes for a player.
     *
     * @param playerUuid the player's UUID
     * @return list of homes
     */
    public List<HomeData> getHomes(UUID playerUuid) {
        return homeOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
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
        List<HomeData> homes = homeOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build(),
            WhereCondition.builder()
                .column("name")
                .value(name.toLowerCase())
                .build()
        );
        return homes.isEmpty() ? null : homes.get(0);
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
            homeOperator.update(existingHome);
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
        homeOperator.delete(home);
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
        
        World world = Bukkit.getWorld(home.getWorld());
        if (world == null) {
            return TeleportResult.WORLD_NOT_FOUND;
        }
        
        Location targetLocation = new Location(
            world, 
            home.getX(), 
            home.getY(), 
            home.getZ(), 
            home.getYaw(), 
            home.getPitch()
        );
        
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
        home.setWorld(loc.getWorld().getName());
        home.setX(loc.getX());
        home.setY(loc.getY());
        home.setZ(loc.getZ());
        home.setYaw(loc.getYaw());
        home.setPitch(loc.getPitch());
    }
    
    public enum SetHomeResult {
        CREATED,
        UPDATED,
        LIMIT_REACHED,
        INVALID_NAME,
        DISABLED
    }
}
