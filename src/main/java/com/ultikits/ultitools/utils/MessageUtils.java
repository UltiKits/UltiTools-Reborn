package com.ultikits.ultitools.utils;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.TextComponent;

/**
 * Message utils.
 */
public class MessageUtils {

    /**
     * Get a colored string.
     *
     * @param chatColor the chat color
     * @param message   the message
     * @return the string
     */
    public static String msg(ChatColor chatColor, String message) {
        return chatColor + message;
    }

    /**
     * Send a colored message to player, using {@literal &} as color code.
     *
     * @param player the player
     * @param msg    the msg
     */
    public static void sendMessage(Player player, String msg) {
        player.sendMessage(coloredMsg(msg));
    }

    /**
     * Send a colored message to player, using custom color code.
     *
     * @param player              the player
     * @param msg                 the msg
     * @param alternateColorCodes the alternate color codes
     */
    public static void sendMessage(Player player, String msg, char alternateColorCodes) {
        player.sendMessage(ChatColor.translateAlternateColorCodes(alternateColorCodes, msg));
    }

    /**
     * Send an Adventure component message to player.
     *
     * @param player        the player
     * @param textComponent the text component
     */
    public static void sendMessage(Player player, TextComponent textComponent) {
        BukkitAudiences audiences = BukkitAudiences.create(UltiTools.getInstance());
        audiences.player(player).sendMessage(textComponent);
        audiences.close();
    }

    /**
     * Get a colored string, using {@literal &} as color code.
     *
     * @param message the message
     * @return the string
     */
    public static String coloredMsg(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Get info message (light blue).
     *
     * @param message the message
     * @return the string
     */
    public static String info(String message) {
        return ChatColor.AQUA + message;
    }

    /**
     * Get warning message (light red).
     *
     * @param message the message
     * @return the string
     */
    public static String warning(String message) {
        return ChatColor.RED + message;
    }

    /**
     * Get error message (dark red).
     *
     * @param message the message
     * @return the string
     */
    public static String error(String message) {
        return ChatColor.DARK_RED + message;
    }
}
