package com.ultikits.plugins.trade.service;

import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.PlayerTradeSettings;
import com.ultikits.plugins.trade.entity.TradeLogData;
import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing trade logs and player settings.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class TradeLogService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private TradeConfig config;
    
    // Player settings cache
    private final Map<UUID, PlayerTradeSettings> settingsCache = new ConcurrentHashMap<>();
    
    // Data operators
    private DataOperator<TradeLogData> logOperator;
    private DataOperator<PlayerTradeSettings> settingsOperator;
    
    // Bukkit plugin instance for scheduler tasks
    private Plugin bukkitPlugin;

    // Cleanup task
    private BukkitTask cleanupTask;
    
    /**
     * Initialize the log service.
     */
    public void init() {
        // Initialize Bukkit plugin reference for scheduler tasks
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

        // Initialize data operators
        logOperator = plugin.getDataOperator(TradeLogData.class);
        settingsOperator = plugin.getDataOperator(PlayerTradeSettings.class);

        // Start cleanup task
        if (config.isEnableTradeLog()) {
            long cleanupInterval = config.getCleanupIntervalHours() * 60L * 60L * 20L; // Convert hours to ticks
            cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                bukkitPlugin,
                this::cleanupOldLogs,
                cleanupInterval, // Initial delay
                cleanupInterval  // Repeat interval
            );
        }
    }
    
    /**
     * Shutdown the service.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        
        // Save all cached settings
        for (PlayerTradeSettings settings : settingsCache.values()) {
            try {
                settingsOperator.update(settings);
            } catch (Exception e) {
                plugin.getLogger().warn(e,
                    "Failed to save player settings: " + settings.getPlayerUuid());
            }
        }
        settingsCache.clear();
    }
    
    /**
     * Log a completed trade.
     *
     * @param session The completed trade session
     * @param player1 Player 1
     * @param player2 Player 2
     * @param moneyTax Money tax collected
     * @param expTax Experience tax collected
     */
    public void logCompletedTrade(TradeSession session, Player player1, Player player2,
                                   double moneyTax, int expTax) {
        if (!config.isEnableTradeLog()) {
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            try {
                TradeLogData log = new TradeLogData(
                    session.getSessionId(),
                    session.getPlayer1(),
                    player1.getName(),
                    session.getPlayer2(),
                    player2.getName()
                );
                
                // Set items
                log.setPlayer1Items(session.getPlayerItems(session.getPlayer1()).values());
                log.setPlayer2Items(session.getPlayerItems(session.getPlayer2()).values());
                
                // Set money and exp
                log.setPlayer1Money(session.getPlayerMoney(session.getPlayer1()));
                log.setPlayer2Money(session.getPlayerMoney(session.getPlayer2()));
                log.setPlayer1Exp(session.getPlayerExp(session.getPlayer1()));
                log.setPlayer2Exp(session.getPlayerExp(session.getPlayer2()));
                
                // Set tax
                log.setMoneyTaxCollected(moneyTax);
                log.setExpTaxCollected(expTax);
                
                // Mark completed
                log.markCompleted();
                
                // Save to database
                logOperator.insert(log);
                
                // Update player statistics
                updatePlayerStats(session.getPlayer1(), player1.getName(),
                    session.getPlayerMoney(session.getPlayer1()),
                    session.getPlayerExp(session.getPlayer1()));
                updatePlayerStats(session.getPlayer2(), player2.getName(),
                    session.getPlayerMoney(session.getPlayer2()),
                    session.getPlayerExp(session.getPlayer2()));
                
            } catch (Exception e) {
                plugin.getLogger().warn(e,
                    "Failed to log trade");
            }
        });
    }
    
    /**
     * Log a cancelled trade.
     *
     * @param session The cancelled trade session
     * @param reason Cancellation reason
     */
    public void logCancelledTrade(TradeSession session, String reason) {
        if (!config.isEnableTradeLog()) {
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            try {
                Player player1 = Bukkit.getPlayer(session.getPlayer1());
                Player player2 = Bukkit.getPlayer(session.getPlayer2());
                
                TradeLogData log = new TradeLogData(
                    session.getSessionId(),
                    session.getPlayer1(),
                    player1 != null ? player1.getName() : "Unknown",
                    session.getPlayer2(),
                    player2 != null ? player2.getName() : "Unknown"
                );
                
                // Set items that were in the trade
                log.setPlayer1Items(session.getPlayerItems(session.getPlayer1()).values());
                log.setPlayer2Items(session.getPlayerItems(session.getPlayer2()).values());
                
                // Set money and exp
                log.setPlayer1Money(session.getPlayerMoney(session.getPlayer1()));
                log.setPlayer2Money(session.getPlayerMoney(session.getPlayer2()));
                log.setPlayer1Exp(session.getPlayerExp(session.getPlayer1()));
                log.setPlayer2Exp(session.getPlayerExp(session.getPlayer2()));
                
                // Mark cancelled
                log.markCancelled(reason);
                
                // Save to database
                logOperator.insert(log);
                
            } catch (Exception e) {
                plugin.getLogger().warn(e,
                    "Failed to log cancelled trade");
            }
        });
    }
    
    /**
     * Update player trade statistics.
     */
    private void updatePlayerStats(UUID playerUuid, String playerName, 
                                   double moneyTraded, int expTraded) {
        PlayerTradeSettings settings = getOrCreateSettings(playerUuid, playerName);
        settings.incrementTradeStats(moneyTraded, expTraded);
        saveSettings(settings);
    }
    
    /**
     * Cleanup old logs based on retention days.
     */
    private void cleanupOldLogs() {
        try {
            int retentionDays = config.getLogRetentionDays();
            long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
            
            // Get all logs and filter expired ones
            List<TradeLogData> allLogs = logOperator.getAll();
            int deleted = 0;
            
            for (TradeLogData log : allLogs) {
                if (log.getTradeTime() < cutoffTime) {
                    logOperator.delById(log.getId());
                    deleted++;
                }
            }
            
            if (deleted > 0) {
                plugin.getLogger().info(
                    "Cleaned up " + deleted + " expired trade logs (older than " + retentionDays + " days)");
            }
        } catch (Exception e) {
            plugin.getLogger().warn(e,
                "Failed to cleanup old logs");
        }
    }
    
    // ==================== Player Settings Management ====================
    
    /**
     * Get or create player settings.
     *
     * @param playerUuid Player UUID
     * @param playerName Player name
     * @return PlayerTradeSettings instance
     */
    public PlayerTradeSettings getOrCreateSettings(UUID playerUuid, String playerName) {
        // Check cache first
        PlayerTradeSettings cached = settingsCache.get(playerUuid);
        if (cached != null) {
            return cached;
        }
        
        // Try to load from database
        List<PlayerTradeSettings> existing = settingsOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();
        
        PlayerTradeSettings settings;
        if (existing != null && !existing.isEmpty()) {
            settings = existing.get(0);
            // Update name if changed
            if (!playerName.equals(settings.getPlayerName())) {
                settings.setPlayerName(playerName);
                saveSettings(settings);
            }
        } else {
            // Create new settings
            settings = new PlayerTradeSettings(playerUuid, playerName);
            settingsOperator.insert(settings);
        }
        
        settingsCache.put(playerUuid, settings);
        return settings;
    }
    
    /**
     * Get player settings (may return null if not found).
     *
     * @param playerUuid Player UUID
     * @return PlayerTradeSettings or null
     */
    public PlayerTradeSettings getSettings(UUID playerUuid) {
        PlayerTradeSettings cached = settingsCache.get(playerUuid);
        if (cached != null) {
            return cached;
        }

        List<PlayerTradeSettings> existing = settingsOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();
        
        if (existing != null && !existing.isEmpty()) {
            PlayerTradeSettings settings = existing.get(0);
            settingsCache.put(playerUuid, settings);
            return settings;
        }
        
        return null;
    }
    
    /**
     * Save player settings.
     *
     * @param settings Settings to save
     */
    public void saveSettings(PlayerTradeSettings settings) {
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            try {
                settingsOperator.update(settings);
            } catch (Exception e) {
                plugin.getLogger().warn(e,
                    "Failed to save player settings");
            }
        });
    }
    
    /**
     * Check if player has trade enabled.
     *
     * @param playerUuid Player UUID
     * @return true if trade is enabled
     */
    public boolean isTradeEnabled(UUID playerUuid) {
        PlayerTradeSettings settings = getSettings(playerUuid);
        return settings == null || settings.isTradeEnabled();
    }
    
    /**
     * Toggle trade status for player.
     *
     * @param player Player
     * @return new trade enabled status
     */
    public boolean toggleTrade(Player player) {
        PlayerTradeSettings settings = getOrCreateSettings(player.getUniqueId(), player.getName());
        settings.setTradeEnabled(!settings.isTradeEnabled());
        saveSettings(settings);
        return settings.isTradeEnabled();
    }
    
    /**
     * Check if target is blocked by player.
     *
     * @param playerUuid Player UUID
     * @param targetUuid Target UUID
     * @return true if target is blocked
     */
    public boolean isBlocked(UUID playerUuid, UUID targetUuid) {
        PlayerTradeSettings settings = getSettings(playerUuid);
        return settings != null && settings.isBlocked(targetUuid.toString());
    }
    
    /**
     * Block a player.
     *
     * @param player Player doing the blocking
     * @param targetUuid Target to block
     * @return true if blocked successfully
     */
    public boolean blockPlayer(Player player, UUID targetUuid) {
        PlayerTradeSettings settings = getOrCreateSettings(player.getUniqueId(), player.getName());
        boolean result = settings.blockPlayer(targetUuid.toString());
        if (result) {
            saveSettings(settings);
        }
        return result;
    }
    
    /**
     * Unblock a player.
     *
     * @param player Player doing the unblocking
     * @param targetUuid Target to unblock
     * @return true if unblocked successfully
     */
    public boolean unblockPlayer(Player player, UUID targetUuid) {
        PlayerTradeSettings settings = getOrCreateSettings(player.getUniqueId(), player.getName());
        boolean result = settings.unblockPlayer(targetUuid.toString());
        if (result) {
            saveSettings(settings);
        }
        return result;
    }
    
    /**
     * Get player statistics.
     *
     * @param playerUuid Player UUID
     * @return PlayerTradeSettings with statistics (may be default values if not found)
     */
    public PlayerTradeSettings getPlayerStats(UUID playerUuid) {
        PlayerTradeSettings settings = getSettings(playerUuid);
        if (settings == null) {
            settings = new PlayerTradeSettings();
            settings.setPlayerUuid(playerUuid.toString());
        }
        return settings;
    }
    
    /**
     * Get trade logs for a player.
     *
     * @param playerUuid Player UUID
     * @param limit Maximum number of logs to return
     * @return List of trade logs
     */
    public List<TradeLogData> getPlayerLogs(UUID playerUuid, int limit) {
        try {
            List<TradeLogData> allLogs = logOperator.getAll();
            String uuidStr = playerUuid.toString();
            
            List<TradeLogData> playerLogs = new ArrayList<>();
            for (TradeLogData log : allLogs) {
                if (uuidStr.equals(log.getPlayer1Uuid()) || uuidStr.equals(log.getPlayer2Uuid())) {
                    playerLogs.add(log);
                }
            }
            
            // Sort by time descending
            playerLogs.sort((a, b) -> Long.compare(b.getTradeTime(), a.getTradeTime()));
            
            // Limit results
            if (playerLogs.size() > limit) {
                return playerLogs.subList(0, limit);
            }
            return playerLogs;
            
        } catch (Exception e) {
            plugin.getLogger().warn(e,
                "Failed to get player logs");
            return new ArrayList<>();
        }
    }
}
