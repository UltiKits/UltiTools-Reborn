package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * 消息工具类
 */
public class MessageUtils {

    /**
     * 获取一个带有颜色的字符串
     *
     * @param chatColor the chat color
     * @param message   the message
     * @return the string
     */
    public static String msg(ChatColor chatColor, String message) {
        return chatColor + message;
    }

    /**
     * 给玩家发送一个带有颜色的消息，使用{@literal &}作为颜色代码
     *
     * @param player the player
     * @param msg    the msg
     */
    public static void sendMessage(Player player, String msg) {
        player.sendMessage(coloredMsg(msg));
    }

    /**
     * 给玩家发送一个带有颜色的消息，使用自定义颜色代码
     *
     * @param player              the player
     * @param msg                 the msg
     * @param alternateColorCodes the alternate color codes
     */
    public static void sendMessage(Player player, String msg, char alternateColorCodes) {
        player.sendMessage(ChatColor.translateAlternateColorCodes(alternateColorCodes, msg));
    }

    /**
     * 给玩家发送一个Adventure组件消息
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
     * 获取一个带有颜色的字符串，使用{@literal &}作为颜色代码
     *
     * @param message the message
     * @return the string
     */
    public static String coloredMsg(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 获取通知消息 （亮蓝色）
     *
     * @param message the message
     * @return the string
     */
    public static String info(String message) {
        return ChatColor.AQUA + message;
    }

    /**
     * 获取警告消息 （亮红色）
     *
     * @param message the message
     * @return the string
     */
    public static String warning(String message) {
        return ChatColor.RED + message;
    }

    /**
     * 获取错误消息 （深红色）
     *
     * @param message the message
     * @return the string
     */
    public static String error(String message) {
        return ChatColor.DARK_RED + message;
    }
}
