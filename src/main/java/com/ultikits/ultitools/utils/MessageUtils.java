package com.ultikits.ultitools.utils;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

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
     * <p>The component goes through the player's own Adventure {@code Audience}, which Paper
     * implements natively, so click and hover events reach the client unchanged and one component
     * arrives as one chat message.
     *
     * <p>It must not go through {@code adventure-platform-bukkit}'s {@code BukkitAudiences}. Measured
     * on Paper 1.21.11: that library (4.3.2) cannot find the server's internal component serializer
     * there, so its chat facet falls back to legacy section-sign text. Every click and hover event is
     * dropped and every newline becomes a separate chat line, which left the {@code /upm list}
     * Install/Uninstall buttons and the chat confirm buttons doing nothing when clicked.
     *
     * @param player        the player
     * @param textComponent the text component
     */
    public static void sendMessage(Player player, TextComponent textComponent) {
        player.sendMessage(textComponent);
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
