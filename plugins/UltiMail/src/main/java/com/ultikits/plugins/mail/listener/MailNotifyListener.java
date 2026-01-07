package com.ultikits.plugins.mail.listener;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Listener for mail notifications.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class MailNotifyListener implements Listener {
    
    @Autowired
    private MailService mailService;
    
    @Autowired
    private MailConfig config;
    
    @Autowired
    private Plugin plugin;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isNotifyOnJoin()) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Delay notification
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            
            int unreadCount = mailService.getUnreadCount(player.getUniqueId());
            if (unreadCount > 0) {
                String message = config.getNewMailMessage().replace("{COUNT}", String.valueOf(unreadCount));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }, config.getNotifyDelay() * 20L);
    }
}
