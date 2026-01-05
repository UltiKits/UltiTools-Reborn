package com.ultikits.plugins.essentials.listeners;

import com.ultikits.plugins.essentials.config.AutoReplyConfig;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for automatic chat replies based on keyword triggers.
 */
@EventListener
public class AutoReplyListener implements Listener {

    /**
     * Records the last time each player triggered an auto-reply for cooldown.
     */
    private static final Map<UUID, Long> LAST_REPLY_TIME = new ConcurrentHashMap<>();

    private final EssentialsConfig config;
    private final AutoReplyConfig autoReplyConfig;

    public AutoReplyListener(EssentialsConfig config, AutoReplyConfig autoReplyConfig) {
        this.config = config;
        this.autoReplyConfig = autoReplyConfig;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!config.isAutoReplyEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        // Check bypass permission
        if (player.hasPermission("ultiessentials.autoreply.bypass")) {
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastTime = LAST_REPLY_TIME.get(player.getUniqueId());
        int cooldown = autoReplyConfig.getCooldown() * 1000;

        if (lastTime != null && (now - lastTime) < cooldown) {
            return;
        }

        String message = event.getMessage();
        if (!autoReplyConfig.isCaseSensitive()) {
            message = message.toLowerCase();
        }

        // Check if any keyword matches
        for (Map.Entry<String, String> rule : autoReplyConfig.getRules().entrySet()) {
            String keyword = autoReplyConfig.isCaseSensitive()
                    ? rule.getKey()
                    : rule.getKey().toLowerCase();

            if (message.contains(keyword)) {
                String reply = ChatColor.translateAlternateColorCodes('&', rule.getValue());
                player.sendMessage(reply);
                LAST_REPLY_TIME.put(player.getUniqueId(), now);
                return; // Only reply to the first matched keyword
            }
        }
    }
}
