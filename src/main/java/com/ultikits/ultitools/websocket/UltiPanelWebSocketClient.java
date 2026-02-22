package com.ultikits.ultitools.websocket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;

import lombok.Getter;

/**
 * UltiPanel WebSocket Client
 * <p>
 * 新的WebSocket客户端，基于Java-WebSocket库
 * <p>
 * New WebSocket client based on Java-WebSocket library
 */
@Getter
public class UltiPanelWebSocketClient extends WebSocketClient {
    private final String serverId;
    private final String token;
    private final ScheduledExecutorService heartbeatExecutor;
    
    private boolean isConnected = false;
    private ScheduledFuture<?> heartbeatTask;
    
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Consumer<JsonObject> messageHandler;
    private Runnable onConnectHandler;
    private Runnable onDisconnectHandler;
    private Consumer<String> onErrorHandler;
    private Runnable onReconnectExhaustedHandler;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY_MS = 5000;
    private int reconnectAttempts = 0;
    private boolean intentionalDisconnect = false;

    /**
     * 构造函数
     *
     * @param url      WebSocket服务器URL
     * @param serverId 服务器ID
     * @param token    认证token
     * @throws URISyntaxException 如果URL格式不正确
     */
    public UltiPanelWebSocketClient(String url, String serverId, String token) throws URISyntaxException {
        super(new URI(url), getHeaders(token));
        this.serverId = serverId;
        this.token = token;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    private static Map<String, String> getHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    /**
     * 连接到WebSocket服务器
     */
    @Override
    public void connect() {
        if (isConnected) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket已经连接，请勿重复连接");
            return;
        }
        super.connect();
    }

    /**
     * 断开WebSocket连接
     */
    public void disconnect() {
        intentionalDisconnect = true;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        close(1000, "Client disconnect");
        isConnected = false;
        heartbeatExecutor.shutdown();
    }

    /**
     * 发送消息到服务器
     *
     * @param message JSON消息对象
     */
    public void sendMessage(JsonObject message) {
        if (!isOpen()) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket未连接，无法发送消息");
            return;
        }

        // 添加时间戳
        if (!message.has("timestamp")) {
            message.addProperty("timestamp", System.currentTimeMillis());
        }

        String messageStr = gson.toJson(message);
        
        // 记录发送的消息日志
        String msgType = message.has("type") && !message.get("type").isJsonNull() 
            ? message.get("type").getAsString() : "未知";
        UltiTools.getInstance().getLogger().log(Level.FINE, 
            String.format("[WebSocket发送] 类型: %s", msgType));

        
        send(messageStr);
    }

    /**
     * 发送Ping消息
     */
    public void sendPing() {
        JsonObject pingMessage = new JsonObject();
        pingMessage.addProperty("type", "ping");
        pingMessage.addProperty("timestamp", System.currentTimeMillis());
        sendMessage(pingMessage);
    }

    /**
     * 订阅服务器状态
     *
     * @param serverId 要订阅的服务器ID
     */
    public void subscribeToServer(String serverId) {
        JsonObject subscribeMessage = new JsonObject();
        subscribeMessage.addProperty("type", "subscribe");
        subscribeMessage.addProperty("serverId", serverId);
        subscribeMessage.addProperty("timestamp", System.currentTimeMillis());
        sendMessage(subscribeMessage);
    }

    /**
     * 取消订阅服务器状态
     *
     * @param serverId 要取消订阅的服务器ID
     */
    public void unsubscribeFromServer(String serverId) {
        JsonObject unsubscribeMessage = new JsonObject();
        unsubscribeMessage.addProperty("type", "unsubscribe");
        unsubscribeMessage.addProperty("serverId", serverId);
        unsubscribeMessage.addProperty("timestamp", System.currentTimeMillis());
        sendMessage(unsubscribeMessage);
    }

    /**
     * 设置消息处理器
     *
     * @param handler 消息处理器
     */
    public void setMessageHandler(Consumer<JsonObject> handler) {
        this.messageHandler = handler;
    }

    /**
     * 设置连接成功处理器
     *
     * @param handler 连接成功处理器
     */
    public void setOnConnectHandler(Runnable handler) {
        this.onConnectHandler = handler;
    }

    /**
     * 设置断开连接处理器
     *
     * @param handler 断开连接处理器
     */
    public void setOnDisconnectHandler(Runnable handler) {
        this.onDisconnectHandler = handler;
    }

    /**
     * 设置错误处理器
     *
     * @param handler 错误处理器
     */
    public void setOnErrorHandler(Consumer<String> handler) {
        this.onErrorHandler = handler;
    }

    /**
     * 设置重连耗尽处理器（当所有重连尝试都失败后调用）
     *
     * @param handler 重连耗尽处理器
     */
    public void setOnReconnectExhaustedHandler(Runnable handler) {
        this.onReconnectExhaustedHandler = handler;
    }

    /**
     * 启动心跳任务
     */
    private void startHeartbeat() {
        // 按照文档建议，每60秒发送一次ping消息（而不是30秒）
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (isOpen()) {
                sendPing();
                UltiTools.getInstance().getLogger().log(Level.FINE, "发送心跳ping消息");
            }
        }, 60, 60, TimeUnit.SECONDS); // 修改为60秒间隔
    }

    // WebSocketClient实现

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        isConnected = true;
        reconnectAttempts = 0;
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("成功连接到UltiPanel WebSocket服务器！"));
        
        if (onConnectHandler != null) {
            onConnectHandler.run();
        }

        // 发送初始ping消息
        sendPing();
        
        // 启动心跳任务
        startHeartbeat();
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject jsonMessage = JsonParser.parseString(message).getAsJsonObject();

            String messageType = jsonMessage.has("type") && !jsonMessage.get("type").isJsonNull()
                ? jsonMessage.get("type").getAsString() : null;
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket接收] 类型: %s", messageType != null ? messageType : "未知"));

            if (messageHandler != null) {
                messageHandler.accept(jsonMessage);
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket消息解析失败: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
        UltiTools.getInstance().getLogger().log(Level.INFO,
            UltiTools.getInstance().i18n("已与UltiPanel WebSocket服务器断开连接！") + " Reason: " + reason);

        if (onDisconnectHandler != null) {
            onDisconnectHandler.run();
        }

        if (!intentionalDisconnect && !heartbeatExecutor.isShutdown()) {
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                long delay = INITIAL_RECONNECT_DELAY_MS * reconnectAttempts;
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    String.format("Attempting WebSocket reconnection %d/%d in %ds...",
                        reconnectAttempts, MAX_RECONNECT_ATTEMPTS, delay / 1000));
                heartbeatExecutor.schedule(() -> {
                    try {
                        reconnect();
                    } catch (Exception e) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket reconnection failed: " + e.getMessage());
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else if (onReconnectExhaustedHandler != null) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "All WebSocket reconnection attempts exhausted, attempting token refresh and re-initialization...");
                heartbeatExecutor.schedule(() -> {
                    try {
                        onReconnectExhaustedHandler.run();
                    } catch (Exception e) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "WebSocket re-initialization after reconnect exhaustion failed: " + e.getMessage());
                    }
                }, INITIAL_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        isConnected = false;
        String errorMessage = "WebSocket连接失败: " + ex.getMessage();
        UltiTools.getInstance().getLogger().log(Level.WARNING, UltiTools.getInstance().i18n("无法连接到UltiPanel WebSocket服务器：") + ex.getMessage());
        
        if (onErrorHandler != null) {
            onErrorHandler.accept(errorMessage);
        }
    }

}
