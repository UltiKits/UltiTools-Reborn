package com.ultikits.ultitools.services.impl;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.services.GameMailService;

/**
 * No-op implementation of GameMailService.
 * Used as a fallback when no mail module is loaded.
 * All operations return false/0 indicating unavailable.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service(priority = 0)
public class NoOpGameMailService implements GameMailService {

    @Override
    public String getName() {
        return "NoOpGameMailService";
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
        // No mail service available, return false
        return false;
    }

    @Override
    public boolean sendMail(UUID senderUuid, String senderName, String receiverName, String subject, String content, ItemStack[] items) {
        // No mail service available, return false
        return false;
    }

    @Override
    public boolean sendSystemMail(String receiverName, String subject, String content) {
        // No mail service available, return false
        return false;
    }

    @Override
    public int getUnreadCount(UUID playerUuid) {
        // No mail service available, return 0
        return 0;
    }

    @Override
    public boolean isAvailable() {
        // This is a no-op implementation, mail service is not available
        return false;
    }

    @Override
    public void notifyNewMail(Player player) {
        // No mail service available, do nothing
    }
}
