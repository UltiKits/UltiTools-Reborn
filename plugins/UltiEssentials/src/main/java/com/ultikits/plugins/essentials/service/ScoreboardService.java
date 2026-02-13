package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import lombok.extern.slf4j.Slf4j;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import com.ultikits.ultitools.annotations.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player scoreboards.
 * <p>
 * 管理玩家计分板的服务。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Slf4j
@Service
public class ScoreboardService {
    
    @Autowired
    private EssentialsConfig config;

    private Plugin bukkitPlugin;

    // Player UUIDs with active scoreboards
    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

    // Main update task
    private BukkitTask updateTask;

    // Scoreboard manager
    private ScoreboardManager manager;
    
    /**
     * Initializes the scoreboard service.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        this.manager = Bukkit.getScoreboardManager();

        if (manager == null) {
            log.warn("Failed to get scoreboard manager, scoreboard feature disabled");
            return;
        }
        
        // Start update task if enabled
        if (config.isScoreboardEnabled()) {
            startUpdateTask();
        }
    }
    
    /**
     * Starts the scoreboard update task.
     */
    private void startUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        
        int updateInterval = config.getScoreboardUpdateInterval();
        
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : enabledPlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        updateScoreboard(player);
                    } else {
                        enabledPlayers.remove(uuid);
                    }
                }
            }
        }.runTaskTimer(bukkitPlugin, 20L, updateInterval * 20L);
    }
    
    /**
     * Enables scoreboard for a player.
     */
    public void enableScoreboard(Player player) {
        if (!config.isScoreboardEnabled()) {
            return;
        }
        
        enabledPlayers.add(player.getUniqueId());
        updateScoreboard(player);
    }
    
    /**
     * Disables scoreboard for a player.
     */
    public void disableScoreboard(Player player) {
        enabledPlayers.remove(player.getUniqueId());
        
        // Reset to default scoreboard
        if (manager != null) {
            player.setScoreboard(manager.getNewScoreboard());
        }
    }
    
    /**
     * Toggles scoreboard for a player.
     */
    public boolean toggleScoreboard(Player player) {
        if (isEnabled(player)) {
            disableScoreboard(player);
            return false;
        } else {
            enableScoreboard(player);
            return true;
        }
    }
    
    /**
     * Checks if scoreboard is enabled for a player.
     */
    public boolean isEnabled(Player player) {
        return enabledPlayers.contains(player.getUniqueId());
    }
    
    /**
     * Updates the scoreboard for a player.
     */
    public void updateScoreboard(Player player) {
        if (manager == null || !enabledPlayers.contains(player.getUniqueId())) {
            return;
        }
        
        Scoreboard scoreboard = manager.getNewScoreboard();
        String title = parsePlaceholders(player, config.getScoreboardTitle());
        
        Objective objective = scoreboard.registerNewObjective(
            "ultiessentials",
            "dummy",
            colorize(title)
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        List<String> lines = config.getScoreboardLines();
        int score = lines.size();
        
        for (String line : lines) {
            String parsedLine = parsePlaceholders(player, line);
            parsedLine = colorize(parsedLine);
            
            // Handle duplicate lines by adding invisible characters
            parsedLine = ensureUnique(scoreboard, parsedLine);
            
            Score scoreEntry = objective.getScore(parsedLine);
            scoreEntry.setScore(score--);
        }
        
        player.setScoreboard(scoreboard);
    }
    
    /**
     * Ensures a line is unique by adding invisible characters if necessary.
     */
    private String ensureUnique(Scoreboard scoreboard, String line) {
        String original = line;
        String result = line;
        int attempt = 0;

        while (scoreboard.getEntries().contains(result) && attempt < 16) {
            result = original + ChatColor.values()[attempt].toString();
            attempt++;
        }

        // Truncate if too long (scoreboard limit is 40 characters in modern MC)
        if (result.length() > 40) {
            result = result.substring(0, 40);
        }

        return result;
    }
    
    /**
     * Parses PlaceholderAPI placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        
        // Fallback - basic placeholders
        text = text.replace("%player_name%", player.getName());
        text = text.replace("%player_health%", String.valueOf((int) player.getHealth()));
        text = text.replace("%player_food%", String.valueOf(player.getFoodLevel()));
        text = text.replace("%player_level%", String.valueOf(player.getLevel()));
        text = text.replace("%player_world%", player.getWorld().getName());
        text = text.replace("%online_players%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        text = text.replace("%max_players%", String.valueOf(Bukkit.getMaxPlayers()));
        
        return text;
    }
    
    /**
     * Colorizes a string with color codes.
     */
    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    
    /**
     * Stops the update task and cleans up.
     */
    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        
        // Reset all player scoreboards
        for (UUID uuid : enabledPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && manager != null) {
                player.setScoreboard(manager.getNewScoreboard());
            }
        }
        
        enabledPlayers.clear();
    }
    
    /**
     * Reloads the scoreboard configuration.
     */
    public void reload() {
        shutdown();
        
        if (config.isScoreboardEnabled()) {
            startUpdateTask();
            
            // Re-enable for all online players if auto-enable is set
            if (config.isScoreboardAutoEnable()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    enableScoreboard(player);
                }
            }
        }
    }
}
