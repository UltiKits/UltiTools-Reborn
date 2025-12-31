package com.ultikits.ultitools.manager;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChatEvent;

import java.time.Instant;

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
        JSONObject data = new JSONObject();
        data.put("event_type", "player_join");
        data.put("player_name", player.getName());
        data.put("player_uuid", player.getUniqueId().toString());
        data.put("join_message", event.getJoinMessage());
        data.put("online_count", Bukkit.getOnlinePlayers().size());
        
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
        JSONObject data = new JSONObject();
        data.put("event_type", "player_quit");
        data.put("player_name", player.getName());
        data.put("player_uuid", player.getUniqueId().toString());
        data.put("quit_message", event.getQuitMessage());
        data.put("online_count", Math.max(0, Bukkit.getOnlinePlayers().size() - 1));
        
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
        JSONObject data = new JSONObject();
        data.put("event_type", "player_chat");
        data.put("player_name", player.getName());
        data.put("player_uuid", player.getUniqueId().toString());
        data.put("message", event.getMessage());
        data.put("format", event.getFormat());
        
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
    private void sendPlayerEvent(JSONObject data) {
        JSONObject message = new JSONObject();
        message.put("type", "player_event");
        message.put("data", data);
        message.put("timestamp", Instant.now().toEpochMilli());
        message.put("serverId", getServerId());
        
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
