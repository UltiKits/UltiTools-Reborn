package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
import com.ultikits.ultitools.annotations.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing TPA (teleport ask) requests.
 * <p>
 * 管理 TPA（传送请求）的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class TpaService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    private Plugin bukkitPlugin;

    /**
     * Active TPA requests: target UUID -> request
     */
    private final Map<UUID, TpaRequest> activeRequests = new ConcurrentHashMap<>();
    
    /**
     * Cooldown tracking: sender UUID -> last request time
     */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    
    /**
     * Timeout tasks for auto-expiration
     */
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();
    
    /**
     * Initializes the service.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    }
    
    /**
     * Sends a TPA request (teleport to target).
     *
     * @param sender the player sending the request
     * @param target the target player
     * @return result of the operation
     */
    public TpaResult sendTpaRequest(Player sender, Player target) {
        return sendRequest(sender, target, TpaType.TPA);
    }
    
    /**
     * Sends a TPA-here request (target teleport to sender).
     *
     * @param sender the player sending the request
     * @param target the target player
     * @return result of the operation
     */
    public TpaResult sendTpaHereRequest(Player sender, Player target) {
        return sendRequest(sender, target, TpaType.TPA_HERE);
    }
    
    /**
     * Common method for sending TPA requests.
     */
    private TpaResult sendRequest(Player sender, Player target, TpaType type) {
        if (!config.isTpaEnabled()) {
            return TpaResult.DISABLED;
        }
        
        if (sender.equals(target)) {
            return TpaResult.SELF_REQUEST;
        }
        
        // Check cross-world
        if (!config.isTpaAllowCrossWorld() && 
            !sender.getWorld().equals(target.getWorld())) {
            return TpaResult.CROSS_WORLD_DISABLED;
        }
        
        // Check cooldown
        if (isOnCooldown(sender.getUniqueId())) {
            return TpaResult.ON_COOLDOWN;
        }
        
        // Check if target already has a pending request
        if (activeRequests.containsKey(target.getUniqueId())) {
            return TpaResult.TARGET_BUSY;
        }
        
        // Create request
        TpaRequest request = new TpaRequest(
            sender.getUniqueId(),
            target.getUniqueId(),
            type,
            System.currentTimeMillis()
        );
        
        activeRequests.put(target.getUniqueId(), request);
        
        // Update cooldown
        cooldowns.put(sender.getUniqueId(), System.currentTimeMillis());
        
        // Start timeout task
        startTimeoutTask(target.getUniqueId());
        
        return TpaResult.SENT;
    }
    
    /**
     * Accepts a pending TPA request.
     *
     * @param target the target player (who received the request)
     * @return result of the operation
     */
    public TpaResult acceptRequest(Player target) {
        if (!config.isTpaEnabled()) {
            return TpaResult.DISABLED;
        }
        
        TpaRequest request = activeRequests.get(target.getUniqueId());
        if (request == null) {
            return TpaResult.NO_REQUEST;
        }
        
        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender == null || !sender.isOnline()) {
            cancelRequest(target.getUniqueId());
            return TpaResult.SENDER_OFFLINE;
        }
        
        // Perform teleport based on type
        if (request.getType() == TpaType.TPA) {
            // Sender teleports to target
            sender.teleport(target.getLocation());
            sender.sendMessage(plugin.i18n("传送成功！"));
        } else {
            // Target teleports to sender
            target.teleport(sender.getLocation());
            target.sendMessage(plugin.i18n("传送成功！"));
        }
        
        cancelRequest(target.getUniqueId());
        return TpaResult.ACCEPTED;
    }
    
    /**
     * Denies a pending TPA request.
     *
     * @param target the target player
     * @return result of the operation
     */
    public TpaResult denyRequest(Player target) {
        TpaRequest request = activeRequests.get(target.getUniqueId());
        if (request == null) {
            return TpaResult.NO_REQUEST;
        }
        
        // Notify sender
        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(plugin.i18n("你的传送请求被拒绝了"));
        }
        
        cancelRequest(target.getUniqueId());
        return TpaResult.DENIED;
    }
    
    /**
     * Cancels a pending request (called by sender or on timeout).
     *
     * @param targetUuid the target's UUID
     */
    public void cancelRequest(UUID targetUuid) {
        activeRequests.remove(targetUuid);
        BukkitTask task = timeoutTasks.remove(targetUuid);
        if (task != null) {
            task.cancel();
        }
    }
    
    /**
     * Gets the pending request for a target.
     *
     * @param targetUuid the target's UUID
     * @return the request, or null if none
     */
    @Nullable
    public TpaRequest getRequest(UUID targetUuid) {
        return activeRequests.get(targetUuid);
    }
    
    /**
     * Checks if a player is on cooldown.
     */
    public boolean isOnCooldown(UUID uuid) {
        Long lastRequest = cooldowns.get(uuid);
        if (lastRequest == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - lastRequest;
        return elapsed < config.getTpaCooldown() * 1000L;
    }
    
    /**
     * Gets remaining cooldown time in seconds.
     */
    public int getRemainingCooldown(UUID uuid) {
        Long lastRequest = cooldowns.get(uuid);
        if (lastRequest == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - lastRequest;
        long remaining = config.getTpaCooldown() * 1000L - elapsed;
        return (int) Math.max(0, remaining / 1000);
    }
    
    /**
     * Starts a timeout task for auto-expiration.
     */
    private void startTimeoutTask(UUID targetUuid) {
        int timeout = config.getTpaTimeout();
        
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                TpaRequest request = activeRequests.get(targetUuid);
                if (request != null) {
                    // Notify both players
                    Player sender = Bukkit.getPlayer(request.getSenderUuid());
                    Player target = Bukkit.getPlayer(targetUuid);
                    
                    if (sender != null && sender.isOnline()) {
                        sender.sendMessage(plugin.i18n("传送请求已超时"));
                    }
                    if (target != null && target.isOnline()) {
                        target.sendMessage(plugin.i18n("传送请求已超时"));
                    }
                    
                    cancelRequest(targetUuid);
                }
            }
        }.runTaskLater(bukkitPlugin, timeout * 20L);
        
        timeoutTasks.put(targetUuid, task);
    }
    
    /**
     * Cleans up when a player quits.
     */
    public void onPlayerQuit(UUID uuid) {
        // Cancel any request where player is target
        cancelRequest(uuid);
        
        // Cancel any request where player is sender
        activeRequests.entrySet().removeIf(entry -> {
            if (entry.getValue().getSenderUuid().equals(uuid)) {
                BukkitTask task = timeoutTasks.remove(entry.getKey());
                if (task != null) {
                    task.cancel();
                }
                return true;
            }
            return false;
        });
    }
    
    public enum TpaType {
        TPA,        // Sender wants to teleport to target
        TPA_HERE    // Sender wants target to teleport to them
    }
    
    public enum TpaResult {
        SENT,
        ACCEPTED,
        DENIED,
        NO_REQUEST,
        SELF_REQUEST,
        TARGET_BUSY,
        SENDER_OFFLINE,
        ON_COOLDOWN,
        CROSS_WORLD_DISABLED,
        DISABLED
    }
    
    /**
     * Represents a TPA request.
     */
    public static class TpaRequest {
        private final UUID senderUuid;
        private final UUID targetUuid;
        private final TpaType type;
        private final long timestamp;
        
        public TpaRequest(UUID senderUuid, UUID targetUuid, TpaType type, long timestamp) {
            this.senderUuid = senderUuid;
            this.targetUuid = targetUuid;
            this.type = type;
            this.timestamp = timestamp;
        }
        
        public UUID getSenderUuid() {
            return senderUuid;
        }
        
        public UUID getTargetUuid() {
            return targetUuid;
        }
        
        public TpaType getType() {
            return type;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
}
