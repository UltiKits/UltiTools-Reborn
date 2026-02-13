package com.ultikits.plugins.sidebar.service;

import com.ultikits.plugins.sidebar.config.SideBarConfig;
import com.ultikits.plugins.sidebar.data.SideBarPreference;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
    private UltiToolsPlugin plugin;

    @Autowired
    private SideBarConfig config;
    
    // Track player scoreboard state
    private final Map<UUID, Scoreboard> playerScoreboards = new ConcurrentHashMap<>();
    
    // Content cache for performance optimization (avoid unnecessary scoreboard updates)
    private final Map<UUID, List<String>> contentCache = new ConcurrentHashMap<>();
    
    // Data operator for persistent storage
    private DataOperator<SideBarPreference> dataOperator;
    
    // Update task
    private BukkitTask updateTask;

    // Bukkit plugin instance for scheduler calls
    private Plugin bukkitPlugin;

    // PlaceholderAPI availability
    private boolean placeholderApiAvailable = false;

    /**
     * Initialize the sidebar service.
     */
    public void init() {
        // Initialize data operator for persistent storage
        dataOperator = plugin.getDataOperator(SideBarPreference.class);
        bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

        // Check PlaceholderAPI
        placeholderApiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!placeholderApiAvailable) {
            plugin.getLogger().warn("PlaceholderAPI not found! Variables will not work.");
        }
        
        // Register config change listener
        config.addChangeListener(cfg -> {
            clearCache();
            refreshAllSidebars();
        });
        
        startUpdateTask();
        
        // Initialize for online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (config.isDefaultEnabled() && isSidebarEnabledInDatabase(player.getUniqueId())) {
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
            updateTask = null;
        }
        
        // Remove all scoreboards
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeSidebar(player);
        }
        
        playerScoreboards.clear();
        contentCache.clear();
    }
    
    /**
     * Reload configuration and refresh all sidebars.
     */
    public void reload() {
        shutdown();
        init();
    }
    
    /**
     * Clear content cache (called on config reload).
     */
    public void clearCache() {
        contentCache.clear();
    }
    
    /**
     * Refresh all online players' sidebars.
     */
    private void refreshAllSidebars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSidebarEnabled(player)) {
                removeSidebar(player);
                enableSidebar(player);
            }
        }
    }
    
    /**
     * Start the update task.
     */
    private void startUpdateTask() {
        if (!config.isEnabled()) {
            return;
        }
        
        updateTask = Bukkit.getScheduler().runTaskTimer(
            bukkitPlugin,
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
        
        // Update database
        setSidebarEnabledInDatabase(player.getUniqueId(), true);
        
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
        setSidebarEnabledInDatabase(player.getUniqueId(), false);
        removeSidebar(player);
    }
    
    /**
     * Remove sidebar from player.
     */
    public void removeSidebar(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        contentCache.remove(player.getUniqueId());
        
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
               isSidebarEnabledInDatabase(player.getUniqueId()) &&
               !config.getWorldBlacklist().contains(player.getWorld().getName());
    }
    
    /**
     * Check if sidebar is enabled in database for player.
     * Returns true if not found (default enabled based on config).
     */
    private boolean isSidebarEnabledInDatabase(UUID playerUuid) {
        List<SideBarPreference> prefs = dataOperator.query()
            .where("player_uuid")
            .eq(playerUuid.toString())
            .list();

        if (prefs.isEmpty()) {
            return config.isDefaultEnabled();
        }

        Boolean enabled = prefs.get(0).getEnabled();
        return enabled != null ? enabled : config.isDefaultEnabled();
    }
    
    /**
     * Set sidebar enabled state in database.
     */
    private void setSidebarEnabledInDatabase(UUID playerUuid, boolean enabled) {
        List<SideBarPreference> existing = dataOperator.query()
            .where("player_uuid")
            .eq(playerUuid.toString())
            .list();

        if (existing.isEmpty()) {
            // Insert new record
            SideBarPreference pref = new SideBarPreference(playerUuid.toString(), enabled);
            dataOperator.insert(pref);
        } else {
            // Update existing record
            SideBarPreference pref = existing.get(0);
            dataOperator.update("enabled", enabled, pref.getId());
        }
    }
    
    /**
     * Toggle sidebar for player.
     * 
     * @return true if now enabled
     */
    public boolean toggleSidebar(Player player) {
        if (isSidebarEnabledInDatabase(player.getUniqueId())) {
            disableSidebar(player);
            return false;
        } else {
            enableSidebar(player);
            return true;
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
        
        // Parse and build new content
        List<String> newContent = new ArrayList<>();
        Set<String> usedEntries = new HashSet<>();
        
        for (String line : lines) {
            String parsed = parsePlaceholders(player, line);
            parsed = ChatColor.translateAlternateColorCodes('&', parsed);
            
            // Ensure unique entry (add invisible chars if duplicate)
            String uniqueParsed = parsed;
            while (usedEntries.contains(uniqueParsed)) {
                uniqueParsed = uniqueParsed + ChatColor.RESET;
            }
            usedEntries.add(uniqueParsed);
            
            // Truncate if too long (max 40 chars in older versions)
            if (uniqueParsed.length() > 40) {
                uniqueParsed = uniqueParsed.substring(0, 40);
            }
            
            newContent.add(uniqueParsed);
        }
        
        // Check if content changed (performance optimization)
        List<String> cachedContent = contentCache.get(player.getUniqueId());
        if (cachedContent != null && cachedContent.equals(newContent)) {
            return; // No change, skip update
        }
        
        // Update cache
        contentCache.put(player.getUniqueId(), new ArrayList<>(newContent));
        
        // Clear old entries
        for (String entry : new HashSet<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        
        // Add new entries (reverse order for correct display)
        int score = newContent.size();
        for (String entry : newContent) {
            try {
                objective.getScore(entry).setScore(score--);
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
        if (isSidebarEnabledInDatabase(player.getUniqueId())) {
            // Delay to allow other plugins to load
            Bukkit.getScheduler().runTaskLater(
                bukkitPlugin,
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
        contentCache.remove(player.getUniqueId());
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
