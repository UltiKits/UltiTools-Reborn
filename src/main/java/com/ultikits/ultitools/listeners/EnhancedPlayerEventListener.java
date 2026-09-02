package com.ultikits.ultitools.listeners;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

/**
 * Enhanced player event listener.
 * Listens for player-related events and sends them to UltiPanel over WebSocket.
 */
@EventListener
public class EnhancedPlayerEventListener implements Listener {
    
    private ServerMonitorManager getMonitorManager() {
        return UltiTools.getInstance().getServerMonitorManager();
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("joinMessage", event.getJoinMessage());
            
            getMonitorManager().sendPlayerEvent("join", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("quitMessage", event.getQuitMessage());
            
            getMonitorManager().sendPlayerEvent("leave", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("message", event.getMessage());
            additionalData.addProperty("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("chat", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("deathMessage", event.getDeathMessage());
            if (event.getEntity().getKiller() != null) {
                additionalData.addProperty("killer", event.getEntity().getKiller().getName());
            }
            
            getMonitorManager().sendPlayerEvent("death", event.getEntity(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("reason", event.getReason());
            additionalData.addProperty("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("kick", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("command", event.getMessage());
            additionalData.addProperty("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("command", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JsonObject additionalData = new JsonObject();
            additionalData.addProperty("fromWorld", event.getFrom().getName());
            additionalData.addProperty("toWorld", event.getPlayer().getWorld().getName());
            
            getMonitorManager().sendPlayerEvent("world_change", event.getPlayer(), additionalData);
        }
    }
}
