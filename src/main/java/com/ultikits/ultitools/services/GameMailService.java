package com.ultikits.ultitools.services;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.ultikits.ultitools.interfaces.BaseService;

/**
 * Game mail service interface for in-game mail system.
 * This service provides cross-module mail functionality.
 * <p>
 * 游戏内邮件服务接口。
 * 此服务提供跨模块的邮件功能。
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface GameMailService extends BaseService {

    /**
     * Send a simple text mail.
     * <p>
     * 发送简单文本邮件。
     *
     * @param senderUuid sender's UUID (can be null for system mail) <br> 发送者UUID（系统邮件可为null）
     * @param senderName sender's name <br> 发送者名称
     * @param receiverName receiver's name <br> 接收者名称
     * @param subject mail subject <br> 邮件主题
     * @param content mail content <br> 邮件内容
     * @return true if sent successfully <br> 是否发送成功
     */
    boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content);

    /**
     * Send a mail with attachments.
     * <p>
     * 发送带附件的邮件。
     *
     * @param senderUuid sender's UUID (can be null for system mail) <br> 发送者UUID（系统邮件可为null）
     * @param senderName sender's name <br> 发送者名称
     * @param receiverName receiver's name <br> 接收者名称
     * @param subject mail subject <br> 邮件主题
     * @param content mail content <br> 邮件内容
     * @param items attached items <br> 附件物品
     * @return true if sent successfully <br> 是否发送成功
     */
    boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content, ItemStack[] items);

    /**
     * Send a system notification mail.
     * <p>
     * 发送系统通知邮件。
     *
     * @param receiverName receiver's name <br> 接收者名称
     * @param subject mail subject <br> 邮件主题
     * @param content mail content <br> 邮件内容
     * @return true if sent successfully <br> 是否发送成功
     */
    boolean sendSystemMail(String receiverName, String subject, String content);

    /**
     * Get unread mail count for a player.
     * <p>
     * 获取玩家未读邮件数量。
     *
     * @param playerUuid player's UUID <br> 玩家UUID
     * @return unread mail count <br> 未读邮件数量
     */
    int getUnreadCount(UUID playerUuid);

    /**
     * Check if the mail service is available and functional.
     * <p>
     * 检查邮件服务是否可用。
     *
     * @return true if available <br> 是否可用
     */
    boolean isAvailable();

    /**
     * Notify a player about new mail.
     * <p>
     * 通知玩家有新邮件。
     *
     * @param player the player to notify <br> 要通知的玩家
     */
    void notifyNewMail(Player player);
}
