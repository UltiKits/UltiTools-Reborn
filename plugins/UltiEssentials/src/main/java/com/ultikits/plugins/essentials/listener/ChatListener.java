package com.ultikits.plugins.essentials.listener;

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
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Listener for customizing chat format.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class ChatListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!config.isChatFormatEnabled()) {
            return;
        }
        
        Player player = event.getPlayer();
        String message = event.getMessage();
        
        // Check color permission
        if (player.hasPermission("ultiessentials.chat.color")) {
            message = colorize(message);
        }
        
        // Get format template
        String format = config.getChatFormat();
        
        // Replace placeholders
        format = parsePlaceholders(player, format);
        
        // Replace message placeholder
        format = format.replace("{message}", "%2$s");
        
        // Replace basic placeholders
        format = format.replace("{player}", player.getName());
        format = format.replace("{displayname}", player.getDisplayName());
        format = format.replace("{world}", player.getWorld().getName());
        
        // Colorize the format
        format = colorize(format);
        
        event.setFormat(format);
        event.setMessage(message);
    }
    
    /**
     * Parses PlaceholderAPI placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text;
    }
    
    /**
     * Colorizes a string with color codes.
     */
    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
