package com.ultikits.plugins.essentials.listeners;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Listener for handling player respawn teleportation.
 */
@EventListener
public class RespawnListener implements Listener {

    private final EssentialsConfig config;
    private final SpawnConfig spawnConfig;

    public RespawnListener(EssentialsConfig config, SpawnConfig spawnConfig) {
        this.config = config;
        this.spawnConfig = spawnConfig;
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!config.isSpawnEnabled()) {
            return;
        }

        if (!spawnConfig.isTeleportOnRespawn()) {
            return;
        }

        if (spawnConfig.getSpawnLocation().getWorld() != null) {
            event.setRespawnLocation(spawnConfig.getSpawnLocation());
        }
    }
}
