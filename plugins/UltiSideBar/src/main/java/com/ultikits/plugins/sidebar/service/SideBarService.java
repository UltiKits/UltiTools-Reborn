package com.ultikits.plugins.sidebar.service;

import com.ultikits.plugins.sidebar.UltiSideBar;
import com.ultikits.plugins.sidebar.config.SideBarConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player sidebars.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class SideBarService {
    
    @Autowired
    private SideBarConfig config;
    
    // Track player scoreboard state
    private final Map<UUID, Scoreboard> playerScoreboards = new ConcurrentHashMap<>();
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();
    
    // Update task
    private BukkitTask updateTask;
    
    // PlaceholderAPI availability
    private boolean placeholderApiAvailable = false;
    
    /**
     * Initialize the sidebar service.
     */
    public void init() {
        // Check PlaceholderAPI
        placeholderApiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!placeholderApiAvailable) {
            UltiSideBar.getInstance().getLogger().warn("PlaceholderAPI not found! Variables will not work.");
        }
        
        startUpdateTask();
        
        // Initialize for online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (config.isDefaultEnabled()) {
                enableSidebar(player);
            }
        }
    }
    
    /**
     * Shutdown the service.
     */
    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        
        // Remove all scoreboards
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeSidebar(player);
        }
        
        playerScoreboards.clear();
        disabledPlayers.clear();
    }
    
    /**
     * Reload configuration.
     */
    public void reload() {
        shutdown();
        init();
    }
    
    /**
     * Start the update task.
     */
    private void startUpdateTask() {
        if (!config.isEnabled()) {
            return;
        }
        
        updateTask = Bukkit.getScheduler().runTaskTimer(
            UltiTools.getInstance(),
            this::updateAllSidebars,
            0L, config.getUpdateInterval()
        );
    }
    
    /**
     * Update all player sidebars.
     */
    private void updateAllSidebars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSidebarEnabled(player)) {
                updateSidebar(player);
            }
        }
    }
    
    /**
     * Enable sidebar for player.
     */
    public void enableSidebar(Player player) {
        if (!config.isEnabled()) {
            return;
        }
        
        disabledPlayers.remove(player.getUniqueId());
        
        // Check world blacklist
        if (config.getWorldBlacklist().contains(player.getWorld().getName())) {
            return;
        }
        
        // Create scoreboard
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("sidebar", "dummy", 
            ChatColor.translateAlternateColorCodes('&', config.getTitle()));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        playerScoreboards.put(player.getUniqueId(), scoreboard);
        player.setScoreboard(scoreboard);
        
        updateSidebar(player);
    }
    
    /**
     * Disable sidebar for player.
     */
    public void disableSidebar(Player player) {
        disabledPlayers.add(player.getUniqueId());
        removeSidebar(player);
    }
    
    /**
     * Remove sidebar from player.
     */
    public void removeSidebar(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        
        // Reset to main scoreboard
        if (Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
    
    /**
     * Check if sidebar is enabled for player.
     */
    public boolean isSidebarEnabled(Player player) {
        return config.isEnabled() && 
               !disabledPlayers.contains(player.getUniqueId()) &&
               !config.getWorldBlacklist().contains(player.getWorld().getName());
    }
    
    /**
     * Toggle sidebar for player.
     * 
     * @return true if now enabled
     */
    public boolean toggleSidebar(Player player) {
        if (disabledPlayers.contains(player.getUniqueId())) {
            enableSidebar(player);
            return true;
        } else {
            disableSidebar(player);
            return false;
        }
    }
    
    /**
     * Update sidebar for player.
     */
    public void updateSidebar(Player player) {
        Scoreboard scoreboard = playerScoreboards.get(player.getUniqueId());
        if (scoreboard == null) {
            return;
        }
        
        Objective objective = scoreboard.getObjective("sidebar");
        if (objective == null) {
            return;
        }
        
        // Update title
        String title = parsePlaceholders(player, config.getTitle());
        try {
            objective.setDisplayName(ChatColor.translateAlternateColorCodes('&', title));
        } catch (Exception ignored) {
            // Ignore title too long errors
        }
        
        // Get lines
        List<String> lines = config.getLines();
        if (lines == null || lines.isEmpty()) {
            return;
        }
        
        // Clear old entries
        for (String entry : new HashSet<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        
        // Add new entries (reverse order for correct display)
        int score = lines.size();
        Set<String> usedEntries = new HashSet<>();
        
        for (String line : lines) {
            String parsed = parsePlaceholders(player, line);
            parsed = ChatColor.translateAlternateColorCodes('&', parsed);
            
            // Ensure unique entry (add invisible chars if duplicate)
            while (usedEntries.contains(parsed)) {
                parsed = parsed + ChatColor.RESET;
            }
            usedEntries.add(parsed);
            
            // Truncate if too long (max 40 chars in older versions)
            if (parsed.length() > 40) {
                parsed = parsed.substring(0, 40);
            }
            
            try {
                objective.getScore(parsed).setScore(score--);
            } catch (Exception e) {
                // Ignore invalid entries
            }
        }
    }
    
    /**
     * Parse PlaceholderAPI placeholders.
     */
    private String parsePlaceholders(Player player, String text) {
        if (placeholderApiAvailable) {
            try {
                return PlaceholderAPI.setPlaceholders(player, text);
            } catch (Exception e) {
                return text;
            }
        }
        return text;
    }
    
    /**
     * Handle player join.
     */
    public void onPlayerJoin(Player player) {
        if (config.isDefaultEnabled() && !disabledPlayers.contains(player.getUniqueId())) {
            // Delay to allow other plugins to load
            Bukkit.getScheduler().runTaskLater(
                UltiTools.getInstance(),
                () -> enableSidebar(player),
                10L
            );
        }
    }
    
    /**
     * Handle player quit.
     */
    public void onPlayerQuit(Player player) {
        playerScoreboards.remove(player.getUniqueId());
    }
    
    /**
     * Handle world change.
     */
    public void onWorldChange(Player player) {
        if (config.getWorldBlacklist().contains(player.getWorld().getName())) {
            removeSidebar(player);
        } else if (isSidebarEnabled(player) && !playerScoreboards.containsKey(player.getUniqueId())) {
            enableSidebar(player);
        }
    }
}
