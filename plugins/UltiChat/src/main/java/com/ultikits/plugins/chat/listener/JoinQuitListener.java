package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChatConfig;
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
 * Handles custom join/quit messages, welcome messages, titles, and first-join broadcasts.
 * 处理自定义进入/离开消息、欢迎消息、标题和首次加入广播。
 */
@EventListener
public class JoinQuitListener implements Listener {

    @Autowired
    private ChatConfig config;

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

        // Welcome message lines
        if (config.isWelcomeEnabled()) {
            sendWelcomeMessage(player);
        }

        // Welcome title
        if (config.isTitleEnabled()) {
            sendWelcomeTitle(player);
        }

        // First join broadcast
        if (!player.hasPlayedBefore()) {
            String firstJoinMsg = config.getFirstJoinMessage();
            if (firstJoinMsg != null && !firstJoinMsg.isEmpty()) {
                firstJoinMsg = parsePlaceholders(player, firstJoinMsg);
                firstJoinMsg = colorize(firstJoinMsg);
                Bukkit.broadcastMessage(firstJoinMsg);
            }
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

    private void sendWelcomeMessage(Player player) {
        List<String> welcomeLines = config.getWelcomeLines();
        if (welcomeLines == null) {
            return;
        }
        for (String line : welcomeLines) {
            line = parsePlaceholders(player, line);
            line = colorize(line);
            player.sendMessage(line);
        }
    }

    private void sendWelcomeTitle(Player player) {
        String title = config.getTitleMain();
        String subtitle = config.getTitleSub();

        title = parsePlaceholders(player, title);
        subtitle = parsePlaceholders(player, subtitle);
        title = colorize(title);
        subtitle = colorize(subtitle);

        player.sendTitle(title, subtitle, 10, 70, 20);
    }

    /**
     * Parse PlaceholderAPI placeholders with basic fallbacks.
     */
    String parsePlaceholders(Player player, String text) {
        if (text == null) {
            return "";
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }

        // Basic fallbacks when PlaceholderAPI is not installed
        String result = text.replace("%player_name%", player.getName());
        result = result.replace("{player}", player.getName());
        result = result.replace("{displayname}", player.getDisplayName());
        result = result.replace("%online_players%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        result = result.replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()));

        return result;
    }

    String colorize(String text) {
        if (text == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
