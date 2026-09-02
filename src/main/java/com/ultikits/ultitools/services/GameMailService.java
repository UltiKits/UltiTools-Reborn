package com.ultikits.ultitools.services;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.ultikits.ultitools.interfaces.BaseService;

/**
 * Game mail service interface for in-game mail system.
 * This service provides cross-module mail functionality.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface GameMailService extends BaseService {

    /**
     * Send a simple text mail.
     *
     * @param senderUuid sender's UUID (can be null for system mail)
     * @param senderName sender's name
     * @param receiverName receiver's name
     * @param subject mail subject
     * @param content mail content
     * @return true if sent successfully
     */
    boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content);

    /**
     * Send a mail with attachments.
     *
     * @param senderUuid sender's UUID (can be null for system mail)
     * @param senderName sender's name
     * @param receiverName receiver's name
     * @param subject mail subject
     * @param content mail content
     * @param items attached items
     * @return true if sent successfully
     */
    boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content, ItemStack[] items);

    /**
     * Send a system notification mail.
     *
     * @param receiverName receiver's name
     * @param subject mail subject
     * @param content mail content
     * @return true if sent successfully
     */
    boolean sendSystemMail(String receiverName, String subject, String content);

    /**
     * Get unread mail count for a player.
     *
     * @param playerUuid player's UUID
     * @return unread mail count
     */
    int getUnreadCount(UUID playerUuid);

    /**
     * Check if the mail service is available and functional.
     *
     * @return true if available
     */
    boolean isAvailable();

    /**
     * Notify a player about new mail.
     *
     * @param player the player to notify
     */
    void notifyNewMail(Player player);
}
