package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.UltiEssentials;
import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Service for managing server announcements.
 * <p>
 * 管理服务器公告的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class AnnouncementService {
    
    @Autowired
    private EssentialsConfig config;
    
    // Tasks
    private BukkitTask chatTask;
    private BukkitTask bossBarTask;
    private BukkitTask titleTask;
    
    // Current indices for rotation
    private int chatIndex = 0;
    private int bossBarIndex = 0;
    private int titleIndex = 0;
    
    // Active boss bars
    private final Map<UUID, BossBar> activeBossBars = new HashMap<>();
    
    /**
     * Initializes the announcement service.
     */
    public void init() {
        startTasks();
    }
    
    /**
     * Starts all announcement tasks.
     */
    public void startTasks() {
        // Chat announcements
        if (config.isAnnouncementChatEnabled() && !config.getAnnouncementChatMessages().isEmpty()) {
            chatTask = new BukkitRunnable() {
                @Override
                public void run() {
                    broadcastChatAnnouncement();
                }
            }.runTaskTimer(UltiTools.getInstance(), 
                config.getAnnouncementChatInterval() * 20L, 
                config.getAnnouncementChatInterval() * 20L);
        }
        
        // Boss bar announcements
        if (config.isAnnouncementBossBarEnabled() && !config.getAnnouncementBossBarMessages().isEmpty()) {
            bossBarTask = new BukkitRunnable() {
                @Override
                public void run() {
                    showBossBarAnnouncement();
                }
            }.runTaskTimer(UltiTools.getInstance(), 
                config.getAnnouncementBossBarInterval() * 20L, 
                config.getAnnouncementBossBarInterval() * 20L);
        }
        
        // Title announcements
        if (config.isAnnouncementTitleEnabled() && !config.getAnnouncementTitleMessages().isEmpty()) {
            titleTask = new BukkitRunnable() {
                @Override
                public void run() {
                    showTitleAnnouncement();
                }
            }.runTaskTimer(UltiTools.getInstance(), 
                config.getAnnouncementTitleInterval() * 20L, 
                config.getAnnouncementTitleInterval() * 20L);
        }
    }
    
    /**
     * Broadcasts a chat announcement.
     */
    private void broadcastChatAnnouncement() {
        List<String> messages = config.getAnnouncementChatMessages();
        if (messages.isEmpty()) return;
        
        String message = messages.get(chatIndex % messages.size());
        chatIndex++;
        
        String prefix = colorize(config.getAnnouncementChatPrefix());
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            String parsed = parsePlaceholders(player, message);
            player.sendMessage(prefix + colorize(parsed));
        }
    }
    
    /**
     * Shows a boss bar announcement.
     */
    private void showBossBarAnnouncement() {
        List<String> messages = config.getAnnouncementBossBarMessages();
        if (messages.isEmpty()) return;
        
        String message = messages.get(bossBarIndex % messages.size());
        bossBarIndex++;
        
        // Remove old boss bars
        removeAllBossBars();
        
        // Create new boss bars for all players
        for (Player player : Bukkit.getOnlinePlayers()) {
            String parsed = parsePlaceholders(player, message);
            BossBar bossBar = Bukkit.createBossBar(
                colorize(parsed),
                BarColor.valueOf(config.getAnnouncementBossBarColor()),
                BarStyle.SOLID
            );
            bossBar.addPlayer(player);
            activeBossBars.put(player.getUniqueId(), bossBar);
        }
        
        // Schedule removal
        new BukkitRunnable() {
            @Override
            public void run() {
                removeAllBossBars();
            }
        }.runTaskLater(UltiTools.getInstance(), config.getAnnouncementBossBarDuration() * 20L);
    }
    
    /**
     * Shows a title announcement.
     */
    private void showTitleAnnouncement() {
        List<String> messages = config.getAnnouncementTitleMessages();
        if (messages.isEmpty()) return;
        
        String message = messages.get(titleIndex % messages.size());
        titleIndex++;
        
        String subtitle = "";
        // Check if message contains subtitle (split by ||)
        if (message.contains("||")) {
            String[] parts = message.split("\\|\\|", 2);
            message = parts[0].trim();
            subtitle = parts[1].trim();
        }
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            String parsedTitle = colorize(parsePlaceholders(player, message));
            String parsedSubtitle = colorize(parsePlaceholders(player, subtitle));
            
            player.sendTitle(
                parsedTitle, 
                parsedSubtitle,
                config.getAnnouncementTitleFadeIn(),
                config.getAnnouncementTitleStay(),
                config.getAnnouncementTitleFadeOut()
            );
        }
    }
    
    /**
     * Removes all active boss bars.
     */
    private void removeAllBossBars() {
        for (BossBar bossBar : activeBossBars.values()) {
            bossBar.removeAll();
        }
        activeBossBars.clear();
    }
    
    /**
     * Handles player quit - remove their boss bar.
     */
    public void onPlayerQuit(Player player) {
        BossBar bossBar = activeBossBars.remove(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }
    
    /**
     * Parses PlaceholderAPI placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return text.replace("%player_name%", player.getName());
    }
    
    /**
     * Colorizes a string with color codes.
     */
    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    /**
     * Stops all tasks and cleans up.
     */
    public void shutdown() {
        if (chatTask != null) {
            chatTask.cancel();
            chatTask = null;
        }
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
        if (titleTask != null) {
            titleTask.cancel();
            titleTask = null;
        }
        removeAllBossBars();
    }
    
    /**
     * Reloads the announcement configuration.
     */
    public void reload() {
        shutdown();
        chatIndex = 0;
        bossBarIndex = 0;
        titleIndex = 0;
        startTasks();
    }
}
