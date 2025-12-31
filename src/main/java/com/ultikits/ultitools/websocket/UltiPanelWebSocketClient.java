package com.ultikits.ultitools.websocket;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import lombok.Getter;
import okhttp3.*;
import okio.ByteString;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * UltiPanel WebSocket Client
 * <p>
 * 新的WebSocket客户端，基于RFC 6455 WebSocket标准
 * <p>
 * New WebSocket client based on RFC 6455 WebSocket standard
 */
@Getter
public class UltiPanelWebSocketClient extends WebSocketListener {
    private final String url;
    private final String serverId;
    private final String token;
    private final OkHttpClient client;
    private final ScheduledExecutorService heartbeatExecutor;
    
    private WebSocket webSocket;
    private boolean isConnected = false;
    private ScheduledFuture<?> heartbeatTask;
    
    private Consumer<JSONObject> messageHandler;
    private Runnable onConnectHandler;
    private Runnable onDisconnectHandler;
    private Consumer<String> onErrorHandler;

    /**
     * 构造函数
     *
     * @param url      WebSocket服务器URL
     * @param serverId 服务器ID
     * @param token    认证token
     */
    public UltiPanelWebSocketClient(String url, String serverId, String token) {
        this.url = url;
        this.serverId = serverId;
        this.token = token;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 连接到WebSocket服务器
     */
    public void connect() {
        if (isConnected) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket已经连接，请勿重复连接");
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        webSocket = client.newWebSocket(request, this);
    }

    /**
     * 断开WebSocket连接
     */
    public void disconnect() {
        // 停止心跳任务
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
        }
        isConnected = false;
        
        // 关闭心跳执行器
        heartbeatExecutor.shutdown();
    }

    /**
     * 发送消息到服务器
     *
     * @param message JSON消息对象
     */
    public void sendMessage(JSONObject message) {
        if (!isConnected || webSocket == null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket未连接，无法发送消息");
            return;
        }

        // 添加时间戳
        if (!message.containsKey("timestamp")) {
            message.put("timestamp", System.currentTimeMillis());
        }

        String messageStr = message.toJSONString();
        
        // 记录发送的消息日志
        UltiTools.getInstance().getLogger().log(Level.INFO, 
            String.format("[WebSocket发送] 类型: %s, 消息: %s", 
                message.getString("type"), messageStr));
        
        webSocket.send(messageStr);
    }

    /**
     * 发送Ping消息
     */
    public void sendPing() {
        JSONObject pingMessage = new JSONObject();
        pingMessage.put("type", "ping");
        pingMessage.put("timestamp", System.currentTimeMillis());
        sendMessage(pingMessage);
    }

    /**
     * 订阅服务器状态
     *
     * @param serverId 要订阅的服务器ID
     */
    public void subscribeToServer(String serverId) {
        JSONObject subscribeMessage = new JSONObject();
        subscribeMessage.put("type", "subscribe");
        subscribeMessage.put("serverId", serverId);
        subscribeMessage.put("timestamp", System.currentTimeMillis());
        sendMessage(subscribeMessage);
    }

    /**
     * 取消订阅服务器状态
     *
     * @param serverId 要取消订阅的服务器ID
     */
    public void unsubscribeFromServer(String serverId) {
        JSONObject unsubscribeMessage = new JSONObject();
        unsubscribeMessage.put("type", "unsubscribe");
        unsubscribeMessage.put("serverId", serverId);
        unsubscribeMessage.put("timestamp", System.currentTimeMillis());
        sendMessage(unsubscribeMessage);
    }

    /**
     * 设置消息处理器
     *
     * @param handler 消息处理器
     */
    public void setMessageHandler(Consumer<JSONObject> handler) {
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
     * 启动心跳任务
     */
    private void startHeartbeat() {
        // 按照文档建议，每60秒发送一次ping消息（而不是30秒）
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (isConnected) {
                sendPing();
                UltiTools.getInstance().getLogger().log(Level.FINE, "发送心跳ping消息");
            }
        }, 60, 60, TimeUnit.SECONDS); // 修改为60秒间隔
    }

    // WebSocketListener实现

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        super.onOpen(webSocket, response);
        isConnected = true;
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("成功连接到UltiPanel WebSocket服务器！"));
        
        if (onConnectHandler != null) {
            onConnectHandler.run();
        }

        // 发送初始ping消息
        sendPing();
        
        // 启动心跳任务，每30秒发送一次ping
        startHeartbeat();
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        super.onMessage(webSocket, text);
        
        try {
            JSONObject message = JSONObject.parseObject(text);
            
            // 记录接收的消息日志
            String messageType = message.getString("type");
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[WebSocket接收] 类型: %s, 消息: %s", 
                    messageType != null ? messageType : "未知", text));
            
            handleMessage(message);
            
            if (messageHandler != null) {
                messageHandler.accept(message);
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket消息解析失败: " + e.getMessage() + ", 原始消息: " + text);
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        super.onMessage(webSocket, bytes);
        onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        super.onClosing(webSocket, code, reason);
        isConnected = false;
        UltiTools.getInstance().getLogger().log(Level.INFO, "WebSocket连接正在关闭: " + reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        super.onClosed(webSocket, code, reason);
        isConnected = false;
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("已与UltiPanel WebSocket服务器断开连接！"));
        
        if (onDisconnectHandler != null) {
            onDisconnectHandler.run();
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        super.onFailure(webSocket, t, response);
        isConnected = false;
        String errorMessage = "WebSocket连接失败: " + t.getMessage();
        UltiTools.getInstance().getLogger().log(Level.WARNING, UltiTools.getInstance().i18n("无法连接到UltiPanel WebSocket服务器：") + t.getMessage());
        
        if (onErrorHandler != null) {
            onErrorHandler.accept(errorMessage);
        }
    }

    /**
     * 处理接收到的消息
     *
     * @param message 接收到的JSON消息
     */
    private void handleMessage(JSONObject message) {
        String type = message.getString("type");
        if (type == null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "收到无效的WebSocket消息：缺少type字段");
            return;
        }

        switch (type) {
            case "pong":
                // 处理pong响应
                UltiTools.getInstance().getLogger().log(Level.FINE, "收到pong响应");
                break;
            case "notification":
                // 处理通知消息
                JSONObject data = message.getJSONObject("data");
                if (data != null) {
                    String notificationMessage = data.getString("message");
                    UltiTools.getInstance().getLogger().log(Level.INFO, "收到通知: " + notificationMessage);
                }
                break;
            case "subscribe":
                // 处理订阅响应
                JSONObject subscribeData = message.getJSONObject("data");
                if (subscribeData != null && subscribeData.getBooleanValue("subscribed")) {
                    UltiTools.getInstance().getLogger().log(Level.INFO, "成功订阅服务器: " + subscribeData.getString("serverId"));
                }
                break;
            case "unsubscribe":
                // 处理取消订阅响应
                JSONObject unsubscribeData = message.getJSONObject("data");
                if (unsubscribeData != null && unsubscribeData.getBooleanValue("unsubscribed")) {
                    UltiTools.getInstance().getLogger().log(Level.INFO, "成功取消订阅服务器: " + unsubscribeData.getString("serverId"));
                }
                break;
            case "error":
                // 处理错误消息
                JSONObject errorData = message.getJSONObject("data");
                if (errorData != null) {
                    String errorMessage = errorData.getString("message");
                    UltiTools.getInstance().getLogger().log(Level.WARNING, "服务器错误: " + errorMessage);
                }
                break;
            default:
                UltiTools.getInstance().getLogger().log(Level.FINE, "收到未知类型的消息: " + type);
                break;
        }
    }
}
