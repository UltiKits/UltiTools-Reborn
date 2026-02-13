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

        if (player.hasPermission("ultichat.autoreply.bypass")) {
            return;
        }

        if (isOnCooldown(player.getUniqueId())) {
            return;
        }

        Map.Entry<String, Map<String, Object>> match = autoReplyService.findMatch(event.getMessage());
        if (match == null) {
            return;
        }

        Map<String, Object> rule = match.getValue();

        if (!hasRulePermission(player, rule)) {
            return;
        }

        sendResponse(player, rule);
        executeCommands(player, autoReplyService.getCommands(rule));

        LAST_REPLY_TIME.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean isOnCooldown(UUID playerId) {
        Long lastTime = LAST_REPLY_TIME.get(playerId);
        if (lastTime == null) {
            return false;
        }
        long cooldownMs = config.getCooldown() * 1000L;
        return (System.currentTimeMillis() - lastTime) < cooldownMs;
    }

    private boolean hasRulePermission(Player player, Map<String, Object> rule) {
        Object permission = rule.get("permission");
        if (permission == null || permission.toString().isEmpty()) {
            return true;
        }
        return player.hasPermission(permission.toString());
    }

    private void sendResponse(Player player, Map<String, Object> rule) {
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
    }

    private void executeCommands(Player player, List<String> commands) {
        if (commands.isEmpty()) {
            return;
        }
        Plugin bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        if (bukkitPlugin == null) {
            return;
        }
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

    private String formatMessage(String message, Player player) {
        String formatted = message.replace("{player}", player.getName());
        return ChatColor.translateAlternateColorCodes('&', formatted);
    }
}
