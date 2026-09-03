package com.ultikits.ultitools.annotations;

import java.util.UUID;

/**
 * Optional interface for services with @PlayerCache(saveBeforeRemove = true).
 * Called before the cache entry is removed on player quit.
 *
 * @since 6.2.0
 */
public interface PlayerCacheSaver {

    /**
     * Save any pending data for the given player before cache eviction.
     *
     * @param playerId the UUID of the player quitting
     */
    void savePlayerData(UUID playerId);
}
