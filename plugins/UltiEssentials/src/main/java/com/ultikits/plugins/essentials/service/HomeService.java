package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.HomeData;
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
    
    private DataOperator<HomeData> homeOperator;
    
    // 传送中的玩家 (防止重复传送)
    private final Map<UUID, BukkitTask> pendingTeleports = new ConcurrentHashMap<>();
    
    // 传送前的位置 (用于取消传送时的移动检测)
    private final Map<UUID, Location> teleportStartLocations = new ConcurrentHashMap<>();
    
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
        if (pendingTeleports.containsKey(player.getUniqueId())) {
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
        
        if (warmup <= 0 || player.hasPermission("ultiessentials.home.nowarmup")) {
            // Instant teleport
            player.teleport(targetLocation);
            return TeleportResult.SUCCESS;
        }
        
        // Warmup teleport
        return startWarmupTeleport(player, targetLocation, warmup);
    }
    
    /**
     * Starts a warmup teleport.
     */
    private TeleportResult startWarmupTeleport(Player player, Location target, int warmupSeconds) {
        UUID uuid = player.getUniqueId();
        
        // Store start location for movement detection
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
                
                // Check movement
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
        return current.distanceSquared(start) > 1; // More than 1 block
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
    
    public enum TeleportResult {
        SUCCESS,
        WARMUP_STARTED,
        NOT_FOUND,
        WORLD_NOT_FOUND,
        ALREADY_TELEPORTING,
        DISABLED
    }
}
