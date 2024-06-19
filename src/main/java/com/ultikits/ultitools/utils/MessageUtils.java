package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Message utils.
 * <p>
 * 消息工具类
 */
public class MessageUtils {

    /**
     * Get a colored string.
     * <br>
     * 获取一个带有颜色的字符串
     *
     * @param chatColor the chat color <br> 颜色
     * @param message   the message <br> 消息
     * @return the string <br> 字符串
     */
    public static String msg(ChatColor chatColor, String message) {
        return chatColor + message;
    }

    /**
     * Send a colored message to player, using {@literal &} as color code.
     * <br>
     * 给玩家发送一个带有颜色的消息，使用{@literal &}作为颜色代码
     *
     * @param player the player <br> 玩家
     * @param msg    the msg <br> 消息
     */
    public static void sendMessage(Player player, String msg) {
        player.sendMessage(coloredMsg(msg));
    }

    /**
     * Send a colored message to player, using custom color code.
     * <br>
     * 给玩家发送一个带有颜色的消息，使用自定义颜色代码
     *
     * @param player              the player <br> 玩家
     * @param msg                 the msg <br> 消息
     * @param alternateColorCodes the alternate color codes <br> 自定义颜色代码
     */
    public static void sendMessage(Player player, String msg, char alternateColorCodes) {
        player.sendMessage(ChatColor.translateAlternateColorCodes(alternateColorCodes, msg));
    }

    /**
     * Send an Adventure component message to player.
     * <br>
     * 给玩家发送一个Adventure组件消息
     *
     * @param player        the player <br> 玩家
     * @param textComponent the text component <br> 文本组件
     */
    public static void sendMessage(Player player, TextComponent textComponent) {
        BukkitAudiences audiences = BukkitAudiences.create(UltiTools.getInstance());
        audiences.player(player).sendMessage(textComponent);
        audiences.close();
    }

    /**
     * Get a colored string, using {@literal &} as color code.
     * <br>
     * 获取一个带有颜色的字符串，使用{@literal &}作为颜色代码
     *
     * @param message the message <br> 消息
     * @return the string <br> 字符串
     */
    public static String coloredMsg(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Get info message (light blue).
     * <br>
     * 获取通知消息 （亮蓝色）
     *
     * @param message the message <br> 消息
     * @return the string <br> 字符串
     */
    public static String info(String message) {
        return ChatColor.AQUA + message;
    }

    /**
     * Get warning message (light red).
     * <br>
     * 获取警告消息 （亮红色）
     *
     * @param message the message <br> 消息
     * @return the string <br> 字符串
     */
    public static String warning(String message) {
        return ChatColor.RED + message;
    }

    /**
     * Get error message (dark red).
     * <br>
     * 获取错误消息 （深红色）
     *
     * @param message the message <br> 消息
     * @return the string <br> 字符串
     */
    public static String error(String message) {
        return ChatColor.DARK_RED + message;
    }
}
