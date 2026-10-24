package com.ultikits.ultitools.utils;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;

/**
 * One attempt's Java-WebSocket client for the UltiCloud {@code /v1/agent} socket (as of 6.3.1).
 * <p>
 * It decides nothing: it forwards open, text, refusal and close to the {@link AgentConnection}
 * state machine through the attempt's {@link AgentConnection.SocketEvents}. A close whose upgrade
 * was answered with a status other than 101 (recorded by {@link StatusCapturingDraft}) is reported
 * as {@code onUpgradeRefused}; every other close as {@code onClosed}.
 * <p>
 * The TCP connect is bounded by the constructor's connect timeout (Java-WebSocket 1.6.0 has no
 * {@code setConnectTimeout}); the library's ping control frames are disabled with
 * {@code setConnectionLostTimeout(0)} because liveness is the application heartbeat's job
 * (contract answer 2). The bearer exists only in the header map handed to the superclass: this
 * class never logs it, keeps it in a field of its own, or prints it from {@code toString}.
 */
final class AgentSocket extends WebSocketClient implements AgentConnection.Handle {

    private static final int MAX_ERROR_TEXT = 200;

    private final AtomicReference<UpgradeRejection> sink;
    private final AgentConnection.SocketEvents events;
    private final BiConsumer<Level, String> log;
    /** Set when the state machine gave this attempt up; a late open is then closed at once. */
    private volatile boolean abandoned;

    /**
     * @param uri              the agent endpoint
     * @param headers          the upgrade headers built by {@link AgentConnection#buildHeaders}
     * @param connectTimeoutMs the TCP connect timeout
     * @param events           this attempt's event sink
     * @param log              the panel-connection log sink
     */
    AgentSocket(URI uri, Map<String, String> headers, int connectTimeoutMs,
                AgentConnection.SocketEvents events, BiConsumer<Level, String> log) {
        this(uri, headers, connectTimeoutMs, events, log, new AtomicReference<UpgradeRejection>());
    }

    private AgentSocket(URI uri, Map<String, String> headers, int connectTimeoutMs,
                        AgentConnection.SocketEvents events, BiConsumer<Level, String> log,
                        AtomicReference<UpgradeRejection> sink) {
        super(uri, new StatusCapturingDraft(sink), headers, connectTimeoutMs);
        this.sink = sink;
        this.events = events;
        this.log = log;
        setConnectionLostTimeout(0);
        setDaemon(true);
    }

    /** @return the upgrade response the draft recorded, or {@code null} before one arrived */
    UpgradeRejection lastResponse() {
        return sink.get();
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        if (abandoned) {
            close(AgentConnection.CLOSE_NORMAL, "abandoned attempt");
            return;
        }
        events.onOpen();
    }

    @Override
    public void onMessage(String message) {
        events.onText(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        UpgradeRejection rejection = sink.get();
        if (rejection != null && rejection.status() != 0
                && rejection.status() != StatusCapturingDraft.SWITCHING_PROTOCOLS) {
            events.onUpgradeRefused(rejection.status(), rejection);
            return;
        }
        events.onClosed(code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        // The message text only: never the request, its headers or the bearer. The library
        // follows an error with onClose, which is where the state machine decides.
        log.accept(Level.WARNING, "UltiCloud: connection error: " + describe(ex));
    }

    @Override
    public boolean sendText(String text) {
        if (!isOpen()) {
            return false;
        }
        try {
            send(text);
            return true;
        } catch (WebsocketNotConnectedException e) {
            return false;
        }
    }

    @Override
    public void closeAttempt(int code, String reason) {
        abandoned = true;
        if (isOpen()) {
            close(code, reason);
        } else {
            closeConnection(code, reason);
        }
    }

    private static String describe(Exception ex) {
        String text = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < MAX_ERROR_TEXT; i++) {
            char c = text.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString();
    }
}
