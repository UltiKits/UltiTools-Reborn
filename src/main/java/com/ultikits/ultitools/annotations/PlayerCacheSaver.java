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
     * <p>
     * Called once per {@code @PlayerCache(saveBeforeRemove = true)} field of the implementing
     * bean. An exception or {@link Error} thrown here is logged as a WARNING by the framework and
     * does not keep the entry: it is removed anyway, and the cleanup of every other field
     * continues (as of 6.3.0, #643; maintainer decision, 2026-10-08). The framework does not retry
     * the save; a module that must not lose the data retries or persists it itself, here.
     *
     * @param playerId the UUID of the player quitting
     */
    void savePlayerData(UUID playerId);
}
