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

    /** 心跳间隔（秒）。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60;

    /**
     * 判定「静默失效」的阈值：两个心跳周期没收到 pong。
     * <p>
     * 取两个周期而不是一个，是为了容忍单次丢包或一次调度抖动 —— 误判的代价是把一条好连接
     * 踢掉重连，而漏判只是晚一个周期发现。
     */
    private static final long PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_SECONDS * 2 * 1000;

    /** 最近一次发出 ping 的时间。 */
    private volatile long lastPingTime = 0;
    /** 最近一次收到 pong 的时间。0 表示从未收到过。 */
    private volatile long lastPongTime = 0;
    /** 最近一次测得的往返延迟，毫秒。 */
    private volatile long latencyMs = -1;

    /**
     * 时钟。生产环境就是 {@code System::currentTimeMillis}，测试可以替换成假时钟。
     * <p>
     * 存在的唯一理由是让「超过阈值未收到 pong」这条判定可以被单元测试覆盖而不用
     * {@code Thread.sleep} —— 真等两个心跳周期是 120 秒，那种测试没人会留着。
     */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

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
        // 记录发出时间，供 recordPong() 算往返延迟
        lastPingTime = clock.getAsLong();
        JsonObject pingMessage = new JsonObject();
        pingMessage.addProperty("type", "ping");
        pingMessage.addProperty("timestamp", lastPingTime);
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
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(this::heartbeatTick,
            HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 一次心跳：先判活，再发 ping。
     * <p>
     * 「先判活」是本方法存在的理由。在此之前，连接健康的唯一判据是 socket 有没有断
     * —— 而一条 TCP 连接完全可以在不产生 {@code onClose} 的情况下静默失效（中间设备
     * 超时、对端进程挂起、NAT 表项过期）。那种状态下 ping 发得出去、{@code onClose}
     * 不触发、重连逻辑不启动，连接看着是好的，实际已经死了。
     * <p>
     * 判定为静默失效时**走 {@code close()} 而不是自己另起一套重连**：close 会触发
     * {@code onClose}，复用既有的重连状态机（含 #181 加的全局预算）。另起一套就会变成
     * 第二个「决定要不要重连」的地方，那正是 #181 的成因。
     */
    void heartbeatTick() {
        if (!isOpen()) {
            return;
        }

        if (!isAlive(PONG_TIMEOUT_MS)) {
            long silentFor = clock.getAsLong() - lastPongTime;
            UltiTools.getInstance().getLogger().log(Level.WARNING, String.format(
                "WebSocket 已 %d 秒未收到 pong（阈值 %d 秒），判定为静默失效，主动重连",
                silentFor / 1000, PONG_TIMEOUT_MS / 1000));
            // 不设 intentionalDisconnect —— 这不是「有意断开」，重连必须继续
            close(4000, "Heartbeat timeout: no pong received");
            return;
        }

        sendPing();
        UltiTools.getInstance().getLogger().log(Level.FINE, String.format(
            "发送心跳ping消息%s", latencyMs >= 0 ? "（上次往返 " + latencyMs + "ms）" : ""));
    }

    /**
     * 收到 pong 时调用：记录时间并算出往返延迟。
     * <p>
     * 由 {@link #onMessage(String)} 在分发给 messageHandler 之前调用 —— pong 是链路层面的
     * 事实，不该依赖上层处理器是否接线。
     */
    private void recordPong() {
        lastPongTime = clock.getAsLong();
        if (lastPingTime > 0) {
            latencyMs = lastPongTime - lastPingTime;
        }
    }

    /**
     * 根据 pong 应答判断连接是否还活着。
     *
     * <p><b>从未收到过 pong 时返回 true。</b> 这一条是刻意的：否则新建立的连接会在第一个
     * 心跳周期就被判死。代价是这套机制发现不了「对端从来就不应答 pong」的情况 —— 但那是
     * 安全的方向：面板若压根没实现 pong，我们不该把好连接反复踢掉。它能发现的是
     * 「曾经在应答、后来不答了」，也就是静默失效。
     *
     * @param timeoutMs 判定阈值，毫秒
     * @return 最近一次 pong 在阈值之内，或从未收到过 pong
     */
    public boolean isAlive(long timeoutMs) {
        if (lastPongTime == 0) {
            return true;
        }
        return (clock.getAsLong() - lastPongTime) < timeoutMs;
    }

    /**
     * 最近一次测得的往返延迟，毫秒；尚未测到时为 -1。
     *
     * @return 延迟毫秒数，或 -1
     */
    public long getLatencyMs() {
        return latencyMs;
    }

    /**
     * 最近一次收到 pong 的时间戳；从未收到过时为 0。
     *
     * @return 时间戳，毫秒
     */
    public long getLastPongTime() {
        return lastPongTime;
    }

    /** 仅供测试替换时钟。 */
    @org.jetbrains.annotations.ApiStatus.Internal
    void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
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

            // isJsonPrimitive 而不是 !isJsonNull：后者挡不住 type 是对象或数组的情况，
            // 那会让 getAsString() 在这里抛 UnsupportedOperationException——而这一行
            // 只是为了打一条 FINE 日志，却会因此让消息根本到不了下面的 messageHandler，
            // 并被记成「消息解析失败」。诊断信息与真实原因不符，且畸形消息在到达真正的
            // 分发逻辑之前就被吞掉了。见 issue #234。
            String messageType = jsonMessage.has("type") && jsonMessage.get("type").isJsonPrimitive()
                ? jsonMessage.get("type").getAsString() : null;
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket接收] 类型: %s", messageType != null ? messageType : "未知"));

            // pong 在分发之前就记下来。它是链路层面的事实，不该取决于上层处理器
            // 有没有接线——存活判定是本类自己的职责。
            if ("pong".equals(messageType)) {
                recordPong();
            }

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
