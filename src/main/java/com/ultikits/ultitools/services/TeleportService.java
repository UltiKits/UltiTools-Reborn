package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.BaseService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Teleport service.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface TeleportService extends BaseService {
    /**
     * Teleport player instantly.
     *
     * @param player   player
     * @param location location
     */
    void teleport(Player player, Location location);

    /**
     * Delay teleport player.
     *
     * @param player   player
     * @param location location
     * @param delay    delay
     */
    void delayTeleport(Player player, Location location, int delay);

    /**
     * Get last teleport location of the player (before teleport).
     *
     * @param uuid player uuid
     * @return last teleport location
     */
    Optional<Location> getLastTeleportLocation(UUID uuid);
}
