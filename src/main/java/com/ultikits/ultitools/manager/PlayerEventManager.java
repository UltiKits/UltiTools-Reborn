package com.ultikits.ultitools.manager;

import java.time.Instant;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * 玩家事件管理器
 * 处理玩家相关事件的WebSocket消息
 */
@SuppressWarnings("deprecation")
public class PlayerEventManager implements Listener {
    private UltiPanelWebSocketClient webSocketClient;
    
    /**
     * 初始化玩家事件管理器
     * @param client WebSocket客户端
     */
    public void initialize(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, 
            Bukkit.getPluginManager().getPlugin("UltiTools"));
    }
    
    /**
     * 处理玩家加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        
        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_join");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("join_message", event.getJoinMessage());
        data.addProperty("online_count", Bukkit.getOnlinePlayers().size());
        
        sendPlayerEvent(data);
        
        // 同时发送日志流消息
        UltiTools.getInstance().getLogStreamManager().sendPlayerEventLog(
            "玩家加入", player.getName(), 
            String.format("玩家加入服务器，当前在线: %d人", Bukkit.getOnlinePlayers().size())
        );
    }
    
    /**
     * 处理玩家退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        
        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_quit");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("quit_message", event.getQuitMessage());
        data.addProperty("online_count", Math.max(0, Bukkit.getOnlinePlayers().size() - 1));
        
        sendPlayerEvent(data);
        
        // 同时发送日志流消息
        UltiTools.getInstance().getLogStreamManager().sendPlayerEventLog(
            "玩家退出", player.getName(),
            String.format("玩家离开服务器，当前在线: %d人", Math.max(0, Bukkit.getOnlinePlayers().size() - 1))
        );
    }
    
    /**
     * 处理玩家聊天事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(PlayerChatEvent event) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        
        Player player = event.getPlayer();
        JsonObject data = new JsonObject();
        data.addProperty("event_type", "player_chat");
        data.addProperty("player_name", player.getName());
        data.addProperty("player_uuid", player.getUniqueId().toString());
        data.addProperty("message", event.getMessage());
        data.addProperty("format", event.getFormat());
        
        sendPlayerEvent(data);
        
        // 同时发送日志流消息（聊天消息通常比较频繁，使用debug级别）
        UltiTools.getInstance().getLogStreamManager().sendCustomLog(
            "debug",
            String.format("[聊天] <%s> %s", player.getName(), event.getMessage()),
            "plugin:UltiTools"
        );
    }
    
    /**
     * 发送玩家事件到UltiPanel
     * @param data 事件数据
     */
    private void sendPlayerEvent(JsonObject data) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "player_event");
        message.add("data", data);
        message.addProperty("timestamp", Instant.now().toEpochMilli());
        message.addProperty("serverId", getServerId());
        
        webSocketClient.sendMessage(message);
    }
    
    /**
     * 获取服务器ID
     * @return 服务器ID
     */
    private String getServerId() {
        // 这里应该返回配置中的服务器ID或生成一个唯一ID
        return "minecraft-server-1";
    }
}
