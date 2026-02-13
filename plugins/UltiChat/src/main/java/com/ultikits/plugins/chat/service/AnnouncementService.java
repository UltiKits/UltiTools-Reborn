package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.AnnouncementConfig;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;

/**
 * Scheduled broadcast service for chat, boss bar, and title announcements.
 * 定时广播服务，支持聊天、Boss栏和标题公告。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class AnnouncementService {

    private final AnnouncementConfig config;

    private int chatIndex = 0;
    private int bossBarIndex = 0;
    private int titleIndex = 0;

    public AnnouncementService(AnnouncementConfig config) {
        this.config = config;
    }

    /**
     * Broadcast a chat announcement rotating through configured messages.
     * 轮播聊天公告消息。
     */
    @Scheduled(period = 6000, async = false)
    public void broadcastChat() {
        if (!config.isChatEnabled()) {
            return;
        }

        List<String> messages = config.getChatMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        String prefix = config.getChatPrefix();
        if (prefix == null) {
            prefix = "";
        }

        String message = messages.get(chatIndex % messages.size());
        String formatted = ChatColor.translateAlternateColorCodes('&', prefix + message);

        for (Player player : players) {
            player.sendMessage(formatted);
        }

        chatIndex = (chatIndex + 1) % messages.size();
    }

    /**
     * Show a boss bar announcement rotating through configured messages.
     * 轮播Boss栏公告消息。
     */
    @Scheduled(period = 1200, async = false)
    public void broadcastBossBar() {
        if (!config.isBossBarEnabled()) {
            return;
        }

        List<String> messages = config.getBossBarMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        String message = messages.get(bossBarIndex % messages.size());
        String formatted = ChatColor.translateAlternateColorCodes('&', message);

        BarColor barColor;
        try {
            barColor = BarColor.valueOf(config.getBossBarColor());
        } catch (IllegalArgumentException e) {
            barColor = BarColor.BLUE;
        }

        final BossBar bossBar = Bukkit.createBossBar(formatted, barColor, BarStyle.SOLID);
        for (Player player : players) {
            bossBar.addPlayer(player);
        }

        // Remove after configured duration
        int durationTicks = config.getBossBarDuration() * 20;
        Plugin bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        if (bukkitPlugin != null) {
            Bukkit.getScheduler().runTaskLater(bukkitPlugin, new Runnable() {
                @Override
                public void run() {
                    bossBar.removeAll();
                }
            }, durationTicks);
        } else {
            // Fallback: remove immediately if UltiTools not available
            bossBar.removeAll();
        }

        bossBarIndex = (bossBarIndex + 1) % messages.size();
    }

    /**
     * Show a title announcement rotating through configured messages.
     * Uses || separator for title and subtitle.
     * 轮播标题公告消息。使用 || 分隔主标题和副标题。
     */
    @Scheduled(period = 12000, async = false)
    public void broadcastTitle() {
        if (!config.isTitleEnabled()) {
            return;
        }

        List<String> messages = config.getTitleMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        String raw = messages.get(titleIndex % messages.size());
        String title;
        String subtitle;

        int separatorIdx = raw.indexOf("||");
        if (separatorIdx >= 0) {
            title = raw.substring(0, separatorIdx);
            subtitle = raw.substring(separatorIdx + 2);
        } else {
            title = raw;
            subtitle = "";
        }

        title = ChatColor.translateAlternateColorCodes('&', title);
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle);

        int fadeIn = config.getTitleFadeIn();
        int stay = config.getTitleStay();
        int fadeOut = config.getTitleFadeOut();

        for (Player player : players) {
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }

        titleIndex = (titleIndex + 1) % messages.size();
    }
}
