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

    /** Heartbeat interval, in seconds. */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 60;

    /**
     * Threshold for declaring a connection "silently dead": no pong received across two
     * heartbeat intervals.
     * <p>
     * Two intervals rather than one, to tolerate a single dropped packet or one scheduling
     * jitter - the cost of a false positive is kicking a good connection into reconnecting,
     * while a false negative only delays detection by one interval.
     */
    private static final long PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_SECONDS * 2 * 1000;

    /** Timestamp of the most recent ping sent. */
    private volatile long lastPingTime = 0;
    /** Timestamp of the most recent pong received. 0 means none has ever been received. */
    private volatile long lastPongTime = 0;
    /** Most recently measured round-trip latency, in milliseconds. */
    private volatile long latencyMs = -1;

    /**
     * Clock. In production this is simply {@code System::currentTimeMillis}; tests can
     * substitute a fake clock.
     * <p>
     * The only reason this exists is so the "no pong received past the threshold" check can be
     * covered by a unit test without {@code Thread.sleep} - actually waiting out two heartbeat
     * intervals is 120 seconds, and nobody keeps a test like that around.
     */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    /**
     * Constructor.
     *
     * @param url      the WebSocket server URL
     * @param serverId the server ID
     * @param token    the auth token
     * @throws URISyntaxException if the URL is malformed
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
     * Connects to the WebSocket server.
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
     * Disconnects the WebSocket connection.
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
     * Sends a message to the server.
     *
     * @param message the JSON message object
     */
    public void sendMessage(JsonObject message) {
        if (!isOpen()) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket未连接，无法发送消息");
            return;
        }

        // Add a timestamp
        if (!message.has("timestamp")) {
            message.addProperty("timestamp", System.currentTimeMillis());
        }

        String messageStr = gson.toJson(message);

        // Log the outgoing message
        String msgType = message.has("type") && !message.get("type").isJsonNull()
            ? message.get("type").getAsString() : "未知";
        UltiTools.getInstance().getLogger().log(Level.FINE, 
            String.format("[WebSocket发送] 类型: %s", msgType));

        
        send(messageStr);
    }

    /**
     * Sends a ping message.
     */
    public void sendPing() {
        // Record the send time, so recordPong() can compute the round-trip latency
        lastPingTime = clock.getAsLong();
        JsonObject pingMessage = new JsonObject();
        pingMessage.addProperty("type", "ping");
        pingMessage.addProperty("timestamp", lastPingTime);
        sendMessage(pingMessage);
    }

    /**
     * Subscribes to a server's status.
     *
     * @param serverId the server ID to subscribe to
     */
    public void subscribeToServer(String serverId) {
        JsonObject subscribeMessage = new JsonObject();
        subscribeMessage.addProperty("type", "subscribe");
        subscribeMessage.addProperty("serverId", serverId);
        subscribeMessage.addProperty("timestamp", System.currentTimeMillis());
        sendMessage(subscribeMessage);
    }

    /**
     * Unsubscribes from a server's status.
     *
     * @param serverId the server ID to unsubscribe from
     */
    public void unsubscribeFromServer(String serverId) {
        JsonObject unsubscribeMessage = new JsonObject();
        unsubscribeMessage.addProperty("type", "unsubscribe");
        unsubscribeMessage.addProperty("serverId", serverId);
        unsubscribeMessage.addProperty("timestamp", System.currentTimeMillis());
        sendMessage(unsubscribeMessage);
    }

    /**
     * Sets the message handler.
     *
     * @param handler the message handler
     */
    public void setMessageHandler(Consumer<JsonObject> handler) {
        this.messageHandler = handler;
    }

    /**
     * Sets the on-connect handler.
     *
     * @param handler the on-connect handler
     */
    public void setOnConnectHandler(Runnable handler) {
        this.onConnectHandler = handler;
    }

    /**
     * Sets the on-disconnect handler.
     *
     * @param handler the on-disconnect handler
     */
    public void setOnDisconnectHandler(Runnable handler) {
        this.onDisconnectHandler = handler;
    }

    /**
     * Sets the error handler.
     *
     * @param handler the error handler
     */
    public void setOnErrorHandler(Consumer<String> handler) {
        this.onErrorHandler = handler;
    }

    /**
     * Sets the reconnect-exhausted handler (invoked once every reconnection attempt has
     * failed).
     *
     * @param handler the reconnect-exhausted handler
     */
    public void setOnReconnectExhaustedHandler(Runnable handler) {
        this.onReconnectExhaustedHandler = handler;
    }

    /**
     * Starts the heartbeat task.
     */
    private void startHeartbeat() {
        heartbeatTask = heartbeatExecutor.scheduleWithFixedDelay(this::heartbeatTick,
            HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * One heartbeat: check liveness first, then send a ping.
     * <p>
     * "Check liveness first" is the reason this method exists. Before it, the only signal of
     * connection health was whether the socket had closed - and a TCP connection can go
     * silently dead without ever producing {@code onClose} (an intermediate device timing out,
     * the peer process hanging, a NAT table entry expiring). In that state pings still go out,
     * {@code onClose} never fires, and reconnection logic never starts - the connection looks
     * fine but is actually dead.
     * <p>
     * When a silent failure is declared, this method **routes through {@code close()} rather
     * than starting a second reconnection path of its own**: closing triggers {@code onClose},
     * reusing the existing reconnect state machine (including the global budget #181 added).
     * Starting a second path would become a second place deciding whether to reconnect - exactly
     * what caused #181.
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
            // Do not set intentionalDisconnect - this is not an "intentional disconnect";
            // reconnection must continue
            close(4000, "Heartbeat timeout: no pong received");
            return;
        }

        sendPing();
        UltiTools.getInstance().getLogger().log(Level.FINE, String.format(
            "发送心跳ping消息%s", latencyMs >= 0 ? "（上次往返 " + latencyMs + "ms）" : ""));
    }

    /**
     * Called when a pong is received: records the time and computes the round-trip latency.
     * <p>
     * Called by {@link #onMessage(String)} before dispatching to messageHandler - a pong is a
     * link-layer fact and should not depend on whether an upper-layer handler happens to be
     * wired up.
     */
    private void recordPong() {
        lastPongTime = clock.getAsLong();
        if (lastPingTime > 0) {
            latencyMs = lastPongTime - lastPingTime;
        }
    }

    /**
     * Determines whether the connection is still alive, based on pong responses.
     *
     * <p><b>Returns true when no pong has ever been received.</b> This is deliberate: otherwise
     * a newly established connection would be declared dead within its first heartbeat
     * interval. The cost is that this mechanism cannot detect "the peer never answers pong at
     * all" - but that is the safe direction: if the panel simply never implemented pong, a good
     * connection should not be repeatedly kicked. What it can detect is "used to answer, and
     * stopped" - that is, a silent failure.
     *
     * @param timeoutMs the liveness threshold, in milliseconds
     * @return true if the most recent pong is within the threshold, or none has ever been
     *         received
     */
    public boolean isAlive(long timeoutMs) {
        if (lastPongTime == 0) {
            return true;
        }
        return (clock.getAsLong() - lastPongTime) < timeoutMs;
    }

    /**
     * Most recently measured round-trip latency, in milliseconds; -1 if not yet measured.
     *
     * @return the latency in milliseconds, or -1
     */
    public long getLatencyMs() {
        return latencyMs;
    }

    /**
     * Timestamp of the most recent pong received; 0 if none has ever been received.
     *
     * @return the timestamp, in milliseconds
     */
    public long getLastPongTime() {
        return lastPongTime;
    }

    /** Test-only clock substitution. */
    @org.jetbrains.annotations.ApiStatus.Internal
    void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    // WebSocketClient implementation

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        isConnected = true;
        reconnectAttempts = 0;
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("成功连接到UltiPanel WebSocket服务器！"));
        
        if (onConnectHandler != null) {
            onConnectHandler.run();
        }

        // Send the initial ping message
        sendPing();

        // Start the heartbeat task
        startHeartbeat();
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject jsonMessage = JsonParser.parseString(message).getAsJsonObject();

            // isJsonPrimitive rather than !isJsonNull: the latter does not guard against type
            // being an object or array, which would make getAsString() throw
            // UnsupportedOperationException right here - and this line exists only to emit a
            // FINE log, yet the exception would keep the message from ever reaching
            // messageHandler below and get it logged as a "message parse failure" instead. The
            // diagnostic would not match the real cause, and a malformed message would be
            // swallowed before it ever reached the real dispatch logic. See issue #234.
            String messageType = jsonMessage.has("type") && jsonMessage.get("type").isJsonPrimitive()
                ? jsonMessage.get("type").getAsString() : null;
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket接收] 类型: %s", messageType != null ? messageType : "未知"));

            // The pong is recorded before dispatch. It is a link-layer fact and should not
            // depend on whether an upper-layer handler happens to be wired up - liveness
            // detection is this class's own responsibility.
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
