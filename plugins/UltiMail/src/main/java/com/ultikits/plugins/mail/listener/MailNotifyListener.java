package com.ultikits.plugins.mail.listener;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Listener for mail notifications with clickable messages.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@EventListener
public class MailNotifyListener implements Listener {

    private Plugin bukkitPlugin;

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private MailService mailService;

    @Autowired
    private MailConfig config;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isNotifyOnJoin()) {
            return;
        }
        
        Player player = event.getPlayer();
        
        // Lazy init bukkitPlugin
        if (bukkitPlugin == null) {
            bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        }

        // Delay notification
        Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            
            int unreadCount = mailService.getUnreadCount(player.getUniqueId());
            if (unreadCount > 0) {
                sendClickableNotification(player, unreadCount);
            }
        }, config.getNotifyDelay() * 20L);
    }
    
    /**
     * Send a clickable notification message using Spigot API.
     *
     * @param player the player to notify
     * @param unreadCount the number of unread mails
     */
    private void sendClickableNotification(Player player, int unreadCount) {
        String messageTemplate = i18n("notify_new_mail");
        String clickText = i18n("notify_click_to_view");
        String hoverText = i18n("notify_hover_hint");
        
        // Build the main message using Spigot/BungeeCord Chat API
        TextComponent prefix = new TextComponent("✉ ");
        prefix.setColor(ChatColor.GOLD);
        
        TextComponent message = new TextComponent(messageTemplate.replace("{COUNT}", String.valueOf(unreadCount)));
        message.setColor(ChatColor.YELLOW);
        
        TextComponent space = new TextComponent(" ");
        
        TextComponent clickable = new TextComponent("[" + clickText + "]");
        clickable.setColor(ChatColor.GREEN);
        clickable.setBold(true);
        clickable.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mail read"));
        clickable.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverText)));
        
        // Send the composed message
        player.spigot().sendMessage(prefix, message, space, clickable);
    }
    
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
