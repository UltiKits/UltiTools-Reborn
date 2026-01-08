package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.config.SpawnConfig;
import com.ultikits.plugins.essentials.config.WelcomeConfig;
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
 * Unified listener for player join/quit messages, welcome messages, and first-join actions.
 * <p>
 * Handles:
 * <ul>
 *   <li>Custom join/quit messages</li>
 *   <li>Welcome messages (line-based or formatted)</li>
 *   <li>First-join teleport to spawn</li>
 *   <li>Welcome title display</li>
 * </ul>
 *
 * @author wisdomme
 * @version 1.1.0
 */
@EventListener
public class JoinQuitListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    @Autowired
    private WelcomeConfig welcomeConfig;
    
    @Autowired
    private SpawnConfig spawnConfig;
    
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
        
        // Welcome message (multiline from EssentialsConfig)
        if (config.isJoinWelcomeEnabled()) {
            sendWelcomeMessage(player);
        }
        
        // First join teleport to spawn
        if (config.isSpawnEnabled() && spawnConfig.isTeleportOnFirstJoin() && !player.hasPlayedBefore()) {
            if (spawnConfig.getSpawnLocation() != null && spawnConfig.getSpawnLocation().getWorld() != null) {
                player.teleport(spawnConfig.getSpawnLocation());
            }
        }
        
        // Welcome title
        if (welcomeConfig.isTitleEnabled()) {
            sendWelcomeTitle(player);
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
     * Sends welcome message to a player (multiline).
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
     * Sends welcome title to a player.
     */
    private void sendWelcomeTitle(Player player) {
        String title = welcomeConfig.getTitleMain();
        String subtitle = welcomeConfig.getTitleSub();
        
        title = parsePlaceholders(player, title);
        subtitle = parsePlaceholders(player, subtitle);
        title = colorize(title);
        subtitle = colorize(subtitle);
        
        player.sendTitle(title, subtitle, 10, 70, 20);
    }
    
    /**
     * Parses PlaceholderAPI placeholders and basic fallbacks.
     */
    private String parsePlaceholders(Player player, String text) {
        if (text == null) {
            return "";
        }
        
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        
        // Basic fallbacks
        text = text.replace("%player_name%", player.getName());
        text = text.replace("%player%", player.getName());
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
        if (text == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
