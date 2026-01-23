package com.ultikits.plugins.essentials.utils;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Utility class for message formatting and sending.
 * <p>
 * Provides common message handling functions used across UltiEssentials.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public final class MessageUtils {

    private MessageUtils() {
        // Utility class, no instantiation
    }

    /**
     * Translates color codes in a string using '&' as the color code prefix.
     *
     * @param text the text to colorize
     * @return the colorized text
     */
    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Strips all color codes from a string.
     *
     * @param text the text to strip colors from
     * @return the text without color codes
     */
    public static String stripColor(String text) {
        if (text == null) {
            return "";
        }
        return ChatColor.stripColor(text);
    }

    /**
     * Parses basic placeholders in a string.
     *
     * @param player the player to parse placeholders for
     * @param text   the text containing placeholders
     * @return the text with placeholders replaced
     */
    public static String parsePlaceholders(@Nullable Player player, String text) {
        if (text == null) {
            return "";
        }
        
        // Server-wide placeholders
        text = text.replace("%online_players%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        text = text.replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()));
        text = text.replace("%server_name%", Bukkit.getServer().getName());
        
        // Player-specific placeholders
        if (player != null) {
            text = text.replace("%player%", player.getName());
            text = text.replace("%player_name%", player.getName());
            text = text.replace("{player}", player.getName());
            text = text.replace("{displayname}", player.getDisplayName());
            text = text.replace("%displayname%", player.getDisplayName());
            text = text.replace("%health%", String.valueOf((int) player.getHealth()));
            text = text.replace("%food%", String.valueOf(player.getFoodLevel()));
            text = text.replace("%level%", String.valueOf(player.getLevel()));
            text = text.replace("%world%", player.getWorld().getName());
        }
        
        return text;
    }

    /**
     * Sends a formatted message to a player.
     *
     * @param player  the player to send the message to
     * @param message the message to send (supports color codes and placeholders)
     */
    public static void sendMessage(Player player, String message) {
        if (player == null || message == null || message.isEmpty()) {
            return;
        }
        message = parsePlaceholders(player, message);
        message = colorize(message);
        player.sendMessage(message);
    }

    /**
     * Broadcasts a formatted message to all online players.
     *
     * @param message the message to broadcast (supports color codes)
     */
    public static void broadcast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        message = colorize(message);
        Bukkit.broadcastMessage(message);
    }

    /**
     * Sends a title to a player.
     *
     * @param player   the player to send the title to
     * @param title    the main title text
     * @param subtitle the subtitle text
     * @param fadeIn   the fade in time in ticks
     * @param stay     the stay time in ticks
     * @param fadeOut  the fade out time in ticks
     */
    public static void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (player == null) {
            return;
        }
        String processedTitle = colorize(parsePlaceholders(player, title != null ? title : ""));
        String processedSubtitle = colorize(parsePlaceholders(player, subtitle != null ? subtitle : ""));
        player.sendTitle(processedTitle, processedSubtitle, fadeIn, stay, fadeOut);
    }

    /**
     * Sends an action bar message to a player.
     * Uses Spigot API for cross-version compatibility.
     *
     * @param player  the player to send the action bar to
     * @param message the message to display
     */
    public static void sendActionBar(Player player, String message) {
        if (player == null || message == null) {
            return;
        }
        message = colorize(parsePlaceholders(player, message));
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
    }

    /**
     * Formats a location as a readable string.
     *
     * @param worldName the world name
     * @param x         the x coordinate
     * @param y         the y coordinate
     * @param z         the z coordinate
     * @return the formatted location string
     */
    public static String formatLocation(String worldName, double x, double y, double z) {
        return String.format("%s (%.1f, %.1f, %.1f)", worldName, x, y, z);
    }

    /**
     * Formats a duration in milliseconds to a human-readable string.
     *
     * @param durationMs the duration in milliseconds
     * @return the formatted duration string
     */
    public static String formatDuration(long durationMs) {
        if (durationMs < 0) {
            return "永久";
        }
        
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long weeks = days / 7;
        
        StringBuilder sb = new StringBuilder();
        
        if (weeks > 0) {
            sb.append(weeks).append("周");
            days %= 7;
        }
        if (days > 0) {
            sb.append(days).append("天");
            hours %= 24;
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
            minutes %= 60;
        }
        if (minutes > 0) {
            sb.append(minutes).append("分钟");
            seconds %= 60;
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append("秒");
        }
        
        return sb.toString();
    }
}
