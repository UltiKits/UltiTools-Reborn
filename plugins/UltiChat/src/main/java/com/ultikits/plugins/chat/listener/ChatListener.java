package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChatConfig;
import com.ultikits.plugins.chat.config.ChannelConfig;
import com.ultikits.plugins.chat.service.AntiSpamService;
import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.plugins.chat.service.EmojiService;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;

import java.util.HashSet;
import java.util.Set;

/**
 * Main chat event listener that integrates anti-spam, emoji, channel, format, and mention features.
 * 主聊天事件监听器，集成反垃圾消息、表情、频道、格式化和@提及功能。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class ChatListener implements Listener {

    private final ChatConfig chatConfig;
    private final ChannelConfig channelConfig;
    private final AntiSpamService antiSpamService;
    private final ChannelService channelService;
    private final EmojiService emojiService;

    public ChatListener(ChatConfig chatConfig, ChannelConfig channelConfig,
                        AntiSpamService antiSpamService, ChannelService channelService,
                        EmojiService emojiService) {
        this.chatConfig = chatConfig;
        this.channelConfig = channelConfig;
        this.antiSpamService = antiSpamService;
        this.channelService = channelService;
        this.emojiService = emojiService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // 1. Anti-spam check
        if (handleAntiSpam(player, message, event)) {
            return;
        }

        // 2. Emoji replacement
        if (player.hasPermission("ultichat.emoji")) {
            message = emojiService.replaceEmojis(message);
        }

        // 3. Channel recipient filtering
        if (channelConfig.isEnabled()) {
            Set<Player> filtered = channelService.filterRecipients(player, event.getRecipients());
            event.getRecipients().clear();
            event.getRecipients().addAll(filtered);
        }

        // 4. Chat format
        if (chatConfig.isChatFormatEnabled()) {
            applyChatFormat(player, event);
        }

        // Color codes in message if player has permission
        if (player.hasPermission("ultichat.color")) {
            message = ChatColor.translateAlternateColorCodes('&', message);
        }

        // 5. @Mentions
        if (chatConfig.isMentionsEnabled()) {
            message = processMentions(player, message, event.getRecipients());
        }

        event.setMessage(message);
    }

    /**
     * Check anti-spam and cancel the event if the message is spam.
     * @return true if the event was cancelled (caller should return)
     */
    private boolean handleAntiSpam(Player player, String message, AsyncPlayerChatEvent event) {
        if (!chatConfig.isAntiSpamEnabled() || player.hasPermission("ultichat.spam.bypass")) {
            return false;
        }
        String spamReason = antiSpamService.checkSpam(player, message);
        if (spamReason != null) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + spamReason);
            return true;
        }
        antiSpamService.recordMessage(player.getUniqueId(), message);
        return false;
    }

    /**
     * Build and apply the chat format string to the event.
     */
    private void applyChatFormat(Player player, AsyncPlayerChatEvent event) {
        String format = chatConfig.getChatFormat();

        // Prepend channel display name if channels enabled
        if (channelConfig.isEnabled()) {
            String channel = channelService.getPlayerChannel(player.getUniqueId());
            String channelDisplay = channelService.getChannelDisplayName(channel);
            format = channelDisplay + " " + format;
        }

        // Replace placeholders
        format = format.replace("{player}", "%1$s");
        format = format.replace("{displayname}", player.getDisplayName());
        format = format.replace("{message}", "%2$s");

        // Translate color codes in format
        format = ChatColor.translateAlternateColorCodes('&', format);

        // PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            format = PlaceholderAPI.setPlaceholders(player, format);
        }

        // Escape stray % chars that aren't format specifiers
        format = escapeFormatString(format);

        event.setFormat(format);
    }

    /**
     * Process @mentions in the message. Highlights mentioned names and plays sounds.
     */
    String processMentions(Player sender, String message, Set<Player> recipients) {
        String result = message;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String mention = "@" + online.getName();
            if (!result.contains(mention)) {
                continue;
            }

            // Self-mention check
            if (online.getUniqueId().equals(sender.getUniqueId()) && !chatConfig.isSelfMention()) {
                continue;
            }

            // Replace with formatted mention
            String mentionFormat = chatConfig.getMentionFormat();
            String formatted = mentionFormat.replace("{player}", online.getName());
            formatted = ChatColor.translateAlternateColorCodes('&', formatted);
            result = result.replace(mention, formatted);

            // Play sound to mentioned player
            playMentionSound(online);
        }
        return result;
    }

    /**
     * Play the configured mention sound to a player.
     */
    private void playMentionSound(Player player) {
        String soundName = chatConfig.getMentionSound();
        if (soundName == null || soundName.isEmpty()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
            // Invalid sound name — silently ignore
        }
    }

    /**
     * Escape % characters in a format string, preserving %1$s and %2$s specifiers.
     * 转义格式字符串中的 % 字符，保留 %1$s 和 %2$s 格式说明符。
     */
    static String escapeFormatString(String format) {
        if (format == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%') {
                // Check if this is %1$s or %2$s
                if (i + 3 < format.length()
                        && (format.charAt(i + 1) == '1' || format.charAt(i + 1) == '2')
                        && format.charAt(i + 2) == '$'
                        && format.charAt(i + 3) == 's') {
                    sb.append(c); // keep as-is
                } else {
                    sb.append("%%");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
