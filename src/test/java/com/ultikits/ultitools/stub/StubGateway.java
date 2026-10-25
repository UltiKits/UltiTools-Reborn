package com.ultikits.ultitools.stub;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The stub's {@code /v1/agent} gateway (plan 22-19), a Java-WebSocket server on an internal
 * loopback port that {@link StubFrontDoor} relays accepted upgrades to.
 *
 * <p>It follows the frozen contract, not a reading of the framework:
 * <ul>
 *   <li><b>hello first</b> (section 9 item 10): the first frame must be a schema-valid
 *       {@code hello}; any other first frame, or an invalid hello, closes with 4400; no hello
 *       within {@code timing.helloTimeoutMs} closes with 4408.</li>
 *   <li><b>welcome</b> is built from the vendored {@code welcome/valid/default-limits.json}: a new
 *       id, {@code replyTo} = the hello's id, the credential's {@code serverId}, {@code accepted} =
 *       the hello's capabilities the contract knows, and the current {@code serverTime}.</li>
 *   <li><b>heartbeat</b> (item 1): the text frame {@code ping} is answered {@code pong}, outside the
 *       envelope; no protocol-level pings are sent.</li>
 *   <li><b>reliable events</b> (items 7 and 11): the ids of {@code player.join} and
 *       {@code player.leave} are acknowledged in one {@code ack} within {@value #ACK_DELAY_MS} ms or
 *       as soon as {@code ack.batchMax} are pending, well inside the contract's {@code ack.flushMs}
 *       upper bound; a repeated id is acknowledged again and logged as a duplicate.</li>
 *   <li><b>validation</b> (item 12): every inbound frame is checked against its vendored schema;
 *       a non-conforming frame is logged {@code WS-INVALID} with the schema's first issue and answered
 *       {@code error invalid_message} (with {@code replyTo} when its id is readable); an unregistered
 *       type is answered {@code unsupported_type}; more than {@code invalidFrames.max} such frames
 *       within {@code invalidFrames.windowMs} close with 4429. A frame of a type whose capability
 *       was not accepted is answered {@code capability_not_accepted} (item 8).</li>
 *   <li><b>one connection per server</b>: a new connection for a server closes the older one with
 *       4000, as section 7 says the gateway keeps the newest.</li>
 * </ul>
 * Every outbound frame is checked against its schema too and logged {@code WS-SELF-INVALID} if it
 * fails (a stub defect). Every frame in both directions is logged and recorded, masked.
 *
 * @since 6.3.1
 */
public final class StubGateway {

    /** How long the gateway collects reliable-event ids before it acknowledges them. */
    static final long ACK_DELAY_MS = 100;

    private static final String HELLO = "hello";
    /** The heartbeat text frames of section 9 ({@code heartbeat.ping}, {@code heartbeat.pong}). */
    private static final String PING = "ping";
    private static final String PONG = "pong";
    private static final String WELCOME_FIXTURE = "fixtures/agent/welcome/valid/default-limits.json";
    private static final String ACK_FIXTURE = "fixtures/agent/ack/valid/two-ids.json";
    private static final String ERROR_FIXTURE = "fixtures/agent/error/valid/reply-invalid-message.json";
    private static final int ERROR_MESSAGE_MAX = 500;

    private final UltiCloudStub.Contract contract;
    private final StubAccessLog log;
    private final UltiCloudStub.Credentials credentials;
    private final long helloTimeoutMs;
    private final int ackBatchMax;
    private final int invalidMax;
    private final long invalidWindowMs;
    private final int maxFrameBytes;
    private final Server server;
    private final ScheduledExecutorService timers;
    private final AtomicInteger connectionIds = new AtomicInteger();
    private final Map<WebSocket, Connection> connections = new ConcurrentHashMap<>();
    private final List<Frame> frames = new ArrayList<>();
    private final Set<String> seenEventIds = Collections.synchronizedSet(new HashSet<String>());
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile Exception startFailure;
    private volatile boolean acking = true;

    StubGateway(UltiCloudStub.Contract contract, StubAccessLog log, UltiCloudStub.Credentials credentials,
                long helloTimeoutMs) {
        this.contract = contract;
        this.log = log;
        this.credentials = credentials;
        this.helloTimeoutMs = helloTimeoutMs;
        this.ackBatchMax = (int) contract.constant("ack.batchMax");
        this.invalidMax = (int) contract.constant("invalidFrames.max");
        this.invalidWindowMs = contract.constant("invalidFrames.windowMs");
        this.maxFrameBytes = contract.object(WELCOME_FIXTURE).getAsJsonObject("payload")
                .getAsJsonObject("limits").get("maxFrameBytes").getAsInt();
        this.server = new Server();
        this.timers = Executors.newSingleThreadScheduledExecutor(UltiCloudStub.daemonThreads("ulticloud-stub-gateway"));
    }

    void start() throws IOException {
        server.start();
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                throw new IOException("The stub gateway did not start within 10 s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting the stub gateway", interrupted);
        }
        if (startFailure != null) {
            throw new IOException("The stub gateway could not start", startFailure);
        }
    }

    /** @return the internal loopback port the front door relays upgrades to */
    int port() {
        return server.getPort();
    }

    void stop() {
        try {
            server.stop(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        timers.shutdownNow();
    }

    // ------------------------------------------------------------------ queries

    /**
     * @param type a frame type, or {@code null} for every type
     * @return the frames received from plugins, oldest first, masked
     */
    public List<JsonObject> received(String type) {
        return frames("in", type);
    }

    /**
     * @param type a frame type, or {@code null} for every type
     * @return the frames the gateway sent, oldest first, masked
     */
    public List<JsonObject> sent(String type) {
        return frames("out", type);
    }

    /** @return every recorded frame in both directions, oldest first */
    public List<Frame> frames() {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    /**
     * Waits until at least {@code count} frames of {@code type} have been received.
     *
     * @param type      the frame type
     * @param count     how many
     * @param timeoutMs how long to wait
     * @return the received frames of that type, oldest first (fewer than {@code count} on timeout)
     * @throws InterruptedException when interrupted while waiting
     */
    public List<JsonObject> awaitReceived(String type, int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frames) {
            while (true) {
                List<JsonObject> found = frames("in", type);
                long left = deadline - System.currentTimeMillis();
                if (found.size() >= count || left <= 0) {
                    return found;
                }
                frames.wait(left);
            }
        }
    }

    /** @return how many plugin connections are open */
    public int openConnections() {
        return connections.size();
    }

    private List<JsonObject> frames(String direction, String type) {
        List<JsonObject> found = new ArrayList<>();
        synchronized (frames) {
            for (Frame frame : frames) {
                if (frame.direction.equals(direction) && (type == null || type.equals(frame.type()))) {
                    found.add(frame.frame());
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ connections

    private void opened(WebSocket socket, ClientHandshake handshake) {
        String credential = StubDeviceAuthority.bearer(handshake.getFieldValue("Authorization"));
        String serverId = credentials.serverIdOf(credential);
        Connection connection = new Connection(connectionIds.incrementAndGet(), socket, credential, serverId);
        log.log("WS-OPEN", connection + " serverId=" + serverId + " auth=Bearer " + credential);
        connections.put(socket, connection);
        if (serverId == null) {
            // Only a revoke racing the upgrade gets here: the front door checked the credential.
            synchronized (connection) {
                closeByStub(connection, 4401, "credential revoked");
            }
            return;
        }
        for (Connection older : new ArrayList<>(connections.values())) {
            if (older != connection && serverId.equals(older.serverId)) {
                synchronized (older) {
                    closeByStub(older, 4000, "superseded by a newer connection");
                }
            }
        }
        synchronized (connection) {
            connection.helloTimer = timers.schedule(() -> {
                synchronized (connection) {
                    if (!connection.welcomed) {
                        closeByStub(connection, 4408, "no hello within " + helloTimeoutMs + " ms");
                    }
                }
            }, helloTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void closed(WebSocket socket, int code, String reason, boolean remote) {
        Connection connection = connections.remove(socket);
        if (connection == null) {
            return;
        }
        synchronized (connection) {
            connection.cancelTimers();
            log.log("WS-CLOSED", connection + " code=" + code + " remote=" + remote
                    + (reason == null || reason.isEmpty() ? "" : " reason=" + reason));
        }
    }

    private void message(WebSocket socket, String text) {
        Connection connection = connections.get(socket);
        if (connection == null) {
            return;
        }
        synchronized (connection) {
            receive(connection, text);
        }
    }

    private void receive(Connection connection, String text) {
        if (PING.equals(text)) {
            log.log("WS-IN", connection + " ping");
            sendText(connection, PONG);
            return;
        }
        if (PONG.equals(text)) {
            log.log("WS-IN", connection + " pong");
            return;
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxFrameBytes) {
            refuse(connection, null, "?", "payload_too_large",
                    "frame of " + bytes + " bytes exceeds maxFrameBytes " + maxFrameBytes, true);
            return;
        }
        JsonObject frame = parseObject(text);
        String type = frame == null ? null : string(frame, "type");
        String id = frame == null ? null : string(frame, "id");
        if (!connection.welcomed) {
            receiveFirst(connection, frame, type);
            return;
        }
        UltiCloudStub.MessageType message = contract.message(type);
        if (frame == null || type == null) {
            refuse(connection, id, type == null ? "?" : type, "invalid_message", "$: the frame is not an envelope", true);
            return;
        }
        if (message == null) {
            refuse(connection, id, type, "unsupported_type", "type " + type + " is not registered", true);
            return;
        }
        if (!message.up()) {
            refuse(connection, id, type, "invalid_message", "type " + type + " is not sent by plugins", true);
            return;
        }
        String issue = contract.firstIssue(UltiCloudStub.Contract.frameSchema(type), frame);
        if (issue != null) {
            refuse(connection, id, type, "invalid_message", issue, true);
            return;
        }
        boolean duplicate = message.reliable() && !seenEventIds.add(id);
        record("in", connection, frame);
        log.log("WS-IN", connection + " " + describe(frame) + (duplicate ? " duplicate" : ""));
        if (HELLO.equals(type)) {
            return;
        }
        if (message.capability() != null && !message.capability().startsWith("modules:")
                && !connection.accepted.contains(message.capability())) {
            JsonObject details = new JsonObject();
            details.addProperty("capability", message.capability());
            sendError(connection, id, "capability_not_accepted",
                    "capability " + message.capability() + " was not accepted at the handshake", details);
            return;
        }
        connection.lastRequestIds.put(type, id);
        if (message.reliable()) {
            acknowledge(connection, id);
        }
    }

    private void receiveFirst(Connection connection, JsonObject frame, String type) {
        if (frame == null || !HELLO.equals(type)) {
            log.log("WS-INVALID", connection + " " + (type == null ? "?" : type)
                    + " the first frame of a connection must be hello");
            closeByStub(connection, 4400, "the first frame must be hello");
            return;
        }
        String issue = contract.firstIssue(UltiCloudStub.Contract.frameSchema(HELLO), frame);
        if (issue != null) {
            log.log("WS-INVALID", connection + " hello " + issue);
            closeByStub(connection, 4400, "invalid hello");
            return;
        }
        record("in", connection, frame);
        log.log("WS-IN", connection + " " + describe(frame));
        if (connection.helloTimer != null) {
            connection.helloTimer.cancel(false);
        }
        connection.lastRequestIds.put(HELLO, string(frame, "id"));
        welcome(connection, frame);
    }

    private void welcome(Connection connection, JsonObject hello) {
        JsonObject welcome = contract.object(WELCOME_FIXTURE);
        long now = System.currentTimeMillis();
        welcome.addProperty("id", UltiCloudStub.Contract.newUlid());
        welcome.addProperty("ts", now);
        welcome.addProperty("replyTo", string(hello, "id"));
        JsonObject payload = welcome.getAsJsonObject("payload");
        payload.addProperty("serverId", welcomeServerId(connection));
        JsonArray accepted = new JsonArray();
        for (JsonElement capability : hello.getAsJsonObject("payload").getAsJsonArray("capabilities")) {
            String name = capability.getAsString();
            if (contract.knowsCapability(name) && connection.accepted.add(name)) {
                accepted.add(name);
            }
        }
        payload.add("accepted", accepted);
        payload.addProperty("serverTime", now);
        connection.welcomed = true;
        sendFrame(connection, welcome);
    }

    /** The serverId the next welcome carries; Task 2's admin override hooks in here. */
    String welcomeServerId(Connection connection) {
        return connection.serverId;
    }

    private void acknowledge(Connection connection, String id) {
        if (!acking) {
            log.log("WS-NOTE", connection + " " + id + " not acknowledged: acknowledging is switched off");
            return;
        }
        connection.pendingAcks.add(id);
        if (connection.pendingAcks.size() >= ackBatchMax) {
            flushAcks(connection);
        } else if (connection.ackFlush == null) {
            connection.ackFlush = timers.schedule(() -> {
                synchronized (connection) {
                    flushAcks(connection);
                }
            }, ACK_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushAcks(Connection connection) {
        if (connection.ackFlush != null) {
            connection.ackFlush.cancel(false);
            connection.ackFlush = null;
        }
        if (connection.pendingAcks.isEmpty() || !connection.socket.isOpen()) {
            return;
        }
        JsonObject ack = contract.object(ACK_FIXTURE);
        ack.addProperty("id", UltiCloudStub.Contract.newUlid());
        ack.addProperty("ts", System.currentTimeMillis());
        JsonArray ids = new JsonArray();
        for (String id : connection.pendingAcks) {
            ids.add(id);
        }
        ack.getAsJsonObject("payload").add("ids", ids);
        connection.pendingAcks.clear();
        sendFrame(connection, ack);
    }

    /** Answers a refused inbound frame with {@code error} and counts it towards 4429. */
    private void refuse(Connection connection, String id, String type, String code, String issue, boolean counts) {
        log.log("WS-INVALID", connection + " " + type + " " + issue);
        sendError(connection, UltiCloudStub.Contract.isUlid(id) ? id : null, code, issue, null);
        if (!counts) {
            return;
        }
        long now = System.currentTimeMillis();
        connection.invalidAt.addLast(now);
        while (!connection.invalidAt.isEmpty() && connection.invalidAt.peekFirst() < now - invalidWindowMs) {
            connection.invalidAt.removeFirst();
        }
        if (connection.invalidAt.size() > invalidMax) {
            closeByStub(connection, 4429, "more than " + invalidMax + " invalid frames within " + invalidWindowMs + " ms");
        }
    }

    private void sendError(Connection connection, String replyTo, String code, String message, JsonObject details) {
        JsonObject error = contract.object(ERROR_FIXTURE);
        error.addProperty("id", UltiCloudStub.Contract.newUlid());
        error.addProperty("ts", System.currentTimeMillis());
        if (replyTo == null) {
            error.remove("replyTo");
        } else {
            error.addProperty("replyTo", replyTo);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message.length() > ERROR_MESSAGE_MAX ? message.substring(0, ERROR_MESSAGE_MAX) : message);
        if (details != null) {
            payload.add("details", details);
        }
        error.add("payload", payload);
        sendFrame(connection, error);
    }

    // ------------------------------------------------------------------ sending

    /** Sends one frame on a connection, after checking it against its schema; caller holds the connection. */
    void sendFrame(Connection connection, JsonObject frame) {
        String type = string(frame, "type");
        String issue = contract.firstIssue(UltiCloudStub.Contract.frameSchema(type), frame);
        if (issue != null) {
            log.log("WS-SELF-INVALID", connection + " " + type + " " + issue);
        }
        try {
            connection.socket.send(frame.toString());
        } catch (WebsocketNotConnectedException closedMeanwhile) {
            log.log("WS-NOTE", connection + " " + type + " not sent: the connection is closed");
            return;
        }
        record("out", connection, frame);
        log.log("WS-OUT", connection + " " + describe(frame));
    }

    private void sendText(Connection connection, String text) {
        try {
            connection.socket.send(text);
        } catch (WebsocketNotConnectedException closedMeanwhile) {
            return;
        }
        log.log("WS-OUT", connection + " " + text);
    }

    /** Closes a connection with a close frame; caller holds the connection. */
    void closeByStub(Connection connection, int code, String reason) {
        log.log("WS-OUT", connection + " close code=" + code + " reason=" + reason);
        connection.cancelTimers();
        connection.socket.close(code, reason);
    }

    /**
     * Closes every open connection authenticated with {@code credential}.
     *
     * @param credential the credential
     * @param code       the close code
     * @param reason     the close reason
     * @return how many connections were closed
     */
    public int closeConnectionsUsing(String credential, int code, String reason) {
        int closed = 0;
        for (Connection connection : new ArrayList<>(connections.values())) {
            if (credential != null && credential.equals(connection.credential)) {
                synchronized (connection) {
                    closeByStub(connection, code, reason);
                }
                closed++;
            }
        }
        return closed;
    }

    private void record(String direction, Connection connection, JsonObject frame) {
        JsonObject masked = JsonParser.parseString(StubAccessLog.mask(frame.toString())).getAsJsonObject();
        synchronized (frames) {
            frames.add(new Frame(System.currentTimeMillis(), connection.id, direction, masked));
            frames.notifyAll();
        }
    }

    private static String describe(JsonObject frame) {
        StringBuilder text = new StringBuilder(String.valueOf(string(frame, "type")))
                .append(" id=").append(string(frame, "id"));
        String replyTo = string(frame, "replyTo");
        if (replyTo != null) {
            text.append(" replyTo=").append(replyTo);
        }
        if ("ack".equals(string(frame, "type")) || "error".equals(string(frame, "type"))) {
            text.append(' ').append(frame.get("payload"));
        }
        return text.toString();
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException notJson) {
            return null;
        }
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value instanceof JsonPrimitive && ((JsonPrimitive) value).isString()) {
            return value.getAsString();
        }
        return null;
    }

    // ------------------------------------------------------------------ types

    /** One frame the gateway received or sent, masked. */
    public static final class Frame {
        private final long at;
        private final int connection;
        private final String direction;
        private final JsonObject frame;

        Frame(long at, int connection, String direction, JsonObject frame) {
            this.at = at;
            this.connection = connection;
            this.direction = direction;
            this.frame = frame;
        }

        /** @return when it was received or sent, epoch milliseconds */
        public long at() {
            return at;
        }

        /** @return the connection number, as the access log writes {@code conn=<n>} */
        public int connection() {
            return connection;
        }

        /** @return {@code in} (from the plugin) or {@code out} (from the gateway) */
        public String direction() {
            return direction;
        }

        /** @return the frame's type */
        public String type() {
            return string(frame, "type");
        }

        /** @return a copy of the frame, masked */
        public JsonObject frame() {
            return frame.deepCopy();
        }
    }

    /** One plugin connection and its protocol state; every field is guarded by the object's monitor. */
    static final class Connection {
        final int id;
        final WebSocket socket;
        final String credential;
        final String serverId;
        final Set<String> accepted = new LinkedHashSet<>();
        final Map<String, String> lastRequestIds = new HashMap<>();
        final List<String> pendingAcks = new ArrayList<>();
        final Deque<Long> invalidAt = new ArrayDeque<>();
        boolean welcomed;
        ScheduledFuture<?> helloTimer;
        ScheduledFuture<?> ackFlush;

        Connection(int id, WebSocket socket, String credential, String serverId) {
            this.id = id;
            this.socket = socket;
            this.credential = credential;
            this.serverId = serverId;
        }

        void cancelTimers() {
            if (helloTimer != null) {
                helloTimer.cancel(false);
            }
            if (ackFlush != null) {
                ackFlush.cancel(false);
                ackFlush = null;
            }
        }

        @Override
        public String toString() {
            return "conn=" + id;
        }
    }

    /** The Java-WebSocket server; every callback is forwarded to the gateway. */
    private final class Server extends WebSocketServer {
        Server() {
            super(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            setReuseAddr(true);
            setDaemon(true);
            // The contract's heartbeat is the text frame ping; protocol-level pings are not used.
            setConnectionLostTimeout(0);
        }

        @Override
        public void onOpen(WebSocket socket, ClientHandshake handshake) {
            opened(socket, handshake);
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {
            closed(socket, code, reason, remote);
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
            message(socket, message);
        }

        @Override
        public void onError(WebSocket socket, Exception ex) {
            if (socket == null) {
                startFailure = ex;
                started.countDown();
                return;
            }
            Connection connection = connections.get(socket);
            log.log("WS-ERROR", (connection == null ? "conn=?" : connection.toString()) + " "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }
}
