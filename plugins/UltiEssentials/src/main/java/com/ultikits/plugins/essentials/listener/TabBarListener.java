package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.TabBarConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listener for customizing player tab list header and footer.
 */
@EventListener
public class TabBarListener implements Listener {

    @Autowired
    private EssentialsConfig config;
    
    @Autowired
    private TabBarConfig tabBarConfig;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isTabBarEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        updateTabBar(player);
    }

    /**
     * Updates the player's tab list header and footer.
     *
     * @param player the player to update
     */
    public void updateTabBar(Player player) {
        String header = tabBarConfig.getHeader();
        String footer = tabBarConfig.getFooter();

        // Replace variables
        header = header
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));

        footer = footer
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));

        // Color codes
        header = ChatColor.translateAlternateColorCodes('&', header);
        footer = ChatColor.translateAlternateColorCodes('&', footer);

        player.setPlayerListHeaderFooter(header, footer);
    }
}
