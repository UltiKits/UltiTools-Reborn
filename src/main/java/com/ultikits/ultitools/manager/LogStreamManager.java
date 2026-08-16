package com.ultikits.ultitools.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * 日志流管理器
 * 负责实时监控服务器日志并通过WebSocket传输到UltiPanel
 * 
 * @author UltiKits
 * @version 2.0.0
 */
@ApiStatus.Internal
public class LogStreamManager implements Listener {
    
    private static LogStreamManager instance;
    private UltiPanelWebSocketClient webSocketClient;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> subscribedClients = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    
    @Getter
    private UltiPanelLogTransmitter logTransmitter;
    private SystemLogHandler systemLogHandler;
    
    private LogStreamManager() {
        // 注册事件监听器
        try {
            Bukkit.getPluginManager().registerEvents(this, UltiTools.getInstance());
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("注册事件监听器失败: " + e.getMessage());
        }
    }
    
    public static LogStreamManager getInstance() {
        if (instance == null) {
            instance = new LogStreamManager();
        }
        return instance;
    }
    
    /**
     * 初始化日志流管理器
     */
    public void initialize(UltiPanelWebSocketClient client) {
        // 幂等：重复调用必须先拆掉上一轮留下的东西。
        //
        // 这个方法由 onConnectHandler 调用，而 onConnectHandler 在**每一次** onOpen 都会跑
        // ——包括重连窗口内的 reconnect()，以及 reinitWebSocket 造出新客户端之后的那次。
        // 原先它无条件 new 一个 UltiPanelLogTransmitter（起一条传输线程）并往 JVM root logger
        // 挂一个新的 SystemLogHandler，而 removeHandler 只写在 shutdown() 里，shutdown() 的
        // 唯一调用者是 onDisable。于是连接每抖动一次就泄漏一个 handler 加一条线程，
        // 每行日志被重复发送 N 次。见 issue #181。
        detachAllSystemLogHandlers();
        if (logTransmitter != null) {
            try {
                logTransmitter.shutdown();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().warning(
                    "[UltiPanel] Error shutting down previous log transmitter: " + e.getMessage());
            }
        }

        this.webSocketClient = client;

        // 初始化日志传输器
        String serverId = getServerId();
        this.logTransmitter = new UltiPanelLogTransmitter(client, serverId);

        // 从配置文件加载批量发送设置
        loadBatchConfiguration();

        // 创建并配置系统日志处理器
        this.systemLogHandler = new SystemLogHandler(logTransmitter);
        this.systemLogHandler.loadConfiguration();

        // 添加系统日志处理器到根Logger
        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(systemLogHandler);

        // 自动启动日志流（立即开始监控和发送日志）
        startLogStream("auto", "info");
        
        UltiTools.getInstance().getLogger().info("[UltiPanel] LogStreamManager initialized and log streaming started");
        
        // 发送初始化完成日志
        sendInitializationLogs();
    }
    
