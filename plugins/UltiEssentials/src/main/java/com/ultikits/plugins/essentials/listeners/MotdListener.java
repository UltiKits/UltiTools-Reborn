package com.ultikits.plugins.essentials.listeners;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.MotdConfig;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

/**
 * Listener for customizing server MOTD (Message of the Day).
 */
@EventListener
public class MotdListener implements Listener {

    private final EssentialsConfig config;
    private final MotdConfig motdConfig;

    public MotdListener(EssentialsConfig config, MotdConfig motdConfig) {
        this.config = config;
        this.motdConfig = motdConfig;
    }

    @EventHandler
    public void onServerListPing(ServerListPingEvent event) {
        if (!config.isMotdEnabled()) {
            return;
        }

        String line1 = ChatColor.translateAlternateColorCodes('&', motdConfig.getLine1());
        String line2 = ChatColor.translateAlternateColorCodes('&', motdConfig.getLine2());

        event.setMotd(line1 + "\n" + line2);

        if (motdConfig.getMaxPlayers() > 0) {
            event.setMaxPlayers(motdConfig.getMaxPlayers());
        }
    }
}
