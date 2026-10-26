package com.ultikits.ultitools.websocket;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.FrameworkText;

import lombok.Getter;

/**
 * The UltiPanel protocol v1 WebSocket client, kept only so 6.3.0 code still links (plan 22-25).
 * <p>
 * <b>As of 6.3.1 it never opens a socket.</b> Protocol 2.0.0 has no v1 gateway, so
 * {@link #connect()}, both {@code connectBlocking} forms, {@link #reconnect()} and
 * {@link #reconnectBlocking()} return without connecting (the blocking forms return
 * {@code false}), and the first such call in a run writes one INFO line that says so. The
 * framework itself never constructs this class; its own UltiCloud session speaks protocol 2.0.0.
 * The kept v1 senders in the managers still send over an instance a caller hands to their
 * deprecated entry points, and {@link #sendMessage(JsonObject)} then reports the closed socket.
 *
 * @deprecated There is no replacement: the framework's own UltiCloud session (protocol 2.0.0)
 *     carries every panel request, and modules answer panel requests through
 *     {@link PanelResponderRegistry}. Removed in 6.4.0.
 * @removeIn 6.4.0
 */
@Getter
@Deprecated(since = "6.3.1", forRemoval = true)
public class UltiPanelWebSocketClient extends WebSocketClient {
    private final String serverId;
    private final String token;
    private final ScheduledExecutorService heartbeatExecutor;
    
    // volatile (#486): written by the socket's own thread in onOpen/onClose/onError and read by
    // the log senders right after a send to tell whether it went out. Read through isConnected().
    private volatile boolean isConnected = false;
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

    /** Where the one-per-run "no v1 gateway" line goes; a class logger, so it needs no plugin instance. */
    private static final Logger NOTICE_LOGGER = Logger.getLogger(UltiPanelWebSocketClient.class.getName());
    /** Whether this run has already said that a v1 client does not connect. */
    private static final AtomicBoolean CONNECT_NOTICE_LOGGED = new AtomicBoolean();
    /** Test-only switch that lets the kept v1 log-stream tests reach a local socket server. */
    private static volatile boolean connectPermittedForTesting;

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

    /**
     * Whether the panel connection is up: {@code onOpen} has run and neither {@code onClose} nor
     * {@code onError} has since, and the socket itself is still open.
     * <p>
     * The second half matters while a connection closes (as of 6.3.0, #486): the socket leaves the
     * open state -- and {@link #sendMessage(JsonObject)} starts refusing to send -- before
     * {@code onClose} runs, which can take a long time when the closing handshake waits on a dead
     * peer. Reporting "connected" in that window made a send that was quietly refused look
     * delivered, and the log records in it were lost.
     *
     * @return whether a message sent now can go out
     */
    public boolean isConnected() {
        return isConnected && isOpen();
    }

    private static Map<String, String> getHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    /**
     * Opens no socket as of 6.3.1: protocol 2.0.0 has no v1 gateway. The first call of any connect
     * form in a run writes one INFO line naming the removal.
     */
    @Override
    public void connect() {
        if (refuseConnect()) {
            return;
        }
        if (isConnected) {
            PanelConnectionLog.log(Level.WARNING, FrameworkText.text("WebSocket已经连接，请勿重复连接"));
            return;
        }
        super.connect();
    }

    /**
     * Opens no socket as of 6.3.1 (see {@link #connect()}).
     *
     * @return {@code false}: no connection was made
     * @throws InterruptedException never as of 6.3.1; kept from the overridden signature
     */
    @Override
    public boolean connectBlocking() throws InterruptedException {
        if (refuseConnect()) {
            return false;
        }
        return super.connectBlocking();
    }

    /**
     * Opens no socket as of 6.3.1 (see {@link #connect()}).
     *
     * @param timeout ignored, nothing is awaited
     * @param timeUnit ignored, nothing is awaited
     * @return {@code false}: no connection was made
     * @throws InterruptedException never as of 6.3.1; kept from the overridden signature
     */
    @Override
    public boolean connectBlocking(long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (refuseConnect()) {
            return false;
        }
        return super.connectBlocking(timeout, timeUnit);
    }

    /**
     * Opens no socket as of 6.3.1 (see {@link #connect()}).
     */
    @Override
    public void reconnect() {
        if (refuseConnect()) {
            return;
        }
        super.reconnect();
    }

    /**
     * Opens no socket as of 6.3.1 (see {@link #connect()}).
     *
     * @return {@code false}: no connection was made
     * @throws InterruptedException never as of 6.3.1; kept from the overridden signature
     */
    @Override
    public boolean reconnectBlocking() throws InterruptedException {
        if (refuseConnect()) {
            return false;
        }
        return super.reconnectBlocking();
    }

    /**
     * Whether a connect call must open nothing: always, outside the kept v1 log-stream tests.
     * Writes the one-per-run INFO line on the first refusal.
     */
    private static boolean refuseConnect() {
        if (connectPermittedForTesting) {
            return false;
        }
        if (CONNECT_NOTICE_LOGGED.compareAndSet(false, true)) {
            NOTICE_LOGGER.info("UltiCloud: UltiPanelWebSocketClient speaks protocol v1, which UltiCloud no "
                    + "longer serves, so it opens no connection; it is deprecated and removed in 6.4.0. The "
                    + "framework's own UltiCloud session (protocol 2.0.0) carries the panel.");
        }
        return true;
    }

    /** Test-only: lets a kept v1 log-stream test connect to its own local socket server. */
    static void permitConnectForTesting(boolean permit) {
        connectPermittedForTesting = permit;
    }

