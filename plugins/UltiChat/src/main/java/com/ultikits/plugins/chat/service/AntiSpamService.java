package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.ChatConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-spam service that enforces cooldown, duplicate detection, caps limiting and temp muting.
 * 反垃圾消息服务，支持冷却、重复检测、大写字母限制和临时禁言。
 */
@Service
public class AntiSpamService {

    @Autowired
    private ChatConfig config;

    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedList<String>> recentMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();

    /**
     * Check whether a message should be considered spam.
     * 检查消息是否属于垃圾消息。
     *
     * @param player  the sending player
     * @param message the chat message
     * @return an i18n key describing the spam reason, or null if the message is not spam
     */
    public String checkSpam(Player player, String message) {
        if (!config.isAntiSpamEnabled()) {
            return null;
        }
        if (player == null || message == null) {
            return null;
        }
        UUID playerId = player.getUniqueId();

        String muteReason = checkMute(playerId);
        if (muteReason != null) {
            return muteReason;
        }

        String cooldownReason = checkCooldown(playerId);
        if (cooldownReason != null) {
            return cooldownReason;
        }

        if (isDuplicate(playerId, message)) {
            return "请不要发送重复消息！";
        }

        if (isExcessiveCaps(message)) {
            return "消息中大写字母过多！";
        }

        return null;
    }

    private String checkMute(UUID playerId) {
        Long muteExpiry = mutedUntil.get(playerId);
        if (muteExpiry == null) {
            return null;
        }
        if (System.currentTimeMillis() < muteExpiry) {
            return "你已被临时禁言！";
        }
        mutedUntil.remove(playerId);
        return null;
    }

    private String checkCooldown(UUID playerId) {
        Long lastTime = lastMessageTime.get(playerId);
        if (lastTime == null) {
            return null;
        }
        long elapsed = System.currentTimeMillis() - lastTime;
        long cooldownMs = config.getAntiSpamCooldown() * 1000L;
        if (elapsed < cooldownMs) {
            return "发送消息太快了！";
        }
        return null;
    }

    /**
     * Record a message for duplicate detection and update cooldown timestamp.
     * 记录消息用于重复检测，并更新冷却时间戳。
     *
     * @param playerId the player UUID
     * @param message  the chat message
     */
    public void recordMessage(UUID playerId, String message) {
        if (playerId == null || message == null) {
            return;
        }
        lastMessageTime.put(playerId, System.currentTimeMillis());

        LinkedList<String> messages = recentMessages.computeIfAbsent(playerId, k -> new LinkedList<String>());
        messages.addLast(message);

        int maxDuplicate = config.getAntiSpamMaxDuplicate();
        if (maxDuplicate <= 0) {
            maxDuplicate = 3;
        }
        while (messages.size() > maxDuplicate) {
            messages.removeFirst();
        }
    }

    /**
     * Temporarily mute a player for the configured duration.
     * 将玩家临时禁言（持续配置的时长）。
     *
     * @param playerId the player UUID
     */
    public void mutePlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        long durationMs = config.getAntiSpamMuteDuration() * 1000L;
        mutedUntil.put(playerId, System.currentTimeMillis() + durationMs);
    }

    /**
     * Check if a message has excessive uppercase characters.
     * 检查消息中大写字母是否过多。
     *
     * @param message the chat message
     * @return true if the uppercase ratio exceeds the configured limit
     */
    public boolean isExcessiveCaps(String message) {
        if (message == null || message.length() < 5) {
            return false;
        }
        int capsLimit = config.getAntiSpamCapsLimit();
        if (capsLimit <= 0 || capsLimit >= 100) {
            return false;
        }
        int total = 0;
        int upper = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isLetter(c)) {
                total++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (total == 0) {
            return false;
        }
        int percentage = (upper * 100) / total;
        return percentage > capsLimit;
    }

    /**
     * Remove all tracked state for a player (call on quit).
     * 清除玩家的所有追踪状态（退出时调用）。
     *
     * @param playerId the player UUID
     */
    public void cleanup(UUID playerId) {
        if (playerId == null) {
            return;
        }
        lastMessageTime.remove(playerId);
        recentMessages.remove(playerId);
        mutedUntil.remove(playerId);
    }

    /**
     * Check if a message is a duplicate of recent messages within the configured window.
     */
    private boolean isDuplicate(UUID playerId, String message) {
        LinkedList<String> messages = recentMessages.get(playerId);
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        int maxDuplicate = config.getAntiSpamMaxDuplicate();
        if (maxDuplicate <= 0) {
            return false;
        }

        // Count how many of the recent messages match
        int duplicateCount = 0;
        for (String recent : messages) {
            if (message.equals(recent)) {
                duplicateCount++;
            }
        }
        return duplicateCount >= maxDuplicate;
    }
}
