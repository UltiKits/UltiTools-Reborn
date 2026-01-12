package com.ultikits.plugins.mail.service;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.services.GameMailService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * UltiMail implementation of GameMailService.
 * Delegates to the existing MailService for actual mail operations.
 * <p>
 * GameMailService 的 UltiMail 实现。
 * 委托给现有的 MailService 执行实际的邮件操作。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service(priority = 100)
public class UltiMailGameMailService implements GameMailService {

    @Autowired
    private MailService mailService;

    @Override
    public String getName() {
        return "UltiMailGameMailService";
    }

    @Override
    public String getAuthor() {
        return "UltiTools";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content) {
        return sendMail(senderUuid, senderName, receiverName, subject, content, null);
    }

    @Override
    public boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content, ItemStack[] items) {
        // Use MailService's internal method directly
        return mailService.sendMailInternal(senderUuid, senderName, receiverName, subject, content, items);
    }

    @Override
    public boolean sendSystemMail(String receiverName, String subject, String content) {
        // System mail uses null UUID and "System" as sender name
        return mailService.sendMailInternal(null, "System", receiverName, subject, content, null);
    }

    @Override
    public int getUnreadCount(UUID playerUuid) {
        return mailService.getUnreadCount(playerUuid);
    }

    @Override
    public boolean isAvailable() {
        return mailService != null;
    }

    @Override
    public void notifyNewMail(Player player) {
        int unread = getUnreadCount(player.getUniqueId());
        if (unread > 0) {
            player.sendMessage(ChatColor.YELLOW + "[UltiMail] " + ChatColor.GREEN + 
                "你有 " + unread + " 封未读邮件! 使用 /mail 查看");
        }
    }
}