    /** Test-only: forgets that this run already logged the "opens no connection" line. */
    static void resetConnectNoticeForTesting() {
        CONNECT_NOTICE_LOGGED.set(false);
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
            // A panel-connection line (#584). Two callers hold a lock across this call:
            // ServerMonitorManager's drains hold logDrainLock, and UltiPanelLogTransmitter's
            // sendBatch/flushLogs hold batchModeLock. A line that reached the log stream from here
            // would take batchModeLock while logDrainLock is held -- the reverse of the order the
            // size-threshold drain takes them in, an AB-BA deadlock. #467 wrote it to System.err on
            // the premise that standard error bypasses java.util.logging; on Paper it does not:
            // SysoutCatcher re-logs a plugin's System.err line through that plugin's logger, so it
            // reached SystemLogHandler unmarked. PanelConnectionLog marks the record, and
            // SystemLogHandler never hands a marked record to the transmitter, so no stream lock is
            // taken; the console still shows the line. Regression test: SysErrRelogDeadlockTest.
            // Plain English, not the language catalogue, as before.
            PanelConnectionLog.log(Level.WARNING, "[UltiPanel] WebSocket is not connected; cannot send the message");
            return;
        }

        // Add a timestamp
        if (!message.has("timestamp")) {
            message.addProperty("timestamp", System.currentTimeMillis());
        }

        String messageStr = gson.toJson(message);

        // Gate-2 finding (round 7): this used to log the outgoing message's type at Level.FINE
        // via the shared plugin logger. sendMessage() is the SOLE chokepoint every outbound panel
        // message goes through -- including log_stream/log_batch/batch_update frames delivered by
        // SystemLogHandler. Before this plan, "debug" never actually lowered the handler's own JUL
        // floor (#433's defect), so this diagnostic was unreachable by SystemLogHandler on any
        // released version. CR-02/round-4's fix (this same PR) makes "debug" genuinely reachable,
        // and every message this method sends -- not just log-related ones -- would then generate
        // ANOTHER Level.FINE record describing that very send, which (if still delivered) reaches
        // this same method again: a 1:1 self-sustaining amplification loop for every single panel
        // message once "debug" is enabled, not merely the batchConfig.size:1 special case the
        // earlier (now-removed) UltiPanelLogTransmitter#sendBatch() diagnostic produced. Removed
        // for the same reason that one was: the information value of a bare "sent message of type
        // X" line does not justify carrying this hazard.
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
            PanelConnectionLog.log(Level.WARNING, FrameworkText.format(
                "WebSocket 已 %d 秒未收到 pong（阈值 %d 秒），判定为静默失效，主动重连",
                silentFor / 1000, PONG_TIMEOUT_MS / 1000));
            // Do not set intentionalDisconnect - this is not an "intentional disconnect";
            // reconnection must continue
            close(4000, "Heartbeat timeout: no pong received");
            return;
        }

        sendPing();
        PanelConnectionLog.log(Level.FINE, latencyMs >= 0
            ? FrameworkText.format("发送心跳ping消息（上次往返 %dms）", latencyMs)
            : FrameworkText.text("发送心跳ping消息"));
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
        PanelConnectionLog.log(Level.INFO, UltiTools.getInstance().i18n("成功连接到UltiPanel WebSocket服务器！"));
        
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
            PanelConnectionLog.log(Level.FINE,
                FrameworkText.format("[WebSocket接收] 类型: %s", messageType != null ? messageType : FrameworkText.text("未知")));

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
            PanelConnectionLog.log(Level.WARNING, FrameworkText.format("WebSocket消息解析失败: %s", e.getMessage()));
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
        PanelConnectionLog.log(Level.INFO,
            UltiTools.getInstance().i18n("已与UltiPanel WebSocket服务器断开连接！") + " Reason: " + reason);

        if (onDisconnectHandler != null) {
            onDisconnectHandler.run();
        }

        if (!intentionalDisconnect && !heartbeatExecutor.isShutdown()) {
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                long delay = INITIAL_RECONNECT_DELAY_MS * reconnectAttempts;
                PanelConnectionLog.log(Level.INFO,
                    String.format("Attempting WebSocket reconnection %d/%d in %ds...",
                        reconnectAttempts, MAX_RECONNECT_ATTEMPTS, delay / 1000));
                heartbeatExecutor.schedule(() -> {
                    try {
                        reconnect();
                    } catch (Exception e) {
                        PanelConnectionLog.log(Level.WARNING, "WebSocket reconnection failed: " + e.getMessage());
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else if (onReconnectExhaustedHandler != null) {
                PanelConnectionLog.log(Level.WARNING,
                    "All WebSocket reconnection attempts exhausted, attempting token refresh and re-initialization...");
                heartbeatExecutor.schedule(() -> {
                    try {
                        onReconnectExhaustedHandler.run();
                    } catch (Exception e) {
                        PanelConnectionLog.log(Level.WARNING,
                            "WebSocket re-initialization after reconnect exhaustion failed: " + e.getMessage());
                    }
                }, INITIAL_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        isConnected = false;
        String errorMessage = FrameworkText.format("WebSocket连接失败: %s", ex.getMessage());
        PanelConnectionLog.log(Level.WARNING, UltiTools.getInstance().i18n("无法连接到UltiPanel WebSocket服务器：") + ex.getMessage());
        
        if (onErrorHandler != null) {
            onErrorHandler.accept(errorMessage);
        }
    }

}
