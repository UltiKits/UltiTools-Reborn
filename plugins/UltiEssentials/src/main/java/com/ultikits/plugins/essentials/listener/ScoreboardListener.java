package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.service.ScoreboardService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listener for scoreboard-related events.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class ScoreboardListener implements Listener {
    
    @Autowired
    private EssentialsConfig config;
    
    @Autowired
    private ScoreboardService scoreboardService;
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isScoreboardEnabled()) {
            return;
        }
        
        if (config.isScoreboardAutoEnable()) {
            // Delay a bit to ensure player is fully loaded
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                org.bukkit.Bukkit.getPluginManager().getPlugin("UltiTools-API"),
                () -> scoreboardService.enableScoreboard(event.getPlayer()),
                20L // 1 second delay
            );
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        scoreboardService.disableScoreboard(event.getPlayer());
    }
}
