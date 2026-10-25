package com.ultikits.ultitools.utils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * The reconnect state machine of the UltiCloud v2 agent connection (as of 6.3.1).
 * <p>
 * It is the <b>single owner</b> of every connect, retry and terminal decision for the
 * {@code /v1/agent} socket: nothing else opens a socket, schedules a retry or decides that the
 * connection is over. (v1 had four owners deciding whether to keep trying.)
 *
 * <table>
 *   <caption>Transitions</caption>
 *   <tr><th>From</th><th>Event</th><th>To</th></tr>
 *   <tr><td>IDLE, STOPPED, terminal</td><td>{@link #start()} with a credential</td><td>CONNECTING (fresh cycle)</td></tr>
 *   <tr><td>CONNECTING</td><td>upgrade accepted</td><td>OPEN (not yet established)</td></tr>
 *   <tr><td>OPEN</td><td>{@link #markEstablished()} (a valid {@code welcome})</td><td>OPEN, established:
 *   counters reset, heartbeat started</td></tr>
 *   <tr><td>CONNECTING</td><td>refused 401</td><td>CREDENTIAL_REJECTED</td></tr>
 *   <tr><td>CONNECTING</td><td>refused 426</td><td>UPGRADE_REQUIRED</td></tr>
 *   <tr><td>CONNECTING</td><td>refused 402</td><td>PLAN_LIMITED (fixed plan-limit interval)</td></tr>
 *   <tr><td>CONNECTING</td><td>refused 429</td><td>BACKOFF (long, at least {@code Retry-After})</td></tr>
 *   <tr><td>CONNECTING</td><td>refused with another 4xx</td><td>BACKOFF (long)</td></tr>
 *   <tr><td>CONNECTING</td><td>refused with 5xx, network error, close, handshake deadline</td><td>BACKOFF (normal)</td></tr>
 *   <tr><td>OPEN, not established</td><td>handshake deadline (no {@code welcome}), {@link #abandonAttempt(String)}</td>
 *   <td>BACKOFF (normal)</td></tr>
 *   <tr><td>OPEN</td><td>{@link #stopForServerIdMismatch()}</td><td>SERVER_ID_MISMATCH</td></tr>
 *   <tr><td>OPEN, CONNECTING</td><td>close 4401 / 4426</td><td>CREDENTIAL_REJECTED / UPGRADE_REQUIRED</td></tr>
 *   <tr><td>OPEN, CONNECTING</td><td>close 4402</td><td>PLAN_LIMITED (one probe after the attempt-0 delay)</td></tr>
 *   <tr><td>OPEN, CONNECTING</td><td>close 4400, 4429</td><td>BACKOFF (long)</td></tr>
 *   <tr><td>OPEN, CONNECTING</td><td>close 4000</td><td>BACKOFF (long), or SUPERSEDED on the third in a row</td></tr>
 *   <tr><td>OPEN</td><td>any other close, heartbeat timeout</td><td>BACKOFF (normal)</td></tr>
 *   <tr><td>BACKOFF, PLAN_LIMITED</td><td>retry due, or {@link #reconnectNow()}</td><td>CONNECTING</td></tr>
 *   <tr><td>SUPERSEDED</td><td>{@link #reconnectNow()}</td><td>CONNECTING (counters reset)</td></tr>
 *   <tr><td>any</td><td>{@link #stop()}</td><td>STOPPED</td></tr>
 * </table>
 * What the owner deletes or prints on a terminal state is the owner's decision (the cloud
 * session); this class only reports it, once, through the {@link Listener}.
 * <p>
 * <b>Why an attempt id.</b> v1 reconnected by reusing one client object, so a reconnect carried
 * the stale headers (and bearer) of the first connect, and a late callback of an old socket could
 * change the state of a new one. Here every attempt builds a new socket with the bearer read at
 * that moment, and every socket's events are bound to the id ({@code generation}) the attempt had
 * when it started; the id only ever increases (an attempt's end, a retry, {@link #stop()} and a
 * terminal state each move it on), so an event whose id is not the current one is ignored.
 * <p>
 * <b>Threads and locks.</b> Socket callbacks arrive on Java-WebSocket's threads (or synchronously
 * inside a forced close); each one only posts a task onto the single-thread agent scheduler. All
 * state changes run under this instance's monitor. The monitor is never held while calling out:
 * listener callbacks, socket opens, closes and sends, the credential supplier and the log sink
 * all run after it is released, so a listener may call {@link #stop()}. In the session's lock
 * order this monitor sits beside {@code CredentialStore.LOCK} and the state-file write lock,
 * below the session monitor; nothing here blocks or waits (no {@code connectBlocking}, no
 * {@code Future#get}).
 * <p>
 * Every contract number lives in the one contract section below.
 */
final class AgentConnection {

    // =====================================================================================
    // Contract: the codes, classes and numbers below are those of the frozen protocol contract
    // 2.0.0 (PROTOCOL.md section 7, "close codes and client actions", and section 9's timing
    // constants), vendored under src/test/resources/ulticloud-contract/v2/2.0.0. AgentConnectionTest
    // holds one case per row of that table. Nothing outside this section names a raw code.
    // =====================================================================================

    /** Upgrade refused: the credential is invalid. Stop and delete the credential. */
    static final int HTTP_INVALID_CREDENTIAL = 401;
    /** Upgrade refused: the server is suspended for exceeding its plan. Keep the credential. */
    static final int HTTP_PLAN_LIMIT = 402;
    /** Upgrade refused: protocol or framework too old. Stop and keep the credential. */
    static final int HTTP_UPGRADE_REQUIRED = 426;
    /** Upgrade refused: rate limited. Long backoff, at least {@code Retry-After}. */
    static final int HTTP_TOO_MANY_REQUESTS = 429;
    /** The first 4xx status; every 4xx not named above backs off long. */
    static final int HTTP_CLIENT_ERROR_MIN = 400;
    /** The last 4xx status. */
    static final int HTTP_CLIENT_ERROR_MAX = 499;

    /** Closed: the server's plan limit applies; same handling as HTTP 402. */
    static final int CLOSE_PLAN_LIMIT = 4402;
    /** Closed: another connection with the same credential replaced this one. */
    static final int CLOSE_SUPERSEDED = 4000;
    /** Closed: an invalid hello, or a frame before welcome. */
    static final int CLOSE_INVALID_HELLO = 4400;
    /** Closed: the credential was revoked. */
    static final int CLOSE_REVOKED = 4401;
    /** Closed: no hello within the gateway's deadline. */
    static final int CLOSE_NO_HELLO = 4408;
    /** Closed: the hello's protocol does not match the upgrade header. */
    static final int CLOSE_PROTOCOL_MISMATCH = 4426;
    /** Closed: abuse rate (including too many invalid frames). */
    static final int CLOSE_ABUSE_RATE = 4429;
    /** The normal close code this side uses for its own closes. */
    static final int CLOSE_NORMAL = 1000;
    /** Java-WebSocket's code for a socket that never connected. */
    static final int CLOSE_NEVER_CONNECTED = -1;

    /** Consecutive 4000 closes, with no long-lived connection between them, that end retrying. */
    static final int SUPERSEDED_STOP_AFTER = 3;
    /** Fixed plan-limit retry interval: 15 minutes, no jitter, no growth. */
    static final long PLAN_LIMIT_RETRY_MS = 900000L;
    /** Largest {@code Retry-After} honoured, in seconds. */
    static final long RETRY_AFTER_MAX_SECONDS = 3600L;
    /** A connection open at least this long resets the consecutive-4000 count. */
    static final long SUPERSEDED_RESET_MS = 600000L;
    /** Deadline for an attempt to be established: upgraded and answered with a valid {@code welcome}. */
    static final long HANDSHAKE_DEADLINE_MS = 15000L;
    /** TCP connect timeout handed to the socket's constructor. */
    static final int CONNECT_TIMEOUT_MS = 10000;
    /** Heartbeat: the text frame {@link #HEARTBEAT_PING} is sent this often once established. */
    static final long HEARTBEAT_INTERVAL_MS = 30000L;
    /** Heartbeat: without a {@link #HEARTBEAT_PONG} for this long the connection is closed. */
    static final long HEARTBEAT_TIMEOUT_MS = 90000L;
    /** The heartbeat request text frame. */
    static final String HEARTBEAT_PING = "ping";
    /** The heartbeat reply text frame. */
    static final String HEARTBEAT_PONG = "pong";

    /** Upgrade header carrying the credential. */
    static final String AUTHORIZATION_HEADER = "Authorization";
    /** Upgrade header carrying the protocol major version. */
    static final String PROTOCOL_HEADER = "X-UltiCloud-Protocol";
    /** Upgrade header carrying the running framework version. */
    static final String FRAMEWORK_HEADER = "X-UltiCloud-Framework";
    /** 426 response header carrying the minimum framework version. */
    static final String MIN_FRAMEWORK_HEADER = "X-UltiCloud-Min-Framework";
    /** The protocol version this client speaks. */
    static final String PROTOCOL_VERSION = "2";

    /** What a refusal or a close means for the connection. */
    enum Disposition {
        /** Retry after a normal delay. */
        RETRY,
        /** Retry after a long delay. */
        RETRY_LONG,
        /** Plan limit: keep the credential and retry on the fixed plan-limit interval. */
        PLAN_LIMIT,
        /** Stop for good; the credential is no longer valid. */
        STOP_DELETE_CREDENTIAL,
        /** Stop for good; keep the credential (an upgrade is required). */
        STOP_KEEP_CREDENTIAL
    }

    /**
     * Classifies a refused upgrade.
     *
     * @param httpStatus the HTTP status of the refused upgrade
     * @return its disposition; another 4xx retries long, a 5xx (and anything else) normally
     */
    static Disposition forRefusal(int httpStatus) {
        switch (httpStatus) {
            case HTTP_INVALID_CREDENTIAL:
                return Disposition.STOP_DELETE_CREDENTIAL;
            case HTTP_UPGRADE_REQUIRED:
                return Disposition.STOP_KEEP_CREDENTIAL;
            case HTTP_PLAN_LIMIT:
                return Disposition.PLAN_LIMIT;
            case HTTP_TOO_MANY_REQUESTS:
                return Disposition.RETRY_LONG;
            default:
                return httpStatus >= HTTP_CLIENT_ERROR_MIN && httpStatus <= HTTP_CLIENT_ERROR_MAX
                        ? Disposition.RETRY_LONG : Disposition.RETRY;
        }
    }

    /**
     * Classifies a close of an upgraded (or never-upgraded) socket.
     *
     * @param code the close code
     * @return its disposition; every code not named here (1000, 1001, 1006, 1009, 1011, 4408,
     *         unknown) retries normally
     */
    static Disposition forClose(int code) {
        switch (code) {
            case CLOSE_REVOKED:
                return Disposition.STOP_DELETE_CREDENTIAL;
            case CLOSE_PROTOCOL_MISMATCH:
                return Disposition.STOP_KEEP_CREDENTIAL;
            case CLOSE_PLAN_LIMIT:
                return Disposition.PLAN_LIMIT;
            case CLOSE_SUPERSEDED:
            case CLOSE_INVALID_HELLO:
            case CLOSE_ABUSE_RATE:
                return Disposition.RETRY_LONG;
            default:
                return Disposition.RETRY;
        }
    }

    // ============================== end of the contract section ==============================

    /** The states of the connection. */
    enum State {
        /** Never started. */
        IDLE,
        /** An attempt is in flight. */
        CONNECTING,
        /** The upgrade was accepted. */
        OPEN,
        /** Waiting to retry. */
        BACKOFF,
        /** Plan limit: waiting on the fixed plan-limit interval, credential kept. */
        PLAN_LIMITED,
        /** Stopped locally (or no credential). */
        STOPPED,
        /** Terminal: the credential was refused (401) or revoked (4401). */
        CREDENTIAL_REJECTED,
        /** Terminal: the framework or protocol is too old (426, 4426); credential kept. */
        UPGRADE_REQUIRED,
        /** Terminal: replaced by another connection with the same credential too often. */
        SUPERSEDED,
        /**
         * Terminal: the gateway's {@code welcome} named another server id than the linked one
         * (contract SEMANTICS 16); credential kept. Only a restart or a new login leaves it.
         */
        SERVER_ID_MISMATCH
    }

    /** What {@link #reconnectNow()} did. */
    enum ReconnectOutcome {
        /** An attempt was started at once. */
        STARTED,
        /** Already connected. */
        ALREADY_OPEN,
        /** An attempt is already in flight. */
        ALREADY_CONNECTING,
        /** Stopped because an upgrade is required; reconnecting cannot help. */
        UPGRADE_REQUIRED,
        /** Not running (never started, stopped, or the credential was rejected). */
        NOT_RUNNING
    }

    /** Callbacks to the owner (the cloud session). Never invoked while this monitor is held. */
    interface Listener {
        /** @param state the new state, once per real transition */
        default void onStateChanged(State state) {
        }

        /**
         * @param statusOrCloseCode 401 or 4401
         * @param requestIdOrNull   the refused response's request id
         */
        default void onCredentialRejected(int statusOrCloseCode, String requestIdOrNull) {
        }

        /**
         * @param minFrameworkOrNull the minimum framework the gateway accepts, sanitised
         * @param requestIdOrNull    the refused response's request id
         */
        default void onUpgradeRequired(String minFrameworkOrNull, String requestIdOrNull) {
        }

        /** Too many consecutive supersessions; retrying stopped, credential kept. */
        default void onSuperseded() {
        }

        /** The {@code welcome} named another server id; retrying stopped, credential kept. */
        default void onServerIdMismatch() {
        }

        /** @param notice the first 402 response of a plan-limit episode; once per episode */
        default void onPlanLimited(UpgradeRejection notice) {
        }

        /** The upgrade was accepted. */
        default void onOpened() {
        }

        /** @param text an inbound text frame other than the heartbeat reply */
        default void onText(String text) {
        }
    }

    /** One attempt's socket, as the state machine sees it. */
    interface Handle {
        /**
         * Closes the attempt: the closing handshake when open, a forced close otherwise.
         *
         * @param code   the close code
         * @param reason the close reason
         */
        void closeAttempt(int code, String reason);

        /**
         * @param text the text frame
         * @return whether it was handed to an open socket
         */
        boolean sendText(String text);
    }

    /** Events of one attempt's socket; each instance is bound to one attempt id. */
    interface SocketEvents {
        /** The upgrade was accepted. */
        void onOpen();

        /** @param text an inbound text frame */
        void onText(String text);

        /**
         * @param httpStatus the refused upgrade's status
         * @param rejection  the parsed response
         */
        void onUpgradeRefused(int httpStatus, UpgradeRejection rejection);

        /**
         * @param code   the close code
         * @param reason the close reason
         * @param remote whether the peer closed
         */
        void onClosed(int code, String reason, boolean remote);
    }

    /** Opens one attempt's socket; must not block. */
    interface SocketFactory {
        /**
         * @param endpoint the agent endpoint
         * @param headers  the upgrade headers
         * @param events   the attempt's event sink
         * @return the attempt's handle
         */
        Handle open(URI endpoint, Map<String, String> headers, SocketEvents events);
    }

    /** The connection's timers. */
    static final class Timing {
        final long handshakeDeadlineMs;
        final long heartbeatIntervalMs;
        final long heartbeatTimeoutMs;
        final long supersededResetMs;
        final long planLimitRetryMs;

        private Timing(long handshakeDeadlineMs, long heartbeatIntervalMs, long heartbeatTimeoutMs,
                       long supersededResetMs, long planLimitRetryMs) {
            this.handshakeDeadlineMs = handshakeDeadlineMs;
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            this.supersededResetMs = supersededResetMs;
            this.planLimitRetryMs = planLimitRetryMs;
        }

        /** @return the production timers */
        static Timing defaults() {
            return new Timing(HANDSHAKE_DEADLINE_MS, HEARTBEAT_INTERVAL_MS, HEARTBEAT_TIMEOUT_MS,
                    SUPERSEDED_RESET_MS, PLAN_LIMIT_RETRY_MS);
        }

        /** @return timers for tests */
        static Timing of(long handshakeDeadlineMs, long heartbeatIntervalMs, long heartbeatTimeoutMs,
                         long supersededResetMs, long planLimitRetryMs) {
            return new Timing(handshakeDeadlineMs, heartbeatIntervalMs, heartbeatTimeoutMs,
                    supersededResetMs, planLimitRetryMs);
        }
    }

    private final URI endpoint;
    private final Supplier<String> bearerSupplier;
    private final String frameworkVersion;
    private final ScheduledExecutorService scheduler;
    private final Listener listener;
    private final SocketFactory sockets;
    private final ReconnectPolicy policy;
    private final LongSupplier clock;
    private final Timing timing;
    private final BiConsumer<Level, String> log;

    // Guarded by this.
    private State state = State.IDLE;
    /** Identifies the live attempt or pending retry; only ever increases. */
    private long generation;
    private Handle handle;
    private int attemptsSinceEstablished;
    private int currentAttemptNumber;
    private int normalFailures;
    private int longFailures;
    private int consecutiveSuperseded;
    private long openedAtMillis;
    /** Whether the open attempt was answered with a valid {@code welcome} ({@link #markEstablished()}). */
    private boolean established;
    private ScheduledFuture<?> retryFuture;
    private ScheduledFuture<?> deadlineFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private long lastPongMillis;
    private long retryAtMillis;
    private boolean planEpisode;
    private boolean planNoticeSent;

    /**
     * The production connection.
     *
     * @param endpoint         the agent endpoint, from {@link AgentEndpoint#fromApiUrl}
     * @param bearerSupplier   reads the current credential; {@code null} means none
     * @param frameworkVersion the running framework version
     * @param scheduler        the single-thread agent scheduler
     * @param listener         the owner's callbacks
     */
    AgentConnection(URI endpoint, Supplier<String> bearerSupplier, String frameworkVersion,
                    ScheduledExecutorService scheduler, Listener listener) {
        this(endpoint, bearerSupplier, frameworkVersion, scheduler, listener,
                realSockets(CONNECT_TIMEOUT_MS, PanelConnectionLog::log),
                new ReconnectPolicy(() -> ThreadLocalRandom.current().nextDouble()),
                System::currentTimeMillis, Timing.defaults(), PanelConnectionLog::log);
    }

    /** The full constructor, for tests. */
    AgentConnection(URI endpoint, Supplier<String> bearerSupplier, String frameworkVersion,
                    ScheduledExecutorService scheduler, Listener listener, SocketFactory sockets,
                    ReconnectPolicy policy, LongSupplier clock, Timing timing,
                    BiConsumer<Level, String> log) {
        this.endpoint = endpoint;
        this.bearerSupplier = bearerSupplier;
        this.frameworkVersion = frameworkVersion;
        this.scheduler = scheduler;
        this.listener = listener;
        this.sockets = sockets;
        this.policy = policy;
        this.clock = clock;
        this.timing = timing;
        this.log = log;
    }

    /**
     * The production socket factory: a fresh {@link AgentSocket} per attempt, connected with the
     * non-blocking {@code connect()} (never {@code connectBlocking}).
     */
    static SocketFactory realSockets(int connectTimeoutMs, BiConsumer<Level, String> log) {
        return (uri, headers, events) -> {
            AgentSocket socket = new AgentSocket(uri, headers, connectTimeoutMs, events, log);
            socket.connect();
            return socket;
        };
    }

    /**
     * The upgrade headers: exactly the credential, the protocol version and the framework version.
     *
     * @param bearer the credential
     * @return the headers, in a fixed order
     */
    Map<String, String> buildHeaders(String bearer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AUTHORIZATION_HEADER, "Bearer " + bearer);
        headers.put(PROTOCOL_HEADER, PROTOCOL_VERSION);
        headers.put(FRAMEWORK_HEADER, frameworkVersion);
        return Collections.unmodifiableMap(headers);
    }

    // ------------------------------------------------------------------------- public operations

    /**
     * Starts a fresh cycle from IDLE, STOPPED or a terminal state, with every counter reset. A
     * no-op while connecting, open, backing off or plan-limited. Without a credential the
     * connection goes to STOPPED.
     */
    void start() {
        synchronized (this) {
            if (!isStartable(state)) {
                return;
            }
        }
        String bearer = readBearer();
        Effects out = new Effects();
        synchronized (this) {
            if (!isStartable(state)) {
                return;
            }
            resetCycle();
            if (bearer == null) {
                generation++;
                moveTo(State.STOPPED, out);
            } else {
                beginAttempt(bearer, out);
            }
        }
        out.run();
    }

    /**
     * Stops: cancels the pending retry and closes the socket with 1000. Events from older sockets
     * are ignored afterwards.
     */
    void stop() {
        Effects out = new Effects();
        Handle toClose;
        synchronized (this) {
            generation++;
            cancelRetry();
            cancelTimers();
            toClose = handle;
            handle = null;
            planEpisode = false;
            planNoticeSent = false;
            moveTo(State.STOPPED, out);
        }
        if (toClose != null) {
            out.add(() -> toClose.closeAttempt(CLOSE_NORMAL, "stopping"));
        }
        out.run();
    }

    /**
     * Retries at once from BACKOFF, PLAN_LIMITED or SUPERSEDED, cancelling the pending retry
     * (from SUPERSEDED with every counter reset; from PLAN_LIMITED the plan-limit episode
     * continues, so a renewed 402 sends no second notice). Used by {@code /ulticloud reconnect}.
     *
     * @return {@code STARTED}, or why nothing was started
     */
    ReconnectOutcome reconnectNow() {
        ReconnectOutcome refusal;
        synchronized (this) {
            refusal = reconnectRefusal(state);
        }
        if (refusal != null) {
            return refusal;
        }
        String bearer = readBearer();
        Effects out = new Effects();
        ReconnectOutcome outcome;
        synchronized (this) {
            outcome = reconnectRefusal(state);
            if (outcome == null) {
                outcome = ReconnectOutcome.STARTED;
                if (state == State.SUPERSEDED) {
                    resetCycle();
                }
                cancelRetry();
                if (bearer == null) {
                    generation++;
                    moveTo(State.STOPPED, out);
                    outcome = ReconnectOutcome.NOT_RUNNING;
                } else {
                    beginAttempt(bearer, out);
                }
            }
        }
        out.run();
        return outcome;
    }

    private static ReconnectOutcome reconnectRefusal(State s) {
        switch (s) {
            case BACKOFF:
            case PLAN_LIMITED:
            case SUPERSEDED:
                return null;
            case OPEN:
                return ReconnectOutcome.ALREADY_OPEN;
            case CONNECTING:
                return ReconnectOutcome.ALREADY_CONNECTING;
            case UPGRADE_REQUIRED:
                return ReconnectOutcome.UPGRADE_REQUIRED;
            default:
                return ReconnectOutcome.NOT_RUNNING;
        }
    }

    /**
     * Marks the open connection as established, called by the protocol layer when a valid
     * {@code welcome} answers the {@code hello} (D-12, contract SEMANTICS 10): resets the normal and
     * long backoff counters and the attempt number, cancels the handshake deadline and starts the
     * text heartbeat. A {@code ping} before {@code welcome} would be closed with 4400, so nothing
     * here runs at the upgrade itself. A no-op unless OPEN; never calls out.
     */
    void markEstablished() {
        synchronized (this) {
            if (state != State.OPEN) {
                return;
            }
            established = true;
            cancelDeadline();
            normalFailures = 0;
            longFailures = 0;
            attemptsSinceEstablished = 0;
            if (heartbeatFuture == null) {
                lastPongMillis = clock.getAsLong();
                scheduleHeartbeat(generation);
            }
        }
    }

    /**
     * Ends the open attempt because the protocol layer refused what the gateway sent (an invalid
     * {@code welcome}, S-51): the socket is closed with 1000 and the normal backoff follows. A
     * no-op unless OPEN.
     *
     * @param reason the reason for the retry line, never carrying payload or credential text
     */
    void abandonAttempt(String reason) {
        Effects out = new Effects();
        Handle toClose;
        synchronized (this) {
            if (state != State.OPEN) {
                return;
            }
            toClose = handle;
            handle = null;
            retryNormal(reason, out);
        }
        if (toClose != null) {
            toClose.closeAttempt(CLOSE_NORMAL, reason);
        }
        out.run();
    }

    /**
     * Stops for good because the {@code welcome} named another server id than the linked one
     * (contract answer 21, SEMANTICS 16): the socket is closed with 1000, no retry is scheduled and
     * the owner is told once through {@link Listener#onServerIdMismatch()}; the credential is kept.
     * {@link #reconnectNow()} does not leave the resulting state. A no-op unless OPEN.
     */
    void stopForServerIdMismatch() {
        Effects out = new Effects();
        Handle toClose;
        synchronized (this) {
            if (state != State.OPEN) {
                return;
            }
            toClose = handle;
            terminal(State.SERVER_ID_MISMATCH, listener::onServerIdMismatch, out);
        }
        if (toClose != null) {
            toClose.closeAttempt(CLOSE_NORMAL, "server id mismatch");
        }
        out.run();
    }

    /** @return the running framework version this connection sends in its upgrade headers */
    String frameworkVersion() {
        return frameworkVersion;
    }

    /** @return whether the open connection was answered with a valid {@code welcome} */
    synchronized boolean isEstablished() {
        return state == State.OPEN && established;
    }

    /**
     * Sends a text frame.
     *
     * @param text the frame
     * @return whether it was handed to an open socket; false unless OPEN
     */
    boolean send(String text) {
        Handle h;
        synchronized (this) {
            if (state != State.OPEN) {
                return false;
            }
            h = handle;
        }
        return h != null && h.sendText(text);
    }

    /** @return the current state */
    synchronized State state() {
        return state;
    }

    /** @return the 1-based number of the current or last attempt since the last established connection */
    synchronized int attempt() {
        return currentAttemptNumber;
    }

    /** @return when the pending retry runs, from the injected clock; 0 when none is pending */
    synchronized long nextRetryAtMillis() {
        return retryFuture == null ? 0 : retryAtMillis;
    }

    // ------------------------------------------------------------------------- transitions

    private static boolean isStartable(State s) {
        return s == State.IDLE || s == State.STOPPED || s == State.CREDENTIAL_REJECTED
                || s == State.UPGRADE_REQUIRED || s == State.SUPERSEDED || s == State.SERVER_ID_MISMATCH;
    }

    private void resetCycle() {
        cancelRetry();
        attemptsSinceEstablished = 0;
        normalFailures = 0;
        longFailures = 0;
        consecutiveSuperseded = 0;
        planEpisode = false;
        planNoticeSent = false;
    }

    private boolean silent() {
        return planEpisode && planNoticeSent;
    }

    /** Under the monitor: enters CONNECTING and queues the socket open. */
    private void beginAttempt(String bearer, Effects out) {
        long id = ++generation;
        attemptsSinceEstablished++;
        currentAttemptNumber = attemptsSinceEstablished;
        handle = null;
        established = false;
        moveTo(State.CONNECTING, out);
        cancelTimers();
        try {
            deadlineFuture = scheduler.schedule(() -> handshakeDeadline(id), timing.handshakeDeadlineMs,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Shutting down: no event of this attempt can be delivered either.
        }
        Map<String, String> headers = buildHeaders(bearer);
        out.log(silent() ? Level.FINE : Level.INFO,
                "UltiCloud: connecting to " + endpoint + " (attempt " + currentAttemptNumber + ").");
        out.add(() -> openSocket(id, headers));
    }

    /** Outside the monitor: opens the attempt's socket and keeps it if the attempt is still live. */
    private void openSocket(long id, Map<String, String> headers) {
        Handle opened;
        try {
            opened = sockets.open(endpoint, headers, new AttemptEvents(id));
        } catch (RuntimeException e) {
            post(() -> onAttemptFailed(id, "could not open the connection (" + e.getClass().getSimpleName() + ")"));
            return;
        }
        boolean keep;
        synchronized (this) {
            keep = id == generation;
            if (keep) {
                handle = opened;
            }
        }
        if (!keep && opened != null) {
            opened.closeAttempt(CLOSE_NORMAL, "stale attempt");
        }
    }

    private void handleOpen(long id) {
        Effects out = new Effects();
        synchronized (this) {
            if (id != generation || state != State.CONNECTING) {
                return;
            }
            moveTo(State.OPEN, out);
            // The deadline keeps running until welcome (markEstablished): an upgraded socket that
            // never answers the hello must not stay open forever without a heartbeat.
            established = false;
            openedAtMillis = clock.getAsLong();
            if (planEpisode) {
                planEpisode = false;
                planNoticeSent = false;
                normalFailures = 0;
                longFailures = 0;
                out.log(Level.INFO, "UltiCloud: the plan limit no longer applies; this server is connected again.");
            }
            out.add(listener::onOpened);
        }
        out.run();
    }

    private void handleText(long id, String text) {
        synchronized (this) {
            if (id != generation || state != State.OPEN) {
                return;
            }
            if (HEARTBEAT_PONG.equals(text)) {
                lastPongMillis = clock.getAsLong();
                return;
            }
        }
        listener.onText(text);
    }

    private void handleRefused(long id, int status, UpgradeRejection rejection) {
        Effects out = new Effects();
        synchronized (this) {
            if (id != generation || state != State.CONNECTING) {
                return;
            }
            String reason = "HTTP " + status + (rejection.code() == null ? "" : " " + rejection.code());
            switch (forRefusal(status)) {
                case STOP_DELETE_CREDENTIAL:
                    terminal(State.CREDENTIAL_REJECTED,
                            () -> listener.onCredentialRejected(status, rejection.requestId()), out);
                    break;
                case STOP_KEEP_CREDENTIAL:
                    terminal(State.UPGRADE_REQUIRED,
                            () -> listener.onUpgradeRequired(rejection.minFramework(), rejection.requestId()), out);
                    break;
                case PLAN_LIMIT:
                    planLimitRefusal(rejection, reason, out);
                    break;
                case RETRY_LONG:
                    retryLong(reason, rejection.retryAfterSeconds(), out);
                    break;
                default:
                    retryNormal(reason, out);
                    break;
            }
        }
        out.run();
    }

    private void handleClosed(long id, int code) {
        Effects out = new Effects();
        synchronized (this) {
            if (id != generation || (state != State.CONNECTING && state != State.OPEN)) {
                return;
            }
            boolean wasOpen = state == State.OPEN;
            long openFor = wasOpen ? clock.getAsLong() - openedAtMillis : 0;
            String reason = code == CLOSE_NEVER_CONNECTED ? "could not connect" : "closed " + code;
            switch (forClose(code)) {
                case STOP_DELETE_CREDENTIAL:
                    terminal(State.CREDENTIAL_REJECTED, () -> listener.onCredentialRejected(code, null), out);
                    break;
                case STOP_KEEP_CREDENTIAL:
                    terminal(State.UPGRADE_REQUIRED, () -> listener.onUpgradeRequired(null, null), out);
                    break;
                case PLAN_LIMIT:
                    planLimitClose(reason, out);
                    break;
                case RETRY_LONG:
                    if (code == CLOSE_SUPERSEDED) {
                        superseded(wasOpen && openFor >= timing.supersededResetMs, out);
                    } else {
                        retryLong(reason, -1, out);
                    }
                    break;
                default:
                    retryNormal(reason, out);
                    break;
            }
        }
        out.run();
    }

    private void onAttemptFailed(long id, String reason) {
        Effects out = new Effects();
        synchronized (this) {
            if (id != generation || (state != State.CONNECTING && state != State.OPEN)) {
                return;
            }
            retryNormal(reason, out);
        }
        out.run();
    }

    /** Under the monitor: a 402 refusal. */
    private void planLimitRefusal(UpgradeRejection rejection, String reason, Effects out) {
        boolean firstOfEpisode = !planNoticeSent;
        boolean quiet = silent();
        planEpisode = true;
        planNoticeSent = true;
        scheduleRetry(State.PLAN_LIMITED, timing.planLimitRetryMs, reason, quiet ? Level.FINE : Level.INFO, out);
        if (firstOfEpisode) {
            out.add(() -> listener.onPlanLimited(rejection));
        }
    }

    /**
     * Under the monitor: close 4402. The close frame cannot carry the reason or the manage link,
     * so one probe follows after the normal attempt-0 delay; its 402 sends the notice.
     */
    private void planLimitClose(String reason, Effects out) {
        planEpisode = true;
        planNoticeSent = false;
        scheduleRetry(State.PLAN_LIMITED, policy.delayMillis(0), reason, Level.INFO, out);
    }

    /** Under the monitor: a failure other than the plan limit ends a plan-limit episode. */
    private void endPlanEpisode() {
        planEpisode = false;
        planNoticeSent = false;
    }

    private void retryNormal(String reason, Effects out) {
        endPlanEpisode();
        long delay = policy.delayMillis(normalFailures);
        normalFailures = bump(normalFailures);
        scheduleRetry(State.BACKOFF, delay, reason, Level.INFO, out);
    }

    /** Under the monitor: a long-class failure; a 429 waits at least its {@code Retry-After}. */
    private void retryLong(String reason, long retryAfterSeconds, Effects out) {
        endPlanEpisode();
        long delay = policy.longDelayMillis(longFailures);
        longFailures = bump(longFailures);
        if (retryAfterSeconds >= 0) {
            delay = Math.min(RETRY_AFTER_MAX_SECONDS * 1000L, Math.max(delay, retryAfterSeconds * 1000L));
        }
        scheduleRetry(State.BACKOFF, delay, reason, Level.INFO, out);
    }

    /**
     * Under the monitor: close 4000. Three in a row with no connection lasting
     * {@code supersededResetMs} in between stop retrying (credential kept); the earlier ones back
     * off long with a warning instead of the retry line.
     */
    private void superseded(boolean afterLongLivedConnection, Effects out) {
        endPlanEpisode();
        consecutiveSuperseded = afterLongLivedConnection ? 1 : bump(consecutiveSuperseded);
        if (consecutiveSuperseded >= SUPERSEDED_STOP_AFTER) {
            terminal(State.SUPERSEDED, listener::onSuperseded, out);
            return;
        }
        long delay = policy.longDelayMillis(longFailures);
        longFailures = bump(longFailures);
        if (scheduleRetry(State.BACKOFF, delay, null, Level.INFO, out)) {
            out.log(Level.WARNING, "UltiCloud: another connection is using this server's credential and "
                    + "replaced this one (close " + CLOSE_SUPERSEDED + "); retrying in " + seconds(delay) + " s.");
        }
    }

    /**
     * Under the monitor: ends the attempt and schedules the next one; logs the retry line unless
     * {@code reason} is null (the caller logs its own line).
     *
     * @return whether the retry was scheduled
     */
    private boolean scheduleRetry(State next, long delayMs, String reason, Level level, Effects out) {
        long token = ++generation;
        handle = null;
        cancelRetry();
        cancelTimers();
        moveTo(next, out);
        try {
            retryFuture = scheduler.schedule(() -> runRetry(token), delayMs, TimeUnit.MILLISECONDS);
            retryAtMillis = clock.getAsLong() + delayMs;
        } catch (RejectedExecutionException e) {
            moveTo(State.STOPPED, out);
            return false;
        }
        if (reason != null) {
            out.log(level, "UltiCloud: connection attempt " + currentAttemptNumber + " failed (" + reason
                    + "); retrying in " + seconds(delayMs) + " s.");
        }
        return true;
    }

    private void runRetry(long token) {
        synchronized (this) {
            if (token != generation || (state != State.BACKOFF && state != State.PLAN_LIMITED)) {
                return;
            }
        }
        String bearer = readBearer();
        Effects out = new Effects();
        synchronized (this) {
            if (token != generation || (state != State.BACKOFF && state != State.PLAN_LIMITED)) {
                return;
            }
            retryFuture = null;
            retryAtMillis = 0;
            if (bearer == null) {
                generation++;
                moveTo(State.STOPPED, out);
            } else {
                beginAttempt(bearer, out);
            }
        }
        out.run();
    }

    /** Under the monitor: enters a terminal state. */
    private void terminal(State terminalState, Runnable callback, Effects out) {
        generation++;
        handle = null;
        cancelRetry();
        cancelTimers();
        planEpisode = false;
        planNoticeSent = false;
        moveTo(terminalState, out);
        out.add(callback);
    }

    /** On the scheduler: the attempt's handshake deadline, covering the upgrade and the welcome. */
    private void handshakeDeadline(long id) {
        Effects out = new Effects();
        Handle toClose;
        synchronized (this) {
            boolean awaitingWelcome = state == State.OPEN && !established;
            if (id != generation || (state != State.CONNECTING && !awaitingWelcome)) {
                return;
            }
            deadlineFuture = null;
            toClose = handle;
            handle = null;
            retryNormal((awaitingWelcome ? "no welcome within " : "no handshake response within ")
                    + seconds(timing.handshakeDeadlineMs) + " s", out);
        }
        if (toClose != null) {
            // The attempt id has moved on, so this socket's later onClose is stale. If the socket
            // was not handed back yet, openSocket closes it when it is.
            toClose.closeAttempt(CLOSE_NORMAL, "handshake timeout");
        }
        out.run();
    }

    /** Under the monitor: the next heartbeat tick of the established connection {@code id}. */
    private void scheduleHeartbeat(long id) {
        try {
            heartbeatFuture = scheduler.schedule(() -> heartbeatTick(id), timing.heartbeatIntervalMs,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            heartbeatFuture = null;
        }
    }

    /**
     * On the scheduler: sends the text frame {@code ping}, or closes and retries when no
     * {@code pong} arrived within the heartbeat timeout (contract answer 2; the library's ping
     * control frames are disabled).
     */
    private void heartbeatTick(long id) {
        Effects out = new Effects();
        Handle target;
        boolean timedOut;
        synchronized (this) {
            if (id != generation || state != State.OPEN) {
                return;
            }
            heartbeatFuture = null;
            target = handle;
            timedOut = clock.getAsLong() - lastPongMillis >= timing.heartbeatTimeoutMs;
            if (timedOut) {
                handle = null;
                retryNormal("no heartbeat reply within " + seconds(timing.heartbeatTimeoutMs) + " s", out);
            } else {
                scheduleHeartbeat(id);
            }
        }
        if (target != null) {
            if (timedOut) {
                target.closeAttempt(CLOSE_NORMAL, "heartbeat timeout");
            } else {
                target.sendText(HEARTBEAT_PING);
            }
        }
        out.run();
    }

    private void cancelDeadline() {
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
    }

    private void cancelTimers() {
        cancelDeadline();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void cancelRetry() {
        if (retryFuture != null) {
            retryFuture.cancel(false);
            retryFuture = null;
        }
        retryAtMillis = 0;
    }

    private void moveTo(State next, Effects out) {
        if (state == next) {
            return;
        }
        state = next;
        out.add(() -> listener.onStateChanged(next));
    }

    private String readBearer() {
        try {
            String bearer = bearerSupplier.get();
            return bearer == null || bearer.trim().isEmpty() ? null : bearer;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void post(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The scheduler is shut down: the event is stale by definition.
        }
    }

    private static int bump(int counter) {
        return counter >= 1000 ? counter : counter + 1;
    }

    private static long seconds(long millis) {
        return (millis + 999) / 1000;
    }

    /** One attempt's socket events: each posts onto the agent scheduler carrying its attempt id. */
    private final class AttemptEvents implements SocketEvents {
        private final long id;

        AttemptEvents(long id) {
            this.id = id;
        }

        @Override
        public void onOpen() {
            post(() -> handleOpen(id));
        }

        @Override
        public void onText(String text) {
            post(() -> handleText(id, text));
        }

        @Override
        public void onUpgradeRefused(int httpStatus, UpgradeRejection rejection) {
            post(() -> handleRefused(id, httpStatus, rejection));
        }

        @Override
        public void onClosed(int code, String reason, boolean remote) {
            post(() -> handleClosed(id, code));
        }
    }

    /**
     * Work collected under the monitor and run after it is released: listener callbacks, log
     * lines, socket opens and closes.
     */
    private final class Effects {
        private final List<Runnable> actions = new ArrayList<>();

        void add(Runnable action) {
            actions.add(action);
        }

        void log(Level level, String message) {
            actions.add(() -> log.accept(level, message));
        }

        void run() {
            for (Runnable action : actions) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.accept(Level.WARNING, "UltiCloud: a connection callback failed: "
                            + e.getClass().getName());
                }
            }
        }
    }
}
