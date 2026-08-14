package com.ultikits.ultitools.manager;

import java.time.Instant;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * 玩家事件管理器
 * 处理玩家相关事件的WebSocket消息
 */
@SuppressWarnings("deprecation")
@ApiStatus.Internal
public class PlayerEventManager implements Listener {
    private UltiPanelWebSocketClient webSocketClient;

    /**
     * 是否已经把自己挂进 Bukkit 事件系统。
     * <p>
     * {@link #initialize(UltiPanelWebSocketClient)} 由 {@code initializeManagers()} 调用，而后者挂在
     * WebSocket 的 {@code onConnectHandler} 上——<b>每次 onOpen 都会跑一遍</b>。没有这个守卫的话，
     * 断线重连 N 次就会注册 N 份监听器，同一个玩家事件被发 N 遍。见 issue #180。
     */
    private volatile boolean listenerRegistered = false;

    /**
     * 初始化玩家事件管理器
     * <p>
     * 整个方法与 {@link #shutdown()} 互斥。只让 {@code registerEvents} 单独同步是不够的：
     * 「赋值客户端」与「注册监听器」之间会留下一个窗口，logout 恰好挤进去的话，
     * {@code shutdown()} 摘掉的监听器会被紧随其后的 {@code registerEvents} 又装回去，
     * 于是 {@code disableCloud()} 之后 {@code listenerRegistered} 仍为 true。见 PR #264 的评审。
     *
     * @param client WebSocket客户端
     */
    public synchronized void initialize(UltiPanelWebSocketClient client) {
        // 客户端每次重连都是新造的实例（见 PluginInitiationUtils.getPanelWebsocketClient），
        // 所以引用要无条件更新；只有注册动作是幂等的。
        this.webSocketClient = client;
        registerEvents();
    }

    /**
     * 幂等地注册事件监听器：已注册过就直接返回。
     * <p>
     * 调用方必须已持有本对象的监视器锁（目前唯一调用方是 {@link #initialize}）。
     */
    private synchronized void registerEvents() {
        if (listenerRegistered) {
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        if (plugin == null) {
            UltiTools.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                "[PlayerEventManager] 找不到 UltiTools 插件实例，玩家事件监听器未注册");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listenerRegistered = true;
    }

    /**
     * 注销事件监听器并断开与客户端的关联。
     * <p>
     * 由 {@code PluginInitiationUtils.disableCloud()} 调用——即 {@code /ulticloud logout} 之后。
     * 注意与「断线重连」区分：那种断开之后 {@code initialize} 还会被调回来，注销了反而要重注册；
     * 这里处理的是「云功能被显式关掉」，监听器应当真的摘掉。
     */
    public synchronized void shutdown() {
        HandlerList.unregisterAll(this);
        listenerRegistered = false;
        this.webSocketClient = null;
    }

    /** 供测试断言幂等守卫的状态。 */
    boolean isListenerRegistered() {
        return listenerRegistered;
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
     * <p>
     * 取建连时交给客户端的那份 UUID（即 {@code CommonUtils.getUltiToolsUUID()}，见
     * {@code PluginInitiationUtils.getPanelWebsocketClient}），与所有兄弟 manager 一致。
     * 在 #180 之前这里返回的是一个写死的占位字符串，意味着全球每台服务器发出的
     * {@code player_event} 都自称同一身份，面板侧无法路由。
     *
     * @return 服务器ID
     */
    private String getServerId() {
        return webSocketClient == null ? "unknown" : webSocketClient.getServerId();
    }
}
