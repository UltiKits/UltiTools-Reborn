package com.ultikits.plugins.essentials.listeners;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.plugins.essentials.config.WelcomeConfig;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listener for player join welcome messages and first-join teleportation.
 */
@EventListener
public class JoinWelcomeListener implements Listener {

    private final EssentialsConfig config;
    private final WelcomeConfig welcomeConfig;
    private final SpawnConfig spawnConfig;

    public JoinWelcomeListener(EssentialsConfig config, WelcomeConfig welcomeConfig, SpawnConfig spawnConfig) {
        this.config = config;
        this.welcomeConfig = welcomeConfig;
        this.spawnConfig = spawnConfig;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Welcome message
        if (config.isJoinWelcomeEnabled()) {
            handleWelcomeMessage(event, player);
        }

        // First join teleport to spawn
        if (config.isSpawnEnabled() && spawnConfig.isTeleportOnFirstJoin() && !player.hasPlayedBefore()) {
            if (spawnConfig.getSpawnLocation().getWorld() != null) {
                player.teleport(spawnConfig.getSpawnLocation());
            }
        }
    }

    /**
     * Handles the welcome message for joining players.
     *
     * @param event  the join event
     * @param player the joining player
     */
    private void handleWelcomeMessage(PlayerJoinEvent event, Player player) {
        String message;

        if (!player.hasPlayedBefore()) {
            // First join message
            message = welcomeConfig.getFirstJoinMessage();
        } else {
            message = welcomeConfig.getMessage();
        }

        message = message.replace("%player%", player.getName());
        message = ChatColor.translateAlternateColorCodes('&', message);

        if (welcomeConfig.isBroadcast()) {
            event.setJoinMessage(message);
        } else {
            event.setJoinMessage(null);
            player.sendMessage(message);
        }

        // Display Title
        if (welcomeConfig.isTitleEnabled()) {
            String title = ChatColor.translateAlternateColorCodes('&',
                    welcomeConfig.getTitleMain().replace("%player%", player.getName()));
            String subtitle = ChatColor.translateAlternateColorCodes('&',
                    welcomeConfig.getTitleSub().replace("%player%", player.getName()));

            player.sendTitle(title, subtitle, 10, 70, 20);
        }
    }
}
