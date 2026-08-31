package com.ultikits.ultitools.services.impl;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.utils.XVersionUtils;

/**
 * 传送服务实现类
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class InMemeryTeleportService implements TeleportService {
    @PlayerCache
    private final static Map<UUID, Boolean> teleportingPlayers = new ConcurrentHashMap<>();
    @PlayerCache
    private final static Map<UUID, String> locationMap = new ConcurrentHashMap<>();

    @PlayerCache
    private final static Map<UUID, Location> inMemoryLocationRecord = new ConcurrentHashMap<>();
    private static volatile boolean schedulerInitialized = false;

    /**
     * True once ANY instance of this class has registered {@link #teleportingPlayers}/{@link
     * #locationMap}/{@link #inMemoryLocationRecord} -- static fields shared by every instance --
     * with the live {@link PlayerCacheManager} for quit-based sweeping (GEN-08, D-03). A STATIC
     * flag, not per-instance, mirroring the identical rationale on {@code
     * InMemoryNotificationService#playerCacheRegistered}: only the first instance to reach {@link
     * #delayTeleport(Player, Location, int)} registers. Set lazily rather than in a constructor
     * for the same reason as {@code CooldownValidator}'s field of the same name: a bare {@code
     * new InMemeryTeleportService()} must never attempt contact with a core plugin that may not
     * exist yet.
     */
    private static volatile boolean playerCacheRegistered = false;

    /**
     * Attempts lazy first-use registration of THIS instance -- as the reference-identity handle
     * for the three shared static fields above -- with the live {@link PlayerCacheManager}
     * singleton. See {@link #playerCacheRegistered}'s javadoc for the static-flag rationale.
     */
    private void ensurePlayerCacheRegistered() {
        if (playerCacheRegistered) {
            return;
        }
        UltiTools instance = UltiTools.getInstance();
        // Checking getPluginManager() too, not just getInstance(), matters especially here:
        // playerCacheRegistered is a STATIC flag shared by every instance, so an earlier test
        // (or an earlier caller) reaching this method while getInstance() is stubbed but
        // getPluginManager() is not would otherwise latch it true forever, permanently skipping
        // the retry a later, genuinely-live chain would have needed.
        if (instance == null || instance.getPluginManager() == null) {
            return;
        }
        PlayerCacheManager.tryRegister(this);
        playerCacheRegistered = true;
    }

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
            // ConcurrentHashMap.put(key, null) throws NullPointerException, unlike the HashMap
            // this field used to be -- remove() is the value-agnostic equivalent: absent from
            // the map reads as null via get() either way (D-05/GEN-08 concurrency conversion).
            locationMap.remove(playerUUID);
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
        ensurePlayerCacheRegistered();
        inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
        player.teleport(location);
        player.playSound(player.getLocation(), XVersionUtils.getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
    }

    @Override
    public void delayTeleport(Player player, Location location, int delay) {
        ensurePlayerCacheRegistered();
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
            player.playSound(player.getLocation(), XVersionUtils.getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
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
