package com.ultikits.ultitools.services.impl;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import static com.ultikits.ultitools.services.impl.ServiceKeys.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.Ticks;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import java.time.Duration;
import java.util.function.Consumer;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.services.TeleportService;

/**
 * Teleport service implementation.
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
            FrameworkScheduling.scheduler().global().runRepeating(
                    Duration.ZERO, Ticks.of(10), handle -> checkAllPlayersMovement());
            schedulerInitialized = true;
        } catch (Exception e) {
            // Scheduler initialization failed - likely in test environment
        }
    }

    /**
     * Check movement for all teleporting players.
     * This method is extracted from the scheduled task for testability.
     */
    static void checkAllPlayersMovement() {
        for (UUID playerUUID : teleportingPlayers.keySet()) {
            checkPlayerMovement(playerUUID);
        }
    }

    /**
     * Check if a single player has moved during teleport delay.
     * This method is extracted from the scheduled task for testability.
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
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 0);
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
        FrameworkScheduling.scheduler().entity(player).runRepeating(
                Duration.ZERO, Ticks.of(10), new Consumer<TaskHandle>() {
                    private float time = delay;

                    @Override
                    public void accept(TaskHandle handle) {
                        DelayTeleportResult result = processDelayTeleportTick(player, location, time);
                        time = result.remainingTime;
                        if (result.shouldCancel) {
                            handle.cancel();
                        }
                    }
                });
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
     * This method is extracted from the scheduled task for testability.
     *
     * @param player   the player being teleported
     * @param location the target location
     * @param time     the current remaining time
     * @return the result containing whether to cancel and the new remaining time
     */
    static DelayTeleportResult processDelayTeleportTick(Player player, Location location, float time) {
        if (!teleportingPlayers.getOrDefault(player.getUniqueId(), false)) {
            var messages = FrameworkMessages.messages();
            player.showTitle(Title.title(messages.render(TELEPORT_FAILED_TITLE, player),
                    messages.render(TELEPORT_FAILED_SUBTITLE, player), times(10, 50, 20)));
            return new DelayTeleportResult(true, time);
        }
        if (time == 0) {
            inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
            player.teleport(location);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 0);
            player.showTitle(Title.title(FrameworkMessages.messages().render(TELEPORT_SUCCESS_TITLE, player),
                    Component.empty(), times(10, 50, 20)));
            teleportingPlayers.put(player.getUniqueId(), false);
            return new DelayTeleportResult(true, time);
        }
        if ((time / 0.5 % 2) == 0) {
            var messages = FrameworkMessages.messages();
            player.showTitle(Title.title(messages.render(TELEPORT_PENDING_TITLE, player),
                    messages.render(TELEPORT_PENDING_SUBTITLE, player, Arg.number("seconds", (int) time)), times(10, 70, 20)));
        }
        return new DelayTeleportResult(false, time - 0.5f);
    }

    private static Title.Times times(long fadeIn, long stay, long fadeOut) {
        return Title.Times.times(net.kyori.adventure.util.Ticks.duration(fadeIn),
                net.kyori.adventure.util.Ticks.duration(stay), net.kyori.adventure.util.Ticks.duration(fadeOut));
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

    /**
     * The display name. English, named after the service interface, like {@code DefaultEmailService}'s
     * {@code "EmailService"}; it is no longer the folder name, see {@link #getResourceFolderName()}.
     */
    @Override
    public String getName() {
        return "TeleportService";
    }

    /**
     * The resource folder name, kept at its pre-6.3.0 value so an existing install keeps its folder.
     * {@link com.ultikits.ultitools.interfaces.BaseService#getResourceFolderName()} defaults to
     * {@link #getName()}, which changed; this override pins the folder instead. It is an identifier,
     * not text shown to anyone.
     */
    @Override
    public String getResourceFolderName() {
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