    /**
     * 从配置文件加载批量发送配置
     */
    private void loadBatchConfiguration() {
        if (logTransmitter == null) {
            return;
        }
        
        try {
            // 加载批量发送配置
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.enabled")) {
                boolean batchEnabled = UltiTools.getInstance().getConfig().getBoolean("ultipanel.logging.batch.enabled", true);
                logTransmitter.setBatchEnabled(batchEnabled);
            }
            
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.size")) {
                int batchSize = UltiTools.getInstance().getConfig().getInt("ultipanel.logging.batch.size", 10);
                logTransmitter.setBatchSize(Math.max(1, batchSize));
            }
            
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.interval")) {
                int interval = UltiTools.getInstance().getConfig().getInt("ultipanel.logging.batch.interval", 5000);
                logTransmitter.setIntervalMs(Math.max(1000, interval));
            }
            
            UltiTools.getInstance().getLogger().info(String.format(
                "[UltiPanel] 日志传输配置 - 批量发送: %s, 批量大小: %d, 发送间隔: %dms",
                logTransmitter.isBatchEnabled(), logTransmitter.getBatchSize(), logTransmitter.getIntervalMs()));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("[UltiPanel] 加载批量发送配置失败，使用默认配置: " + e.getMessage());
        }
    }
    
    /**
     * 发送初始化完成相关日志
     */
    private void sendInitializationLogs() {
        // 发送服务器启动信息
        logTransmitter.info("UltiTools 日志传输系统已启动", "plugin:UltiTools");
        
        // 发送当前在线玩家信息
        int onlineCount = Bukkit.getOnlinePlayers().size();
        logTransmitter.info(String.format("当前在线玩家数量: %d", onlineCount), "server");
        
        // 发送系统配置信息
        if (systemLogHandler != null) {
            String configInfo = systemLogHandler.getConfigurationInfo();
            UltiTools.getInstance().getLogger().info(configInfo);
        }
    }
    
    /**
     * 处理日志流消息
     */
    public void handleLogStreamMessage(JsonObject data) {
        if (data == null) {
            UltiTools.getInstance().getLogger().warning("LogStreamManager: 收到空的日志流消息");
            return;
        }
        
        String action = data.has("action") && !data.get("action").isJsonNull() 
            ? data.get("action").getAsString() : null;
        String clientId = data.has("clientId") && !data.get("clientId").isJsonNull() 
            ? data.get("clientId").getAsString() : null;
        String level = data.has("level") && !data.get("level").isJsonNull() 
            ? data.get("level").getAsString() : null; // 可选的日志级别过滤
        
        if (clientId == null) {
            clientId = "default";
        }
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 处理日志流操作 - 动作: %s, 客户端: %s, 级别: %s", 
                action, clientId, level));
        
        switch (action != null ? action : "") {
            case "start":
                startLogStream(clientId, level);
                break;
            case "stop":
                stopLogStream(clientId);
                break;
            case "pause":
                pauseLogStream(clientId);
                break;
            case "resume":
                resumeLogStream(clientId);
                break;
            case "status":
                sendStreamStatus(clientId);
                break;
            case "config":
                handleConfigUpdate(data, clientId);
                break;
            default:
                UltiTools.getInstance().getLogger().warning(
                    String.format("LogStreamManager: 未知的日志流操作: %s", action));
                sendErrorResponse(clientId, "Unknown log stream action: " + action);
                break;
        }
    }
    
    /**
     * 处理配置更新
     */
    private void handleConfigUpdate(JsonObject data, String clientId) {
        try {
            // 更新日志级别配置
            if (data.has("levels") && systemLogHandler != null) {
                // 这里可以动态更新日志级别配置
                UltiTools.getInstance().getLogger().info("[UltiPanel] 收到日志级别配置更新请求");
            }
            
            // 更新批量发送配置
            if (data.has("batchConfig") && logTransmitter != null) {
                JsonObject batchConfig = data.getAsJsonObject("batchConfig");
                if (batchConfig.has("enabled") && !batchConfig.get("enabled").isJsonNull()) {
                    logTransmitter.setBatchEnabled(batchConfig.get("enabled").getAsBoolean());
                }
                if (batchConfig.has("size") && !batchConfig.get("size").isJsonNull()) {
                    logTransmitter.setBatchSize(batchConfig.get("size").getAsInt());
                }
                if (batchConfig.has("interval") && !batchConfig.get("interval").isJsonNull()) {
                    logTransmitter.setIntervalMs(batchConfig.get("interval").getAsInt());
                }
                UltiTools.getInstance().getLogger().info("[UltiPanel] 批量发送配置已更新");
            }
            
            sendStreamResponse(clientId, "config_updated", "Configuration updated successfully");
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("[UltiPanel] 更新配置失败: " + e.getMessage());
            sendErrorResponse(clientId, "Failed to update configuration: " + e.getMessage());
        }
    }
    
    /**
     * 开始日志流
     */
    public void startLogStream(String clientId, String level) {
        subscribedClients.put(clientId, true);
        streaming.set(true);
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 启动日志流，级别: %s", clientId, level));
        
        // 发送确认消息
        sendStreamResponse(clientId, "started", "Log stream started successfully");
    }
    
    /**
     * 开始日志流（兼容旧版本）
     */
    public void startLogStream(String clientId) {
        startLogStream(clientId, "info");
    }
    
    /**
     * 停止日志流
     */
    public void stopLogStream(String clientId) {
        subscribedClients.remove(clientId);
        if (subscribedClients.isEmpty()) {
            streaming.set(false);
        }
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 停止日志流", clientId));
        
        // 发送确认消息
        sendStreamResponse(clientId, "stopped", "Log stream stopped successfully");
    }
    
    /**
     * 暂停日志流
     */
    public void pauseLogStream(String clientId) {
        subscribedClients.put(clientId, false); // 标记为暂停状态
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 暂停日志流", clientId));
        
        sendStreamResponse(clientId, "paused", "Log stream paused");
    }
    
    /**
     * 恢复日志流
     */
    public void resumeLogStream(String clientId) {
        subscribedClients.put(clientId, true);
        streaming.set(true);
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 恢复日志流", clientId));
        
        sendStreamResponse(clientId, "resumed", "Log stream resumed");
    }
    
    /**
     * 发送流响应消息
     */
    private void sendStreamResponse(String clientId, String status, String message) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "log_stream_response");
            response.addProperty("serverId", getServerId());
            response.addProperty("timestamp", System.currentTimeMillis());
            
            JsonObject data = new JsonObject();
            data.addProperty("status", status);
            data.addProperty("message", message);
            data.addProperty("clientId", clientId);
            data.addProperty("subscriberCount", subscribedClients.size());
            data.addProperty("streaming", streaming.get());
            
            response.add("data", data);
            webSocketClient.sendMessage(response);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning(
                String.format("LogStreamManager: 发送流响应失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendErrorResponse(String clientId, String error) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "log_stream_response");
            response.addProperty("serverId", getServerId());
            response.addProperty("timestamp", System.currentTimeMillis());
            
            JsonObject data = new JsonObject();
            data.addProperty("message", error);
            data.addProperty("clientId", clientId);
            data.addProperty("context", "log_stream");
            
            response.add("data", data);
            webSocketClient.sendMessage(response);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning(
                String.format("LogStreamManager: 发送错误响应失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 发送流状态
     */
    private void sendStreamStatus(String clientId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "log_stream_response");
        message.addProperty("timestamp", System.currentTimeMillis());
        message.addProperty("serverId", getServerId());
        
        JsonObject data = new JsonObject();
        data.addProperty("action", "status");
        data.addProperty("streaming", streaming.get());
        data.addProperty("subscriberCount", subscribedClients.size());
        data.addProperty("clientId", clientId);
        data.addProperty("logTransmitterEnabled", logTransmitter != null && logTransmitter.isLogTransmissionEnabled());
        data.addProperty("queueSize", logTransmitter != null ? logTransmitter.getQueueSize() : 0);
        message.add("data", data);
        
        if (webSocketClient != null) {
            webSocketClient.sendMessage(message);
        }
    }
    
    /**
     * 获取当前流状态
     */
    public boolean isStreaming() {
        return streaming.get();
    }
    
    /**
     * 获取订阅客户端数量
     */
    public int getSubscriberCount() {
        return subscribedClients.size();
    }
    
    /**
     * 直接发送自定义日志消息
     * 用于插件特定事件的日志记录
     */
    public void sendCustomLog(String level, String message, String source) {
        if (logTransmitter != null) {
            logTransmitter.sendLog(level, message, source, null);
        }
    }
    
    /**
     * 发送玩家事件日志
     */
    public void sendPlayerEventLog(String eventType, String playerName, String message) {
        sendCustomLog("info", 
            String.format("[玩家事件] %s: %s - %s", eventType, playerName, message),
            "plugin:UltiTools");
    }
    
    /**
     * 发送插件操作日志
     */
    public void sendPluginActionLog(String action, String details) {
        sendCustomLog("info",
            String.format("[插件操作] %s: %s", action, details),
            "plugin:UltiTools");
    }
    
    // ========== Bukkit事件处理器 ==========
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        sendPlayerEventLog("JOIN", playerName, "玩家加入服务器");
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        sendPlayerEventLog("QUIT", playerName, "玩家离开服务器");
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        sendCustomLog("info", "服务器加载完成", "server");
    }
    
    /**
     * 获取服务器ID
     */
    private String getServerId() {
        try {
            return com.ultikits.ultitools.utils.CommonUtils.getUltiToolsUUID();
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * 关闭日志流管理器
     */
    public void shutdown() {
        subscribedClients.clear();
        streaming.set(false);
        
        // 关闭日志传输器
        if (logTransmitter != null) {
            logTransmitter.shutdown();
        }
        
        // 从Bukkit的Logger中移除处理器
        detachAllSystemLogHandlers();
        
        UltiTools.getInstance().getLogger().info("[UltiPanel] LogStreamManager shutdown");
    }

    /**
     * 摘掉 JVM root logger 上所有本框架的 {@link SystemLogHandler}。
     * <p>
     * 刻意按类型扫描并全部移除，而不是只移除 {@code this.systemLogHandler}：在本方法存在之前，
     * 每次重连成功都会往 root logger 上多挂一个，字段里只留得住最后那一个，先前泄漏的那些
     * 没有任何引用能指到、也就永远摘不掉。按类型扫一遍才能把历史欠账一并清干净，这也是
     * 「handler 数量恒为 1」这条验收唯一能成立的写法。见 issue #181。
     */
    private void detachAllSystemLogHandlers() {
        try {
            Logger rootLogger = Logger.getLogger("");
            for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof SystemLogHandler) {
                    rootLogger.removeHandler(handler);
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("Error removing log handler: " + e.getMessage());
        }
        this.systemLogHandler = null;
    }
}
