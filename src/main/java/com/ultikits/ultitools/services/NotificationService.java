package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.BaseService;
import com.ultikits.ultitools.widgets.Toast;
import org.bukkit.Sound;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * Notification service interface <p> 通知服务接口
 */
public interface NotificationService extends BaseService {

    /**
     * Send a boss bar notification to a player <p> 向玩家发送Boss bar通知
     * @param player The player to send the boss bar to <p> 要发送Boss bar的玩家
     * @param message The message to send <p> 要发送的消息
     * @return Whether the boss bar was sent successfully <p> 是否成功发送了Boss bar
     */
    boolean sendBossBarNotification(Player player, String message);

    /**
     * Send a boss bar notification to a player <p> 向玩家发送Boss bar通知
     * @param player The player to send the boss bar to <p> 要发送Boss bar的玩家
     * @param message The message to send <p> 要发送的消息
     * @param seconds The duration of the boss bar in seconds <p> Boss bar的持续时间（秒）
     * @return Whether the boss bar was sent successfully <p> 是否成功发送了Boss bar
     */
    boolean sendBossBarNotification(Player player, String message, int seconds);

    /**
     * Send a boss bar notification to a player <p> 向玩家发送Boss bar通知
     * @param player The player to send the boss bar to <p> 要发送Boss bar的玩家
     * @param message The message to send <p> 要发送的消息
     * @param seconds The duration of the boss bar in seconds <p> Boss bar的持续时间（秒）
     * @param sound The sound to play when the boss bar is sent <p> 发送Boss bar时播放的声音
     * @return Whether the boss bar was sent successfully <p> 是否成功发送了Boss bar
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, Sound sound);

    /**
     * Send a boss bar notification to a player <p> 向玩家发送Boss bar通知
     * @param player The player to send the boss bar to <p> 要发送Boss bar的玩家
     * @param message The message to send <p> 要发送的消息
     * @param seconds The duration of the boss bar in seconds <p> Boss bar的持续时间（秒）
     * @param bossBar The boss bar to send <p> 要发送的Boss bar
     * @return Whether the boss bar was sent successfully <p> 是否成功发送了Boss bar
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar);

    /**
     * Send a boss bar notification to a player <p> 向玩家发送Boss bar通知
     * @param player The player to send the boss bar to <p> 要发送Boss bar的玩家
     * @param message The message to send <p> 要发送的消息
     * @param seconds The duration of the boss bar in seconds <p> Boss bar的持续时间（秒）
     * @param bossBar The boss bar to send <p> 要发送的Boss bar
     * @param sound The sound to play when the boss bar is sent <p> 发送Boss bar时播放的声音
     * @return Whether the boss bar was sent successfully <p> 是否成功发送了Boss bar
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar, Sound sound);

    /**
     * Send a message notification to a player <p> 向玩家发送消息通知
     * @param player The player to send the message to <p> 要发送消息的玩家
     * @param message The message to send <p> 要发送的消息
     * @return Whether the message was sent successfully <p> 是否成功发送了消息
     */
    boolean sendMessageNotification(Player player, String message);

    /**
     * Send a message notification to a player <p> 向玩家发送消息通知
     * @param player The player to send the message to <p> 要发送消息的玩家
     * @param message The message to send <p> 要发送的消息
     * @param sound The sound to play when the message is sent <p> 发送消息时播放的声音
     * @return Whether the message was sent successfully <p> 是否成功发送了消息
     */
    boolean sendMessageNotification(Player player, String message, Sound sound);

    /**
     * Send a subtitle message notification to a player <p> 向玩家发送副标题消息通知
     * @param player The player to send the subtitle to <p> 要发送副标题的玩家
     * @param subtitle The subtitle to send <p> 要发送的副标题
     * @return Whether the subtitle was sent successfully <p> 是否成功发送了副标题
     */
    boolean sendSubTitleNotification(Player player, String subtitle);

    /**
     * Send a subtitle message notification to a player <p> 向玩家发送副标题消息通知
     * @param player The player to send the subtitle to <p> 要发送副标题的玩家
     * @param subtitle  The subtitle to send <p> 要发送的副标题
     * @param sound The sound to play when the subtitle is sent <p> 发送副标题时播放的声音
     * @return Whether the subtitle was sent successfully <p> 是否成功发送了副标题
     */
    boolean sendSubTitleNotification(Player player, String subtitle, Sound sound);

    /**
     * Send a title message notification to a player <p> 向玩家发送标题消息通知
     * @param player The player to send the title to <p> 要发送标题的玩家
     * @param title The title to send <p> 要发送的标题
     * @param subtitle The subtitle to send <p> 要发送的副标题
     * @return Whether the title was sent successfully <p> 是否成功发送了标题
     */
    boolean sendTitleNotification(Player player, String title, String subtitle);

    /**
     * Send a title message notification to a player <p> 向玩家发送标题消息通知
     * @param player The player to send the title to <p> 要发送标题的玩家
     * @param title The title to send <p> 要发送的标题
     * @param subtitle The subtitle to send <p> 要发送的副标题
     * @param sound The sound to play when the title is sent <p> 发送标题时播放的声音
     * @return Whether the title was sent successfully <p> 是否成功发送了标题
     */
    boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound);

    /**
     * Send a title message notification to a player <p> 向玩家发送标题消息通知
     * @param player The player to send the title to <p> 要发送标题的玩家
     * @param title The title to send <p> 要发送的标题
     * @param subtitle The subtitle to send <p> 要发送的副标题
     * @param sound The sound to play when the title is sent <p> 发送标题时播放的声音
     * @param fadeIn The time in ticks for the title to fade in <p> 标题淡入的时间（ticks）
     * @param stay The time in ticks for the title to stay <p> 标题停留的时间（ticks）
     * @param fadeOut The time in ticks for the title to fade out <p> 标题淡出的时间（ticks）
     * @return Whether the title was sent successfully <p> 是否成功发送了标题
     */
    boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound, int fadeIn, int stay, int fadeOut);

    boolean sendActionBarNotification(Player player, String message);

    boolean sendToastNotification(Player player, String icon, String message, Toast.Style style);
}
