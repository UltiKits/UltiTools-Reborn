package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.BaseService;
import com.ultikits.ultitools.widgets.Toast;
import org.bukkit.Sound;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * Notification service interface
 */
public interface NotificationService extends BaseService {

    /**
     * Send a boss bar notification to a player
     * @param player The player to send the boss bar to
     * @param message The message to send
     * @return Whether the boss bar was sent successfully
     */
    boolean sendBossBarNotification(Player player, String message);

    /**
     * Send a boss bar notification to a player
     * @param player The player to send the boss bar to
     * @param message The message to send
     * @param seconds The duration of the boss bar in seconds
     * @return Whether the boss bar was sent successfully
     */
    boolean sendBossBarNotification(Player player, String message, int seconds);

    /**
     * Send a boss bar notification to a player
     * @param player The player to send the boss bar to
     * @param message The message to send
     * @param seconds The duration of the boss bar in seconds
     * @param sound The sound to play when the boss bar is sent
     * @return Whether the boss bar was sent successfully
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, Sound sound);

    /**
     * Send a boss bar notification to a player
     * @param player The player to send the boss bar to
     * @param message The message to send
     * @param seconds The duration of the boss bar in seconds
     * @param bossBar The boss bar to send
     * @return Whether the boss bar was sent successfully
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar);

    /**
     * Send a boss bar notification to a player
     * @param player The player to send the boss bar to
     * @param message The message to send
     * @param seconds The duration of the boss bar in seconds
     * @param bossBar The boss bar to send
     * @param sound The sound to play when the boss bar is sent
     * @return Whether the boss bar was sent successfully
     */
    boolean sendBossBarNotification(Player player, String message, int seconds, BossBar bossBar, Sound sound);

    /**
     * Send a message notification to a player
     * @param player The player to send the message to
     * @param message The message to send
     * @return Whether the message was sent successfully
     */
    boolean sendMessageNotification(Player player, String message);

    /**
     * Send a message notification to a player
     * @param player The player to send the message to
     * @param message The message to send
     * @param sound The sound to play when the message is sent
     * @return Whether the message was sent successfully
     */
    boolean sendMessageNotification(Player player, String message, Sound sound);

    /**
     * Send a subtitle message notification to a player
     * @param player The player to send the subtitle to
     * @param subtitle The subtitle to send
     * @return Whether the subtitle was sent successfully
     */
    boolean sendSubTitleNotification(Player player, String subtitle);

    /**
     * Send a subtitle message notification to a player
     * @param player The player to send the subtitle to
     * @param subtitle  The subtitle to send
     * @param sound The sound to play when the subtitle is sent
     * @return Whether the subtitle was sent successfully
     */
    boolean sendSubTitleNotification(Player player, String subtitle, Sound sound);

    /**
     * Send a title message notification to a player
     * @param player The player to send the title to
     * @param title The title to send
     * @param subtitle The subtitle to send
     * @return Whether the title was sent successfully
     */
    boolean sendTitleNotification(Player player, String title, String subtitle);

    /**
     * Send a title message notification to a player
     * @param player The player to send the title to
     * @param title The title to send
     * @param subtitle The subtitle to send
     * @param sound The sound to play when the title is sent
     * @return Whether the title was sent successfully
     */
    boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound);

    /**
     * Send a title message notification to a player
     * @param player The player to send the title to
     * @param title The title to send
     * @param subtitle The subtitle to send
     * @param sound The sound to play when the title is sent
     * @param fadeIn The time in ticks for the title to fade in
     * @param stay The time in ticks for the title to stay
     * @param fadeOut The time in ticks for the title to fade out
     * @return Whether the title was sent successfully
     */
    boolean sendTitleNotification(Player player, String title, String subtitle, Sound sound, int fadeIn, int stay, int fadeOut);

    boolean sendActionBarNotification(Player player, String message);

    boolean sendToastNotification(Player player, String icon, String message, Toast.Style style);
}
