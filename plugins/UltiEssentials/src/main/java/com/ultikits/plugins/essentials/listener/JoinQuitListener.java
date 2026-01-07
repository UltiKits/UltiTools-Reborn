package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

/**
 * Listener for player join/quit messages and welcome messages.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class JoinQuitListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Custom join message
        if (config.isJoinMessageEnabled()) {
            String joinMsg = config.getJoinMessageFormat();
            joinMsg = parsePlaceholders(player, joinMsg);
            joinMsg = colorize(joinMsg);
            event.setJoinMessage(joinMsg);
        }
        
        // Welcome message
        if (config.isJoinWelcomeEnabled()) {
            sendWelcomeMessage(player);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!config.isQuitMessageEnabled()) {
            return;
        }
        
        Player player = event.getPlayer();
        String quitMsg = config.getQuitMessageFormat();
        quitMsg = parsePlaceholders(player, quitMsg);
        quitMsg = colorize(quitMsg);
        event.setQuitMessage(quitMsg);
    }
    
    /**
     * Sends welcome message to a player.
     */
    private void sendWelcomeMessage(Player player) {
        List<String> welcomeLines = config.getWelcomeMessageLines();
        
        for (String line : welcomeLines) {
            line = parsePlaceholders(player, line);
            line = colorize(line);
            player.sendMessage(line);
        }
    }
    
    /**
     * Parses PlaceholderAPI placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        
        // Basic fallbacks
        text = text.replace("%player_name%", player.getName());
        text = text.replace("{player}", player.getName());
        text = text.replace("{displayname}", player.getDisplayName());
        text = text.replace("%online_players%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        text = text.replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()));
        
        return text;
    }
    
    /**
     * Colorizes a string with color codes.
     */
    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
