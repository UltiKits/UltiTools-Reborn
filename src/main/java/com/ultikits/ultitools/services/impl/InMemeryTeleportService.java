package com.ultikits.ultitools.services.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.services.TeleportService;

/**
 * 传送服务实现类
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class InMemeryTeleportService implements TeleportService {
    private final static Map<UUID, Boolean> teleportingPlayers = new HashMap<>();
    private final static Map<UUID, String> locationMap = new HashMap<>();

    private final static Map<UUID, Location> inMemoryLocationRecord = new HashMap<>();
    private static volatile boolean schedulerInitialized = false;

    /**
     * Initialize the movement check scheduler. Called lazily to avoid static initializer issues in tests.
     */
    static synchronized void initScheduler() {
        if (schedulerInitialized) {
            return;
        }
        try {
            new BukkitRunnable() {
                @Override
                public void run() {
                    checkAllPlayersMovement();
                }
            }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0, 10L);
            schedulerInitialized = true;
        } catch (Exception e) {
            // Scheduler initialization failed - likely in test environment
        }
    }

    /**
     * Check movement for all teleporting players.
     * This method is extracted from BukkitRunnable for testability.
     */
    static void checkAllPlayersMovement() {
        for (UUID playerUUID : teleportingPlayers.keySet()) {
            checkPlayerMovement(playerUUID);
        }
    }

    /**
     * Check if a single player has moved during teleport delay.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param playerUUID the UUID of the player to check
     * @return true if the player moved (teleport should be cancelled), false otherwise
     */
    static boolean checkPlayerMovement(UUID playerUUID) {
        if (!teleportingPlayers.getOrDefault(playerUUID, false)) {
            locationMap.put(playerUUID, null);
            return false;
        }
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) {
            return false;
        }
        Location location = player.getLocation();
        String currentLocation = location.getX() + "" + location.getY() + "" + location.getZ();
        if (locationMap.get(playerUUID) == null) {
            locationMap.put(playerUUID, currentLocation);
            return false;
        } else {
            String lastLocation = locationMap.get(playerUUID);
            if (!currentLocation.equals(lastLocation)) {
                teleportingPlayers.put(playerUUID, false);
                return true;
            }
        }
        return false;
    }

    @Override
    public void teleport(Player player, Location location) {
        inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
        player.teleport(location);
        player.playSound(player.getLocation(), UltiTools.getInstance().getVersionWrapper().getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
    }

    @Override
    public void delayTeleport(Player player, Location location, int delay) {
        initScheduler();
        teleportingPlayers.put(player.getUniqueId(), true);
        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load();
        }
        new BukkitRunnable() {
            float time = delay;

            @Override
            public void run() {
                DelayTeleportResult result = processDelayTeleportTick(player, location, time);
                time = result.remainingTime;
                if (result.shouldCancel) {
                    this.cancel();
                }
            }
        }.runTaskTimer(UltiTools.getInstance(), 0, 10L);
    }

    /**
     * Result object for delay teleport tick processing.
     */
    static class DelayTeleportResult {
        final boolean shouldCancel;
        final float remainingTime;

        DelayTeleportResult(boolean shouldCancel, float remainingTime) {
            this.shouldCancel = shouldCancel;
            this.remainingTime = remainingTime;
        }
    }

    /**
     * Process a single tick of the delay teleport countdown.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param player   the player being teleported
     * @param location the target location
     * @param time     the current remaining time
     * @return the result containing whether to cancel and the new remaining time
     */
    static DelayTeleportResult processDelayTeleportTick(Player player, Location location, float time) {
        if (!teleportingPlayers.getOrDefault(player.getUniqueId(), false)) {
            player.sendTitle(ChatColor.RED + UltiTools.getInstance().i18n("传送失败！"), UltiTools.getInstance().i18n("请勿移动！"), 10, 50, 20);
            return new DelayTeleportResult(true, time);
        }
        if (time == 0) {
            inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
            player.teleport(location);
            player.playSound(player.getLocation(), UltiTools.getInstance().getVersionWrapper().getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
            player.sendTitle(ChatColor.GREEN + UltiTools.getInstance().i18n("传送成功！"), "", 10, 50, 20);
            teleportingPlayers.put(player.getUniqueId(), false);
            return new DelayTeleportResult(true, time);
        }
        if ((time / 0.5 % 2) == 0) {
            player.sendTitle(ChatColor.GREEN + UltiTools.getInstance().i18n("传送中..."), String.format(UltiTools.getInstance().i18n("离传送还有%d秒"), (int) time), 10, 70, 20);
        }
        return new DelayTeleportResult(false, time - 0.5f);
    }

    /**
     * Set a player's teleporting status. Used for testing.
     */
    static void setTeleportingStatus(UUID playerUUID, boolean status) {
        teleportingPlayers.put(playerUUID, status);
    }

    /**
     * Get a player's teleporting status. Used for testing.
     */
    static Boolean getTeleportingStatus(UUID playerUUID) {
        return teleportingPlayers.get(playerUUID);
    }

    /**
     * Clear all teleporting data. Used for testing.
     */
    static void clearTeleportingData() {
        teleportingPlayers.clear();
        locationMap.clear();
    }

    @Override
    public Optional<Location> getLastTeleportLocation(UUID uuid) {
        return Optional.ofNullable(inMemoryLocationRecord.get(uuid));
    }

    @Override
    public String getName() {
        return "传送服务";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }
}
