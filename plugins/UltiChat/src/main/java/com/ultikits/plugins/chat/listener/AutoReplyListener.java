package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.AutoReplyConfig;
import com.ultikits.plugins.chat.service.AutoReplyService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for automatic chat replies based on keyword/regex triggers.
 * <p>
 * Supports contains, exact, and regex match modes with per-rule
 * case sensitivity, permissions, cooldowns, multi-line responses,
 * and console command execution.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class AutoReplyListener implements Listener {

    /**
     * Records the last time each player triggered an auto-reply for cooldown.
     */
    static final Map<UUID, Long> LAST_REPLY_TIME = new ConcurrentHashMap<>();

    @Autowired
    private AutoReplyConfig config;

    @Autowired
    private AutoReplyService autoReplyService;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!config.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        // Check bypass permission
        if (player.hasPermission("ultichat.autoreply.bypass")) {
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        Long lastTime = LAST_REPLY_TIME.get(player.getUniqueId());
        long cooldownMs = config.getCooldown() * 1000L;

        if (lastTime != null && (now - lastTime) < cooldownMs) {
            return;
        }

        String message = event.getMessage();

        // Find matching rule
        Map.Entry<String, Map<String, Object>> match = autoReplyService.findMatch(message);
        if (match == null) {
            return;
        }

        Map<String, Object> rule = match.getValue();

        // Check rule-specific permission
        Object permission = rule.get("permission");
        if (permission != null && !permission.toString().isEmpty()) {
            if (!player.hasPermission(permission.toString())) {
                return;
            }
        }

        // Send response
        Object response = autoReplyService.getResponse(rule);
        if (response instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) response;
            for (String line : lines) {
                player.sendMessage(formatMessage(line, player));
            }
        } else if (response != null) {
            player.sendMessage(formatMessage(response.toString(), player));
        }

        // Execute commands on main thread
        List<String> commands = autoReplyService.getCommands(rule);
        if (!commands.isEmpty()) {
            Plugin bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
            if (bukkitPlugin != null) {
                Bukkit.getScheduler().runTask(bukkitPlugin, new Runnable() {
                    @Override
                    public void run() {
                        for (String cmd : commands) {
                            String formatted = cmd.replace("{player}", player.getName());
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
                        }
                    }
                });
            }
        }

        // Record cooldown
        LAST_REPLY_TIME.put(player.getUniqueId(), now);
    }

    private String formatMessage(String message, Player player) {
        String formatted = message.replace("{player}", player.getName());
        return ChatColor.translateAlternateColorCodes('&', formatted);
    }
}
