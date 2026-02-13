package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.enums.TeleportResult;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Centralized teleport service with warmup support.
 * <p>
 * 统一的传送服务，支持预热传送、移动检测和取消功能。
 * 提取自 HomeService 和 WarpService 的重复代码。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class TeleportService {

    @Autowired
    private UltiToolsPlugin plugin;

    private Plugin bukkitPlugin;

    /**
     * Players currently in a teleport warmup.
     * Key: Player UUID, Value: BukkitTask for the warmup
     */
    private final Map<UUID, BukkitTask> pendingTeleports = new ConcurrentHashMap<>();
    
    /**
     * Start locations for movement detection.
     * Key: Player UUID, Value: Location when teleport started
     */
    private final Map<UUID, Location> teleportStartLocations = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    }

    /**
     * Teleports a player to a location with optional warmup.
     *
     * @param player       the player to teleport
     * @param target       the target location
     * @param warmupSeconds warmup time in seconds (0 or negative for instant)
     * @param cancelOnMove  whether to cancel if player moves
     * @return result of the operation
     */
    public TeleportResult teleport(Player player, Location target, int warmupSeconds, boolean cancelOnMove) {
        return teleport(player, target, warmupSeconds, cancelOnMove, null, null);
    }
    
    /**
     * Teleports a player to a location with optional warmup and callbacks.
     *
     * @param player        the player to teleport
     * @param target        the target location
     * @param warmupSeconds warmup time in seconds (0 or negative for instant)
     * @param cancelOnMove  whether to cancel if player moves
     * @param onSuccess     callback when teleport succeeds (can be null)
     * @param onCancel      callback when teleport is cancelled (can be null)
     * @return result of the operation
     */
    public TeleportResult teleport(Player player, Location target, int warmupSeconds, boolean cancelOnMove,
                                   Consumer<Player> onSuccess, Consumer<Player> onCancel) {
        UUID uuid = player.getUniqueId();
        
        // Check if already teleporting
        if (pendingTeleports.containsKey(uuid)) {
            return TeleportResult.ALREADY_TELEPORTING;
        }
        
        // Instant teleport if no warmup
        if (warmupSeconds <= 0) {
            player.teleport(target);
            if (onSuccess != null) {
                onSuccess.accept(player);
            }
            return TeleportResult.SUCCESS;
        }
        
        // Start warmup teleport
        return startWarmupTeleport(player, target, warmupSeconds, cancelOnMove, onSuccess, onCancel);
    }
    
    /**
     * Starts a warmup teleport with countdown.
     */
    private TeleportResult startWarmupTeleport(Player player, Location target, int warmupSeconds,
                                               boolean cancelOnMove, Consumer<Player> onSuccess, 
                                               Consumer<Player> onCancel) {
        UUID uuid = player.getUniqueId();
        
        // Store start location for movement detection
        teleportStartLocations.put(uuid, player.getLocation().clone());
        
        BukkitTask task = new BukkitRunnable() {
            int countdown = warmupSeconds;
            
            @Override
            public void run() {
                // Player left the server
                if (!player.isOnline()) {
                    cleanupTeleport(uuid);
                    cancel();
                    return;
                }
                
                // Check movement
                if (cancelOnMove) {
                    Location startLoc = teleportStartLocations.get(uuid);
                    if (startLoc != null && hasMovedTooFar(player.getLocation(), startLoc)) {
                        player.sendMessage(i18n("teleport_cancelled_moved"));
                        cleanupTeleport(uuid);
                        cancel();
                        if (onCancel != null) {
                            onCancel.accept(player);
                        }
                        return;
                    }
                }
                
                // Teleport when countdown reaches 0
                if (countdown <= 0) {
                    player.teleport(target);
                    player.sendMessage(i18n("teleport_success"));
                    cleanupTeleport(uuid);
                    cancel();
                    if (onSuccess != null) {
                        onSuccess.accept(player);
                    }
                    return;
                }
                
                // Show countdown message
                player.sendMessage(i18n("teleport_warmup") + " " + countdown + "s");
                countdown--;
            }
        }.runTaskTimer(bukkitPlugin, 0L, 20L);
        
        pendingTeleports.put(uuid, task);
        return TeleportResult.WARMUP_STARTED;
    }
    
    /**
     * Cancels a pending teleport for a player.
     *
     * @param uuid the player's UUID
     */
    public void cancelTeleport(UUID uuid) {
        BukkitTask task = pendingTeleports.get(uuid);
        if (task != null) {
            task.cancel();
        }
        cleanupTeleport(uuid);
    }
    
    /**
     * Cleans up teleport state for a player.
     */
    private void cleanupTeleport(UUID uuid) {
        pendingTeleports.remove(uuid);
        teleportStartLocations.remove(uuid);
    }
    
    /**
     * Checks if a player is currently in a teleport warmup.
     *
     * @param uuid the player's UUID
     * @return true if teleporting
     */
    public boolean isTeleporting(UUID uuid) {
        return pendingTeleports.containsKey(uuid);
    }
    
    /**
     * Checks if player has moved too far from start position.
     * Currently uses 1 block squared distance threshold.
     *
     * @param current the current location
     * @param start   the start location
     * @return true if moved too far
     */
    private boolean hasMovedTooFar(Location current, Location start) {
        if (!Objects.equals(current.getWorld(), start.getWorld())) {
            return true;
        }
        return current.distanceSquared(start) > 1; // More than 1 block
    }
    
    /**
     * Helper method to get i18n string.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
