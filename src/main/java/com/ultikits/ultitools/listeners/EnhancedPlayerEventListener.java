package com.ultikits.ultitools.listeners;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

/**
 * 增强的玩家事件监听器
 * 监听玩家相关事件并通过WebSocket发送到UltiPanel
 */
@EventListener
public class EnhancedPlayerEventListener implements Listener {
    
    private ServerMonitorManager getMonitorManager() {
        return UltiTools.getInstance().getServerMonitorManager();
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("joinMessage", event.getJoinMessage());
            
            getMonitorManager().sendPlayerEvent("join", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("quitMessage", event.getQuitMessage());
            
            getMonitorManager().sendPlayerEvent("leave", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("message", event.getMessage());
            additionalData.put("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("chat", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("deathMessage", event.getDeathMessage());
            if (event.getEntity().getKiller() != null) {
                additionalData.put("killer", event.getEntity().getKiller().getName());
            }
            
            getMonitorManager().sendPlayerEvent("death", event.getEntity(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("reason", event.getReason());
            additionalData.put("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("kick", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("command", event.getMessage());
            additionalData.put("cancelled", event.isCancelled());
            
            getMonitorManager().sendPlayerEvent("command", event.getPlayer(), additionalData);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (getMonitorManager() != null && getMonitorManager().isMonitoring()) {
            JSONObject additionalData = new JSONObject();
            additionalData.put("fromWorld", event.getFrom().getName());
            additionalData.put("toWorld", event.getPlayer().getWorld().getName());
            
            getMonitorManager().sendPlayerEvent("world_change", event.getPlayer(), additionalData);
        }
    }
}
