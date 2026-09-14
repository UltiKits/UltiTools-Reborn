package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;
import com.ultikits.ultitools.websocket.ExponentialBackoffStrategy;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Utility class for plugin initialization and WebSocket communication.
 * Handles account login, WebSocket connection, and message processing
 * for UltiPanel integration.
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class PluginInitiationUtils {

    /** {@code server_properties} is handled by its own dedicated manager — it is not a real config file path. */
    private static final String SERVER_PROPERTIES_FILE = "server_properties";

    /**
     * The WebSocket client belonging to {@link CloudSession#current()}, or {@code null} if none is
     * connected.
     * <p>
     * Before plan 16-08 Task 2 this was a static field ({@code panelWS}) set once by
     * {@code initWebsocket()} and read everywhere a handler needed to send a response. It is now
     * session-owned state (D-16): a session's WebSocket client dies with the session
     * ({@link CloudSession#invalidate()} disconnects and clears it), so "which client is current"
     * and "which session is current" can never disagree. Every call site that used to read the bare
     * {@code panelWS} field now calls this method instead — same read, same nullability, just
     * sourced from the session that actually owns the client.
     */
    private static UltiPanelWebSocketClient currentWebSocketClient() {
        return CloudSession.current().getWebSocketClient();
    }

    /**
     * Test-only: installs {@code client} as {@link CloudSession#current()}'s WebSocket client and
     * returns whatever was there before. Package-private, reached via reflection from
     * {@code CapabilityGateIndependenceTest} (a different package, {@code manager}) -- the same
     * idiom that test class already uses to reach {@link #initializeManagers()} and
     * {@link #onWebSocketOpened(UltiPanelWebSocketClient)}. Exists because {@code CloudSession}
     * itself is package-private and cannot be named from outside {@code utils}; this method is the
     * seam a cross-package test needs instead of reflecting into {@code CloudSession}'s own field
     * directly (which it could not even compile a reference to).
     *
     * @param client the client to install, or {@code null}
     * @return the client that was previously installed, or {@code null}
     */
    static UltiPanelWebSocketClient setWebSocketClientForTesting(UltiPanelWebSocketClient client) {
        CloudSession session = CloudSession.current();
        UltiPanelWebSocketClient previous = session.getWebSocketClient();
        session.setWebSocketClient(client);
        return previous;
    }

    /**
     * The inbound-message dispatch table: message {@code type} string to the {@link InboundHandlerEntry}
     * that serves it.
     * <p>
     * Replaces what used to be a 24-case {@code switch} inside {@link #handleInboundMessage}
     * (NPath complexity 1514 against a threshold of 200 — see issue #234's coupled complexity
     * finding). A switch multiplies independent path counts by the number of branches; a lookup
     * does not, so the paths through {@link #handleInboundMessage} are now bounded by its guards
     * rather than by how many message types exist. Built once, statically, and never mutated after
     * construction — see {@link #buildInboundHandlers()}.
     * <p>
     * Not module-visible and never will be: this is framework-internal routing for the fixed set of
     * panel protocol messages. Module-facing panel messaging is a separate, deliberately narrower
     * surface (EventBus broadcast plus a single-owner request/response responder) that a later phase
     * owns. A second module-visible dispatch mechanism grown out of this table would repeat a mistake
     * this repository already has twice, in its command-executor and GUI generations.
     */
    private static final Map<String, InboundHandlerEntry> INBOUND_HANDLERS =
            Collections.unmodifiableMap(buildInboundHandlers());

    /**
     * The elapsed-time threshold above which a {@link PanelMessageEvent} publish is considered
     * slow enough to warn about, in milliseconds. Set below one server tick (50ms at the nominal
     * 20 TPS) so a subscriber costing a visible fraction of the tick budget is named before
     * players feel it — this constant is the runtime half of D-24's mitigation; {@link
     * PanelMessageEvent}'s javadoc is the other half, stating the contract a reader sees before
     * ever hitting this warning at runtime.
     */
    private static final long SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS = 20L;

    // Both fields above are declared here — rather than at their original, method-adjacent
    // positions — so that all field declarations precede all methods (PMD
    // FieldDeclarationsShouldBeAtStartOfClass). Both initializers are static-method-call /
    // literal expressions with no dependency on declaration order relative to other members
    // (buildInboundHandlers() does not reference the removed cloud state-machine fields; see the
    // Phase 06 Codacy remediation commit for the verification).

    /**
     * Attempts to resume a previously-saved UltiCloud credential at startup: loads it from disk on
     * the current session, and -- unless the startup login is currently rate-limited -- activates
     * cloud features with it via {@link #loginWithToken(TokenEntity)}. Replaces what used to be
     * {@code UltiTools.attemptCloudLogin()}'s own inline body; moved here (plan 16-09, D-18) because
     * {@link #loginWithToken(TokenEntity)} itself had to become package-private (it accepted a
     * {@link TokenEntity} and was public), and {@code UltiTools} is a different package.
     *
     * @return {@code true} if a saved credential was found and successfully activated
     */
    public static boolean resumeSavedCredentialOnStartup() {
        try {
            TokenEntity savedToken = CloudSession.current().loadFromDisk();
            if (savedToken != null) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    "Found saved UltiCloud token, authenticating...");
                if (ApiRateLimiter.isAllowed("startup-login")) {
                    return loginWithToken(savedToken);
                }
                UltiTools.getInstance().getLogger().log(Level.INFO, "Skipping UltiCloud login (rate limited)");
            } else {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "No saved UltiCloud token found. Use /ulticloud login to authenticate.");
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "UltiCloud login failed (server will continue without cloud features): " + e.getMessage());
        }
        return false;
    }

    /**
     * Starts the token-refresh scheduler on the current session. Replaces the direct
     * {@code CloudAuthManager.startTokenRefreshScheduler()} call {@code UltiTools.onEnable()} used
     * to make (plan 16-09, D-17/D-18) -- {@link CloudSession} is package-private, so a different
     * package cannot reach it by name; this is the public seam instead.
     */
    public static void startTokenRefreshScheduler() {
        CloudSession.current().startTokenRefreshScheduler();
    }

    /**
     * Stops the current session's token-refresh scheduler and magic-link poller. Replaces the two
     * direct {@code CloudAuthManager.stopTokenRefreshScheduler()}/{@code stopPolling()} calls
     * {@code UltiTools.onDisable()} used to make (plan 16-09, D-17/D-18).
     */
    public static void stopCredentialSchedulers() {
        CloudSession.current().stopTokenRefreshScheduler();
        CloudSession.current().stopPolling();
    }

    /**
     * Login to UltiPanel using an existing token (from magic-link or saved token).
     * Registers or updates the server without needing username/password.
     * <p>
     * Package-private as of plan 16-09 (D-18) -- a public static method accepting a
     * {@link TokenEntity} is exactly the static bypass D-18's structural invariant forbids. Reached
     * from a different package only through {@link #resumeSavedCredentialOnStartup()}; within this
     * package, {@code CloudSession.completeMagicLinkLogin} calls it directly.
     *
     * @param existingToken the pre-authenticated token
     * @return true if server registration/update succeeded
     * @throws IOException if an I/O error occurs
     */
    static boolean loginWithToken(TokenEntity existingToken) throws IOException {
        String uuid = CommonUtils.getUltiToolsUUID();
        int port = org.bukkit.Bukkit.getServer().getPort();
        String domain = "";
        boolean ssl = true;

        try (Response uuidResponse = HttpRequestUtils.getServerByUUID(uuid, existingToken)) {
            if (uuidResponse.getStatus() == 404) {
                String serverName = org.bukkit.Bukkit.getServer().getName();
                if (serverName == null || serverName.trim().isEmpty()) {
                    serverName = "MC Server";
                }
                if (serverName.length() > 64) {
                    serverName = serverName.substring(0, 64);
                }
                try (Response registerResponse = HttpRequestUtils.registerServer(uuid, serverName, port, domain, ssl, existingToken)) {
                    if (!registerResponse.isOk()) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "Server registration failed: HTTP " + registerResponse.getStatus() + " - " + registerResponse.body());
                        return false;
                    }
                }
            } else if (uuidResponse.isOk()) {
                try (Response updateResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, existingToken)) {
                    if (!updateResponse.isOk()) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "Server update failed: HTTP " + updateResponse.getStatus() + " - " + updateResponse.body());
                        return false;
                    }
                }
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Failed to check server status: HTTP " + uuidResponse.getStatus() + " - " + uuidResponse.body());
                return false;
            }
        }
        return true;
    }

    /**
     * Initialize websocket on the current session.
     */
    public static void initWebsocket() throws IOException {
        initWebsocket(CloudSession.current());
    }

    /**
     * Initialize websocket on a specific session.
     * <p>
     * The callbacks registered below (onConnect, onReconnectExhausted) capture {@code session}
     * lexically, not {@code CloudSession.current()} read fresh when they eventually fire -- an
     * asynchronous handshake or a reconnect-exhaustion event must be checked against the session
     * that actually started it, not against whichever session happens to be current when the
     * callback runs (D-16). A logout landing in between must make this session's own gates refuse,
     * which they will, regardless of what has replaced it as current by then.
     *
     * @param session the session this WebSocket client belongs to
     */
    static void initWebsocket(CloudSession session) throws IOException {
        TokenEntity token = session.getToken();
        if (token == null || token.getAccess_token() == null) {
            throw new IOException("Cannot initialize WebSocket: no auth token available");
        }
        if (token.isExpired()) {
            throw new IOException("Cannot initialize WebSocket: auth token has expired");
        }

        // Deliberately does **not** mark the session enabled here.
        //
        // It used to flip the enabled flag on at this point, and that was wrong: reinitWebSocket
        // also reaches this line, so an in-flight reconnection could resurrect a state machine that
        // had just been turned off by logout — the two run on different threads, with a
        // token-refresh network call in between, and that window can be several seconds wide. As of
        // 16-08 Task 2 there is no separate flag to set: "enabled" is simply "this session is
        // current and not invalidated," which is already true or it would not have reached this
        // line's caller (activateCloudIfCurrent's own re-check).

        UltiPanelWebSocketClient client = getPanelWebsocketClient(token);
        session.setWebSocketClient(client);

        // Set the message handler
        client.setMessageHandler(PluginInitiationUtils::handleInboundMessage);

        // Set the on-connect-success handler
        client.setOnConnectHandler(() -> onWebSocketOpened(client));

        // Set the reconnect-exhausted handler — attempts to refresh the token and re-establish the
        // connection, on THIS session specifically (see this method's own javadoc).
        client.setOnReconnectExhaustedHandler(() -> reinitWebSocket(session));

        // Connect to the WebSocket server
        client.connect();
    }

    /**
     * The wiring performed once a handshake succeeds.
     * <p>
     * <b>The parameter is this handshake's own client; the method body never re-reads
     * {@link CloudSession#current()}'s client reference for the calls before
     * {@link #initializeManagers()}.</b> onOpen is an asynchronous callback: by the time it runs,
     * {@code disableCloud()} may already have closed and cleared the owning session's client
     * ({@code /ulticloud logout}, or the reconnect budget being exhausted — the latter runs on the
     * WebSocket thread), or {@code reinitWebSocket} may already have swapped it for a different
     * instance. Re-reading the session's client reference here would make
     * {@code subscribeToServer} / {@code uploadConfig} / {@code uploadServerProperties} all
     * silently hit a stale or null reference — {@link #initializeManagers()} itself is guarded by a
     * lock-held re-check (now the session's own monitor), but the code around it here is not.
     * <p>
     * Sending a message on an already-disconnected client is safe: {@code sendMessage} logs one
     * WARNING and returns when not connected. The real danger is a null reference, so what this fixes
     * is reference stability, not connection state.
     * <p>
     * Package-private rather than private — only so it can be tested. Triggering it otherwise would
     * require a real authenticated token and a real WebSocket handshake; same treatment as
     * {@link #handleInboundMessage}.
     */
    static void onWebSocketOpened(UltiPanelWebSocketClient client) {
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("Websocket已连接!"));

        // The handshake has genuinely succeeded — this is the only place where the phrase
        // "reconnection succeeded" actually holds. The outer budget is also only reset here —
        // resetting it inside reinitWebSocket would treat "a client was built" as success, and the
        // budget would never run out. See issue #181 / #223.
        onWebSocketConnected();
        UltiTools.getInstance().getLogger().log(Level.INFO,
            "WebSocket connected to UltiPanel");

        // Subscribe to the current server
        client.subscribeToServer(client.getServerId());

        // Initialize all managers
        initializeManagers();

        // Upload config
        uploadConfig(client);

        // Upload server properties to the cloud — gated by the SERVER_PROPERTIES capability switch (D-11/D-12)
        if (Capability.SERVER_PROPERTIES.isEnabled()) {
            uploadServerProperties(client);
        } else {
            logSkippedCapability(Capability.SERVER_PROPERTIES);
        }
    }

    /**
     * A dispatch-table entry pairing a handler with the {@link Capability} that must be enabled
     * before it runs (D-10), and with which side records that decision's verdict in the
     * {@link RemoteActionLog} (CR-01, 06-REVIEW.md).
     * <p>
     * Exposes exactly two static factories and no capability-free, verdict-recorder-free
     * construction path — this is the whole point of D-10: {@link #of(Capability, VerdictRecorder,
     * BiConsumer)} and {@link #resolved(Function, VerdictRecorder, BiConsumer)} are the only ways
     * to build an entry, both take these arguments in fixed positions, and neither has a shorter
     * overload or a default.
     * <p>
     * <b>Precisely stated (corrected per IN-01, 06-REVIEW.md — an earlier revision of this javadoc
     * overstated this):</b> omitting either argument from a call site IS a genuine {@code javac}
     * compile error — there is no shorter overload to fall back to. Passing a {@code null}
     * capability, resolver, or {@link VerdictRecorder}, however, compiles cleanly (a
     * reference-typed parameter accepts {@code null} at the language level) and is instead rejected
     * by an {@link IllegalArgumentException} thrown from {@link #of}/{@link #resolved} the moment
     * {@link #buildInboundHandlers()} runs — at class-initialization time, before the server
     * finishes starting, not by the compiler. Together the two guarantees still mean a new message
     * type cannot silently ship ungated or with an undeclared verdict recorder — the argument slot
     * is mandatory (compile-time) and a {@code null} value fails immediately and loudly
     * (class-load-time) — but the {@code null}-rejection half is not literally a compile error.
     * <p>
     * {@link #of(Capability, VerdictRecorder, BiConsumer)} covers the 23 entries whose capability is
     * fixed by the message {@code type} alone; {@link #resolved(Function, VerdictRecorder,
     * BiConsumer)} covers {@code file_operation}, the one entry whose capability depends on the
     * message's {@code operation} field rather than its {@code type}.
     */
    static final class InboundHandlerEntry {
        private final Capability capability;
        private final Function<JsonObject, Capability> resolver;
        private final VerdictRecorder verdictRecorder;
        private final BiConsumer<JsonObject, JsonObject> handler;

        private InboundHandlerEntry(Capability capability, Function<JsonObject, Capability> resolver,
                                     VerdictRecorder verdictRecorder, BiConsumer<JsonObject, JsonObject> handler) {
            this.capability = capability;
            this.resolver = resolver;
            this.verdictRecorder = verdictRecorder;
            this.handler = handler;
        }

        /**
         * An entry whose capability is a fixed constant.
         *
         * @param capability      the required capability — use {@link Capability#NONE} for
         *                        protocol-level and echo messages that carry no operator-facing
         *                        policy
         * @param verdictRecorder which side records the enabled-branch verdict — see
         *                        {@link VerdictRecorder}
         * @param handler         the handler to invoke once the gate clears
         * @return the entry
         */
        static InboundHandlerEntry of(Capability capability, VerdictRecorder verdictRecorder,
                                       BiConsumer<JsonObject, JsonObject> handler) {
            if (capability == null) {
                throw new IllegalArgumentException("capability must not be null — declare Capability.NONE explicitly");
            }
            if (verdictRecorder == null) {
                throw new IllegalArgumentException("verdictRecorder must not be null — declare GATE or HANDLER");
            }
            return new InboundHandlerEntry(capability, null, verdictRecorder, handler);
        }

        /**
         * An entry whose capability depends on the inbound message's own {@code data} — the
         * {@code file_operation} case, whose true capability depends on the {@code operation} field
         * (D-10's resolver case, D-09).
         *
         * @param resolver        a function from the message's {@code data} to the
         *                        {@link Capability} it requires
         * @param verdictRecorder which side records the enabled-branch verdict — see
         *                        {@link VerdictRecorder}
         * @param handler         the handler to invoke once the gate clears
         * @return the entry
         */
        static InboundHandlerEntry resolved(Function<JsonObject, Capability> resolver,
                                             VerdictRecorder verdictRecorder,
                                             BiConsumer<JsonObject, JsonObject> handler) {
            if (resolver == null) {
                throw new IllegalArgumentException("resolver must not be null — declare a Capability.of(...) entry instead");
            }
            if (verdictRecorder == null) {
                throw new IllegalArgumentException("verdictRecorder must not be null — declare GATE or HANDLER");
            }
            return new InboundHandlerEntry(null, resolver, verdictRecorder, handler);
        }

        /**
         * Resolves this entry's required capability against one message's {@code data}.
         *
         * @param data the message's {@code data} object, possibly {@code null}
         * @return the required {@link Capability}
         */
        Capability resolveCapability(JsonObject data) {
            return capability != null ? capability : resolver.apply(data);
        }

        BiConsumer<JsonObject, JsonObject> getHandler() {
            return handler;
        }

        /**
         * Whether this entry's own handler already records its {@link RemoteActionLog} verdict —
         * see {@link VerdictRecorder#HANDLER}.
         *
         * @return {@code true} if the handler records its own verdict, so
         *         {@link #dispatchWithCapabilityGate} must not record a second, blanket entry
         */
        boolean recordsOwnVerdict() {
            return verdictRecorder == VerdictRecorder.HANDLER;
        }
    }

    /**
     * Which side of a capability-gated dispatch records the {@link RemoteActionLog} verdict for
     * the enabled branch (CR-01, 06-REVIEW.md).
     * <p>
     * Exactly two entries — {@code execute_command} and {@code file_operation} — invoke a handler
     * that performs its own, independent, finer-grained {@code AccessDecision} check
     * ({@code CommandExecutionManager#isCommandAllowed}/{@code FileOperationManager#isPathAllowed})
     * and records its own verdict from that check; every other capability-gated entry has no
     * second policy layer to conflict with. Before this enum existed,
     * {@code dispatchWithCapabilityGate} recorded a blanket {@code ALLOWED} entry on every enabled
     * branch regardless of which case it was, producing a contradictory second log line — a real
     * {@code DENIED} from the handler's own check immediately followed by a false {@code ALLOWED}
     * from the gate — for every blocklisted command and every credential/out-of-root file request.
     * A required field (verified by inspecting every entry's handler for its own
     * {@link RemoteActionLog} write) is what stops a newly added entry from silently repeating that
     * mistake, rather than a defaulted or inferred value. Precisely: omitting the argument is a
     * genuine compile error; a {@code null} value compiles but is rejected immediately at
     * class-initialization time — see {@link InboundHandlerEntry}'s own javadoc for the exact
     * boundary between the two (IN-01, 06-REVIEW.md).
     */
    enum VerdictRecorder {
        /**
         * {@link #dispatchWithCapabilityGate} records the {@link RemoteActionLog.Verdict#ALLOWED}
         * entry on the enabled branch — the default shape for an entry with no second policy
         * layer.
         */
        GATE,
        /**
         * The handler records its own verdict from its own, finer-grained {@code AccessDecision}
         * check — {@link #dispatchWithCapabilityGate} must not also record one, or the log gets
         * two contradictory lines for one request.
         */
        HANDLER
    }

    /**
     * Builds {@link #INBOUND_HANDLERS}. Each entry invokes exactly the same target its former
     * {@code case} label invoked — this method is the byte-for-byte routing record of the switch it
     * replaces, not a redesign of it. {@code log_stream} and {@code log_stream_control} share one
     * {@link BiConsumer} instance, preserving the fall-through the two case labels used to express.
     *
     * @return a table from message {@code type} to the {@link InboundHandlerEntry} that serves it
     */
    private static Map<String, InboundHandlerEntry> buildInboundHandlers() {
        Map<String, InboundHandlerEntry> handlers = new HashMap<>();

        // System-level base messages — protocol-layer/echo messages, explicitly declared
        // Capability.NONE (D-10): never blocked, never recorded. The NONE branch never reaches
        // recordAction, so VerdictRecorder's value has no effect here; GATE is declared uniformly.
        handlers.put("ping",
                InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE, (message, data) -> handlePing(message)));
        handlers.put("pong",
                InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE, (message, data) -> handlePong(data)));
        handlers.put("subscribe", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleSubscribe(data)));
        handlers.put("unsubscribe", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleUnsubscribe(data)));
        handlers.put("notification", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleNotification(data)));
        handlers.put("error",
                InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE, (message, data) -> handleError(data)));

        // Server monitoring messages — the handler performs no second-layer decision, the gate
        // records ALLOWED (VerdictRecorder.GATE).
        handlers.put("server_status", InboundHandlerEntry.of(Capability.MONITORING, VerdictRecorder.GATE,
                (message, data) -> handleServerStatusRequest(data)));
        handlers.put("plugin_list", InboundHandlerEntry.of(Capability.MONITORING, VerdictRecorder.GATE,
                (message, data) -> handlePluginListRequest(data)));
        handlers.put("player_event", InboundHandlerEntry.of(Capability.PLAYER_EVENTS, VerdictRecorder.GATE,
                (message, data) -> handlePlayerEvent(data)));
        handlers.put("metrics_data", InboundHandlerEntry.of(Capability.MONITORING, VerdictRecorder.GATE,
                (message, data) -> handleMetricsRequest(data)));

        // Operation-control messages — execute_command's handler performs its own isCommandAllowed()
        // second-layer decision and records its own result (CommandExecutionManager.executeCommand),
        // so it declares VerdictRecorder.HANDLER (CR-01).
        handlers.put("execute_command", InboundHandlerEntry.of(Capability.COMMANDS, VerdictRecorder.HANDLER,
                (message, data) -> UltiTools.getInstance().getCommandExecutionManager().executeCommand(data)));
        handlers.put("command_result", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleCommandResult(data)));
        // file_operation's capability depends on data.operation, not a constant — D-10's resolver
        // scenario (D-09). The handler performs its own isPathAllowed() second-layer decision and
        // records its own result (FileOperationManager.recordFileDecision), so it likewise declares
        // VerdictRecorder.HANDLER (CR-01).
        handlers.put("file_operation", InboundHandlerEntry.resolved(
                PluginInitiationUtils::resolveFileOperationCapability, VerdictRecorder.HANDLER,
                (message, data) -> UltiTools.getInstance().getFileOperationManager().handleFileOperation(data)));
        handlers.put("file_operation_result", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleFileOperationResult(data)));

        // Data-stream messages — log_stream and log_stream_control share the same handler; this is
        // the equivalent of the fall-through the two former case labels used to express. The handler
        // performs no second-layer decision.
        BiConsumer<JsonObject, JsonObject> logStreamHandler =
                (message, data) -> UltiTools.getInstance().getLogStreamManager().handleLogStreamMessage(data);
        handlers.put("log_stream", InboundHandlerEntry.of(Capability.LOGS, VerdictRecorder.GATE, logStreamHandler));
        handlers.put("log_stream_control",
                InboundHandlerEntry.of(Capability.LOGS, VerdictRecorder.GATE, logStreamHandler));
        // backup_operation is a pure logging placeholder today, but its declared intent is a
        // file-producing operation — so it is declared FILE_WRITE on the stricter side rather than
        // waiting to revisit the declaration once the stub implementation lands. The handler performs
        // no second-layer decision.
        handlers.put("backup_operation", InboundHandlerEntry.of(Capability.FILE_WRITE, VerdictRecorder.GATE,
                (message, data) -> handleBackupOperation(data)));
        handlers.put("backup_progress", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) -> handleBackupProgress(data)));

        // Config-management messages — the handler performs no second-layer decision.
        handlers.put("upload_config", InboundHandlerEntry.of(Capability.FILE_WRITE, VerdictRecorder.GATE,
                (message, data) -> handleConfigUpload(data)));
        handlers.put("update_config", InboundHandlerEntry.of(Capability.FILE_WRITE, VerdictRecorder.GATE,
                (message, data) -> handleConfigUpdate(data)));
        handlers.put("server_properties",
                InboundHandlerEntry.of(Capability.SERVER_PROPERTIES, VerdictRecorder.GATE, (message, data) -> {
                    if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                        UltiTools.getInstance().getServerPropertiesManager().handleServerProperties(data);
                    }
                }));
        handlers.put("server_properties_result", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE,
                (message, data) ->
                // Response from this plugin forwarded back by DO — ignore silently
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received server_properties_result echo — ignoring")));

        // Magic link auth messages (completion handled by HTTP polling in UltiLogin)
        handlers.put("auth_complete", InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE, (message, data) ->
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received auth_complete message: " + (data != null ? data.toString() : "null"))));
        handlers.put("magic_link_response",
                InboundHandlerEntry.of(Capability.NONE, VerdictRecorder.GATE, (message, data) ->
                        UltiTools.getInstance().getLogger().log(Level.FINE,
                                "Received magic_link_response message: " + (data != null ? data.toString() : "null"))));

        return handlers;
    }

    /**
     * Resolves {@code file_operation}'s required capability from the message's {@code operation}
     * field (D-09, D-10's Pitfall 4). Delegates to {@link #resolveFileOperationCapability(String)}.
     *
     * @param data the message's {@code data} object, possibly {@code null}
     * @return the required capability
     */
    private static Capability resolveFileOperationCapability(JsonObject data) {
        String operation = data != null ? readString(data, "operation") : null;
        return resolveFileOperationCapability(operation);
    }

    /**
     * The single {@code operation} name to {@link Capability} mapping, shared between this class's
     * D-10 dispatch-table resolver above and {@code FileOperationManager}'s own action-log
     * recording (D-22, Plan 06-04 Task 1) — so the two mappings cannot drift apart. {@code list}
     * resolves to {@link Capability#FILE_READ} — listing is reading. An unrecognised or absent
     * operation also resolves to {@link Capability#FILE_READ}, the most-permitted of the three, so
     * an unknown verb reaching the dispatch table is still gated and still reaches
     * {@code handleFileOperation}'s own unsupported-operation branch.
     *
     * @param operation the {@code operation} field's value, possibly {@code null}
     * @return the required capability
     */
    public static Capability resolveFileOperationCapability(String operation) {
        if ("write".equals(operation)) {
            return Capability.FILE_WRITE;
        }
        if ("delete".equals(operation)) {
            return Capability.FILE_DELETE;
        }
        return Capability.FILE_READ;
    }

    /**
     * Package-private accessor for {@link #INBOUND_HANDLERS}, exposed only so
     * {@code PluginInitiationUtilsDispatchTableTest} can assert the table's key set and entry
     * identities without duplicating {@link #buildInboundHandlers()}'s routing record in a second
     * place. Not a registration point — the returned map is already unmodifiable.
     *
     * @return the unmodifiable inbound dispatch table
     */
    static Map<String, InboundHandlerEntry> inboundDispatchTable() {
        return INBOUND_HANDLERS;
    }

    /**
     * Whether {@code messageType} is one of the framework's own {@link #INBOUND_HANDLERS} entries
     * — the single source {@code PanelResponderRegistry.registerResponder} consults before letting
     * a module claim a message type (D-26, WIRE-16, Plan 06-08 Task 1). Deliberately a separate,
     * narrower predicate rather than widening {@link #inboundDispatchTable()}'s visibility: that
     * accessor's own javadoc says it is test-only and not a registration point, and a boolean
     * membership check is exactly the narrower thing a registration point actually needs — it
     * cannot read, mutate, or iterate the table itself.
     * <p>
     * Exact {@code String} key membership, matching {@link #INBOUND_HANDLERS}'s own
     * {@code HashMap} key semantics: no case folding, no Unicode normalization.
     *
     * @param messageType the message type to check, possibly {@code null}
     * @return {@code true} if the framework's own dispatch table already serves this exact type
     */
    public static boolean isFrameworkOwnedType(String messageType) {
        return messageType != null && INBOUND_HANDLERS.containsKey(messageType);
    }

    /**
     * Handles an inbound WebSocket message dispatched by the panel.
     * <p>
     * Extracted out of {@code initWebsocket()}'s lambda for the sole purpose of letting it be called
     * directly by a unit test: it used to be an anonymous lambda passed to
     * {@code setMessageHandler}, and constructing that required a real authenticated token and a
     * real WebSocket client, so this path's malformed-input handling had no test coverage at all.
     * See issue #234.
     * <p>
     * Package-private rather than public — it is not a public API, only testable this way.
     *
     * @param message the message dispatched by the panel, may be {@code null}
     */
    static void handleInboundMessage(JsonObject message) {
        if (message == null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[WebSocket消息处理] 收到 null 消息，已忽略");
            return;
        }

        // type and data share the same guard. This used to be
        // message.get("type").getAsString(): with a missing type field, get() returns null and
        // .getAsString() immediately throws an NPE — and that call sat outside the try, so the
        // catch below could not catch it.
        //
        // That NPE does not interrupt the receive loop — UltiPanelWebSocketClient.onMessage wraps
        // messageHandler.accept in its own try. But it would be logged as "WebSocket message parse
        // failed" when parsing had in fact succeeded: the message was silently dropped, the
        // diagnostic pointed in the wrong direction, and that call site only passed
        // e.getMessage(), no stack trace.
        //
        // Uses isJsonPrimitive rather than !isJsonNull: the latter only guards against a JSON null
        // and not against type being an object or array — in that case getAsString() throws
        // UnsupportedOperationException. A non-primitive type belongs to the same class of
        // malformed input as a missing field, a JSON null, or an empty string, and should take the
        // same WARNING branch rather than being logged as SEVERE under "an error occurred while
        // handling the message."
        //
        // The superseded WIRE-17 dispatch cluster (deleted in 6.3.0, GEN-11) used !isJsonNull()
        // for this same check, which only guards a JSON null and not a non-primitive type — this
        // path deliberately does not repeat that gap.
        String type = null;
        JsonObject data = null;
        // Tracks whether this message should reach PanelMessageEvent subscribers (WIRE-16).
        // Stays false — the safe default — unless the dispatch below explicitly earns it: an
        // entry-less (unknown) type earns it after its warning, and dispatchWithCapabilityGate's
        // return value earns it for a known type (true for Capability.NONE and for an enabled
        // capability, false for a denied one). Carrying the gate's own outcome here means the
        // gate and the publish can never disagree — there is no second, independent check.
        boolean shouldPublishEvent = false;
        try {
            if (message.has("type") && message.get("type").isJsonPrimitive()) {
                type = message.get("type").getAsString();
            }
            if (type == null || type.isEmpty()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("[WebSocket消息处理] 消息缺少有效的 type 字段，已忽略: %s",
                        new Gson().toJson(message)));
                // Early return — the type never resolved, so there is nothing a subscriber could
                // filter on. This also means the trailing publish call below is never reached.
                return;
            }

            data = message.has("data") && message.get("data").isJsonObject()
                ? message.getAsJsonObject("data") : null;

            // Log that the received message has started processing
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket消息处理] 类型: %s, 开始处理", type));

            // Lookup replaces the former 24-case switch — see INBOUND_HANDLERS. Every entry
            // invokes the same target its former case label invoked; an absent entry is the same
            // "unknown type" outcome the former default branch produced.
            InboundHandlerEntry entry = INBOUND_HANDLERS.get(type);
            if (entry != null) {
                shouldPublishEvent = dispatchWithCapabilityGate(type, message, data, entry);
            } else {
                // Module-owned responders are served from this exact branch — the same lookup
                // that serves the framework's own 24 types — rather than a second dispatch
                // mechanism (01-CONTEXT D-10/D-11, WIRE-16, Plan 06-08). A registered responder
                // earns its own dispatch and reply; a genuinely unknown type keeps today's
                // behaviour unchanged (one warning, no reply, to avoid feedback loops with the
                // server).
                PanelResponderRegistry responderRegistry = UltiTools.getInstance().getPanelResponderRegistry();
                if (responderRegistry != null && responderRegistry.hasResponder(type)) {
                    dispatchToResponder(type, data, responderRegistry);
                } else {
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        String.format("未知的消息类型: %s，消息内容: %s", type, new Gson().toJson(message)));
                    // Don't send error responses to avoid feedback loops with server
                }
                // Unknown to the framework's own dispatch table is exactly the case WIRE-16
                // exists to serve — a module's own responder for a type the framework does not
                // own. No capability gate applies (there is no entry to resolve one from), so
                // this is unconditionally publishable, whether or not a responder actually
                // served it.
                shouldPublishEvent = true;
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.SEVERE,
                String.format("处理消息类型 %s 时发生错误: %s", type, e.getMessage()), e);
            // Don't send error responses to avoid feedback loops with server
            // shouldPublishEvent stays at its default (false): an exception mid-dispatch means
            // the framework cannot say the message was actually handled, so this conservatively
            // does not publish rather than guessing.
        }

        // Log that message processing has completed
        UltiTools.getInstance().getLogger().log(Level.FINE,
            String.format("[WebSocket消息处理] 类型: %s, 处理完成", type));

        // One added statement at the end of the bridge (D-29, issue #237, WIRE-16). Appended
        // rather than inserted: removing this call must leave the 24 pre-existing message types
        // working exactly as they do today. Only reached when type resolved (the early return
        // above skips it for a malformed type) and shouldPublishEvent was earned above.
        if (shouldPublishEvent) {
            publishPanelMessageEvent(type, message, data);
        }
    }

    /**
     * Dispatches {@code type} to its registered responder and, once the registry's returned future
     * settles, sends exactly one reply through the same client accessor the other outbound helpers
     * use — {@code panelWS.sendMessage(...)}, matching {@code sendCapabilityRefusal}'s and
     * {@code FileOperationManager#sendFileOperationResult}'s existing pattern (WIRE-16, D-27).
     * <p>
     * No Bukkit main-thread hop here, deliberately: unlike {@link PanelMessageEvent}'s publish
     * (which must run on the main thread because a subscriber may touch Bukkit API), sending a
     * reply over the WebSocket client is plain network I/O — the same off-main-thread pattern
     * {@code CommandExecutionManager}/{@code FileOperationManager} already use for their own
     * outbound replies.
     * <p>
     * The reply always carries the message type and the request's {@code requestId} (echoed from
     * {@code data}), and either the responder's resolved {@link JsonObject} or an {@code error}
     * member naming the failure — {@link PanelResponderRegistry#dispatch} guarantees its returned
     * future always settles one way or the other, so this method never has to guess. When the
     * request carried no {@code requestId}, the reply is logged rather than sent: the panel has no
     * way to correlate an uncorrelated reply, matching {@link #sendCapabilityRefusal}'s same
     * reasoning for {@code commandId}/{@code operationId}.
     *
     * @param type     the message type, already confirmed to have a registered responder
     * @param data     the message's {@code data} object, possibly {@code null}
     * @param registry the registry to dispatch through
     */
    private static void dispatchToResponder(String type, JsonObject data, PanelResponderRegistry registry) {
        String requestId = data != null ? readString(data, "requestId") : null;
        registry.dispatch(type, data, requestId).whenComplete((result, throwable) -> {
            if (requestId == null || requestId.isEmpty()) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    String.format("Responder reply for type '%s' not sent — request carried no requestId", type));
                return;
            }
            JsonObject payload = throwable != null ? new JsonObject() : result;
            payload.addProperty("requestId", requestId);
            if (throwable != null) {
                payload.addProperty("error", rootCauseMessage(throwable));
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", type);
            response.add("data", payload);
            if (currentWebSocketClient() != null) {
                response.addProperty("serverId", currentWebSocketClient().getServerId());
                currentWebSocketClient().sendMessage(response);
            } else {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Responder reply for type '" + type + "' not sent — no WebSocket client connected");
            }
        });
    }

    /**
     * The deepest non-null message on {@code throwable}'s cause chain, falling back to the
     * throwable's own class name when every message is {@code null} — a bare
     * {@code NullPointerException} carries no message at all, and an empty {@code error} field
     * would tell the panel operator nothing.
     *
     * @param throwable the throwable to describe
     * @return a human-readable description, never {@code null}
     */
    // PMD.CompareObjectsWithEquals: deliberate reference-identity check, not a false economy.
    // This walks a cause chain looking for a self-referential cycle (getCause() returning the
    // same instance) — reference identity is precisely what must be tested here, and
    // Throwable does not override equals(), so .equals() would behave identically while
    // *saying* something the code does not mean (value equality, not "is this the same object
    // I started from").
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static String rootCauseMessage(Throwable throwable) {
        Throwable deepest = throwable;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        return deepest.getMessage() != null ? deepest.getMessage() : deepest.getClass().getSimpleName();
    }

    /**
     * Bridges an inbound panel message the framework has already handled onto the module-facing
     * {@link EventBus} (WIRE-16). This is the single publish site — see the dispatch-table call
     * site in {@link #handleInboundMessage} for the only place this is invoked.
     * <p>
     * {@link EventBus#publishAsync} was considered and rejected: it submits to an async worker
     * pool and never reaches the main thread, so it does not address Paper's AsyncCatcher at
     * all — it only keeps the WebSocket I/O thread unblocked. A Minecraft module's handler
     * touches Bukkit API by definition, so the real choice here was main-thread versus
     * not-main-thread, not sync-dispatch versus async-dispatch; only
     * {@code Bukkit.getScheduler().runTask(...)} puts a handler on the main thread. The whole
     * helper body is wrapped in a catch so a missing scheduler (no Bukkit server booted, as in a
     * plain unit test) or a missing {@link EventBus} can never break the inbound message path —
     * both are logged no-ops.
     *
     * @param type    the resolved message type
     * @param message the full inbound envelope
     * @param data    the message's {@code data} object, possibly {@code null}
     */
    private static void publishPanelMessageEvent(String type, JsonObject message, JsonObject data) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            EventBus eventBus = instance.getEventBus();
            if (eventBus == null) {
                return;
            }
            Bukkit.getScheduler().runTask(instance, () -> {
                // Two long reads and a comparison on the fast path — no allocation, no logging,
                // until the slow branch below is actually taken.
                long startNanos = System.nanoTime();
                eventBus.publish(new PanelMessageEvent(type, data, message));
                long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
                if (elapsedMillis > SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS) {
                    // Times the whole publish, not an individual handler: EventBus.publish
                    // iterates its subscriber list internally and this bridge cannot see inside
                    // that loop without changing EventBus, a shared class this plan does not
                    // touch. This warning can therefore only say that some subscriber to this
                    // event type is slow — never which one, and it fires once per slow publish
                    // regardless of how many subscribers contributed to the elapsed time.
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        String.format("[PanelMessageEvent] Subscriber(s) to type '%s' took %dms "
                            + "to run (threshold %dms) — a slow handler on the main thread can "
                            + "drag server tick rate",
                            type, elapsedMillis, SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS));
                }
            });
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[PanelMessageEvent] Failed to publish event for type " + type, e);
        }
    }

    /**
     * The single enforcement point for every inbound capability (D-10). Resolves the entry's
     * required capability against {@code data}; {@link Capability#NONE} runs the handler with no
     * check and no action-log entry. Otherwise: enabled runs the handler and, unless the entry's
     * own handler already records its verdict ({@link InboundHandlerEntry#recordsOwnVerdict()},
     * CR-01), records one {@link RemoteActionLog.Verdict#ALLOWED} entry; disabled sends one
     * {@code capability_denied} reply and records one {@link RemoteActionLog.Verdict#DENIED} entry
     * — the handler is never invoked on the denied path, so there is only ever one writer there.
     * <p>
     * {@code execute_command} and {@code file_operation} are the two entries whose handler performs
     * its own, finer-grained {@code AccessDecision} check and records its own verdict from that
     * check — recording a second, blanket {@code ALLOWED} entry here for those two would produce a
     * contradictory second log line (see 06-REVIEW.md CR-01) for every blocklisted command and
     * every credential/out-of-root file request.
     *
     * @param type    the message type, used for the action-log {@code action} and the refusal payload
     * @param message the full inbound message
     * @param data    the message's {@code data} object, possibly {@code null}
     * @param entry   the dispatch-table entry that serves this type
     * @return whether the message should also reach {@link PanelMessageEvent} subscribers —
     *         {@code true} for {@link Capability#NONE} and for an enabled capability, {@code
     *         false} for a denied one. The caller carries this straight into the publish decision
     *         so the gate and the publish can never disagree (see {@link #handleInboundMessage}).
     */
    private static boolean dispatchWithCapabilityGate(String type, JsonObject message, JsonObject data,
                                                     InboundHandlerEntry entry) {
        Capability capability = entry.resolveCapability(data);
        if (capability == Capability.NONE) {
            entry.getHandler().accept(message, data);
            return true;
        }
        if (capability.isEnabled()) {
            entry.getHandler().accept(message, data);
            if (!entry.recordsOwnVerdict()) {
                recordAction(capability, type, data, RemoteActionLog.Verdict.ALLOWED, null);
            }
            return true;
        }
        sendCapabilityRefusal(type, data, capability);
        recordAction(capability, type, data, RemoteActionLog.Verdict.DENIED, capability.refusalMessage());
        return false;
    }

    /**
     * Sends one {@code capability_denied} outbound message naming the config key, the config file,
     * the refusal reason, and echoing whichever correlation id the inbound message carried
     * ({@code commandId}, {@code operationId} or {@code requestId}) so the panel can correlate the
     * refusal with the request that caused it. Not reusing the existing {@code error} message type
     * — see {@link #handleInboundMessage}'s own comment on why unsolicited {@code error} replies are
     * avoided on this path. A logged no-op when no client is connected.
     *
     * @param type       the inbound message type that was refused
     * @param data       the message's {@code data} object, possibly {@code null}
     * @param capability the capability that refused it
     */
    private static void sendCapabilityRefusal(String type, JsonObject data, Capability capability) {
        if (currentWebSocketClient() == null) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Capability refusal for " + type + " not sent — no WebSocket client connected");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("capability", capability.name());
        payload.addProperty("configKey", capability.getConfigPath());
        payload.addProperty("configFile", "plugins/UltiTools/config.yml");
        payload.addProperty("reason", capability.refusalMessage());

        if (data != null) {
            copyIfPresent(data, payload, "commandId");
            copyIfPresent(data, payload, "operationId");
            copyIfPresent(data, payload, "requestId");
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "capability_denied");
        response.add("data", payload);
        response.addProperty("serverId", currentWebSocketClient().getServerId());
        currentWebSocketClient().sendMessage(response);
    }

    /** Copies {@code field} from {@code source} to {@code target} only when present and non-null. */
    private static void copyIfPresent(JsonObject source, JsonObject target, String field) {
        String value = readString(source, field);
        if (value != null) {
            target.addProperty(field, value);
        }
    }

    /**
     * Records one action-log entry for a capability-gated inbound message. A {@code null}
     * {@code UltiTools.getInstance().getRemoteActionLog()} is a silent no-op — the existing
     * inbound-message tests mock {@code UltiTools} and return null for it.
     */
    private static void recordAction(Capability capability, String type, JsonObject data,
                                      RemoteActionLog.Verdict verdict, String reason) {
        RemoteActionLog log = UltiTools.getInstance().getRemoteActionLog();
        if (log == null) {
            return;
        }
        String action = resolveActionLogAction(type, data);
        String target = resolveActionLogTarget(type, data);
        String actor = resolveActor(data);
        RemoteActionLog.Entry entry = verdict == RemoteActionLog.Verdict.ALLOWED
                ? RemoteActionLog.Entry.allowed(capability, action, target, actor)
                : RemoteActionLog.Entry.denied(capability, action, target, actor, reason);
        log.record(entry);
    }

    /** The action-log {@code action} field — the message type, extended with the resolved sub-operation for {@code file_operation}. */
    private static String resolveActionLogAction(String type, JsonObject data) {
        if ("file_operation".equals(type) && data != null) {
            String operation = readString(data, "operation");
            if (operation != null) {
                return type + ":" + operation;
            }
        }
        return type;
    }

    /** The action-log {@code target} field — the command text, file path, or message type otherwise. */
    private static String resolveActionLogTarget(String type, JsonObject data) {
        if (data == null) {
            return type;
        }
        if ("execute_command".equals(type)) {
            String command = readString(data, "command");
            return command != null ? command : type;
        }
        if ("file_operation".equals(type)) {
            String path = readString(data, "path");
            return path != null ? path : type;
        }
        return type;
    }

    /**
     * The action-log {@code actor} field — the inbound {@code executor} field verbatim, or the
     * literal {@code "panel"} when absent. The framework cannot attribute a remote command to an
     * individual panel operator today (see {@link RemoteActionLog.Entry}'s javadoc), so this never
     * invents a per-operator identity.
     */
    private static String resolveActor(JsonObject data) {
        String executor = data != null ? readString(data, "executor") : null;
        return executor != null ? executor : "panel";
    }

    /**
     * Wires all WebSocket managers up to the current connection, on {@link CloudSession#current()}.
     * <p>
     * This method hangs off {@code onConnectHandler}, and an in-flight handshake can still land
     * after {@code /ulticloud logout}. Without a guard, the listeners {@code disableCloud()} just
     * tore down would be reinstalled verbatim by this late-arriving onOpen — the exact same "no one
     * owns the decision" defect from #181/#223, resurfacing in a different place.
     * <p>
     * As of 16-08 Task 2 the actual wiring and its re-check-while-locked guard both live on
     * {@link CloudSession} itself (its own intrinsic lock replaces the former global
     * the former global lifecycle lock — see {@link CloudSession#initializeManagers()}). This method is
     * now a one-line delegator, kept package-private (not private) only so it can still be reached
     * by name via reflection from tests in other packages (see the two review rounds on PR #264 for
     * why the guard exists at all).
     */
    static void initializeManagers() {
        CloudSession.current().initializeManagers();
    }

    /**
     * Logs one INFO entry for a skipped outbound capability, naming which capability and which
     * config key caused the skip.
     * <p>
     * This log line matters especially for {@link Capability#MONITORING}: {@code sendBatchUpdate}
     * firing every 5 seconds is the panel's sole basis for deciding "is the server online" — turning
     * monitoring off makes an upgraded server show as offline on the panel, which is the worst shape
     * a failure can take, because the symptom points operators in the wrong direction (they go check
     * the network and the token, not the config). D-08 already
     * set monitoring's out-of-the-box default to enabled as the first layer of mitigation; this log
     * line is the second.
     * <p>
     * Package-private (not private) as of 16-08 Task 2 -- {@link CloudSession}'s own
     * {@code wireManagers()} calls this too, now that the wiring logic lives there.
     *
     * @param capability the capability that was skipped
     */
    static void logSkippedCapability(Capability capability) {
        UltiTools.getInstance().getLogger().log(Level.INFO, String.format(
                "[UltiPanel] Skipped %s wiring — capability disabled (%s)",
                capability.name(), capability.getConfigPath()));
    }

    /**
     * Handles a config update.
     *
     * <p>This path used to disagree with the panel in three places, and each failure was silent
     * (issue #236): the panel sends content in {@code data.configData}, and this read
     * {@code data.config}; the panel names the file with {@code data.fileName}, and this never read
     * it except for {@code server_properties}; the panel does not send {@code requestId}, and this
     * used whether {@code requestId} was present as the test for "is this a request" — when it was
     * missing, the code just logged one {@code Level.FINE} line and dropped the message, and
     * {@code FINE} does not print under the default log configuration. So the panel got an HTTP 200,
     * nothing changed on the server, and neither side reported an error.
     *
     * <p>The test is now "is there config content": no content means this is an echo/acknowledgement,
     * content means it is a request, and a missing {@code requestId} is still applied — it just logs
     * a WARNING explaining that the result cannot be reported back.
     *
     * <p>Package-private rather than private — only so it can be tested.
     */
    static void handleConfigUpdate(JsonObject data) {
        if (data == null) {
            return;
        }

        String fileName = readString(data, "fileName");
        String requestId = readString(data, "requestId");
        String configContent = readConfigContent(data);

        // server_properties's "get" request: no content is still a legitimate request, not an echo.
        if (SERVER_PROPERTIES_FILE.equals(fileName) && configContent == null) {
            ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
            if (spm != null) {
                JsonObject spData = new JsonObject();
                spData.addProperty("action", "get");
                spm.handleServerProperties(spData);
            }
            return;
        }

        // No config content = an acknowledgement bounced back by the forwarding layer, or this
        // server's own echo. This is the one case where doing nothing is still normal, so it stays
        // at FINE.
        if (configContent == null) {
            if (data.has("message") && !data.get("message").isJsonNull()) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        String.format("收到服务器配置更新确认: %s", data.get("message").getAsString()));
            } else {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "收到不含配置内容的 update_config 消息，按回声处理");
            }
            return;
        }

        if (requestId == null) {
            // This used to just return here. A missing requestId is the counterpart's protocol
            // defect, not "this message need not be handled" — treating it as the latter dresses
            // the defect up as a normal path.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "收到不含 requestId 的配置更新请求，仍会应用，但无法向面板回报结果");
        }

        try {
            applyConfigUpdate(fileName, configContent);
            sendConfigUpdateResponse(requestId, true, null);
        } catch (IOException | RuntimeException e) {
            // RuntimeException is caught too: JsonParser throws it when configData is malformed.
            // This class of failure used to bubble all the way up to handleInboundMessage's catch and
            // get logged as "an error occurred while handling message type update_config" — and the
            // panel would never get a reply.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("应用配置更新失败（文件: %s）: %s", fileName, e.getMessage()), e);
            sendConfigUpdateResponse(requestId, false, e.getMessage());
        }
    }

    /** Reads a string field that may be missing, or may be a JSON null. */
    private static String readString(JsonObject data, String field) {
        return (data.has(field) && !data.get(field).isJsonNull())
                ? data.get(field).getAsString() : null;
    }

    /**
     * Reads the config content, preferring {@code data.configData}.
     *
     * <p>{@code data.config} used to be the only field this method read, but no producer of it can
     * be found anywhere in the tree — the panel has always sent {@code configData}. It is kept only
     * for compatibility with a possible third-party panel; reading it logs a deprecation warning.
     */
    private static String readConfigContent(JsonObject data) {
        String configData = readString(data, "configData");
        if (configData != null) {
            return configData;
        }
        String legacy = readString(data, "config");
        if (legacy != null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "update_config 使用了已废弃的 data.config 字段，请改用 data.configData");
        }
        return legacy;
    }

    /**
     * Decides where to write based on {@code fileName}.
     *
     * <p>The three branches correspond to three payload shapes — exactly what the former
     * "fileName is never read" covered up: {@code server_properties} is a flat property table
     * handed to its own dedicated manager; a named file is that one config file's own
     * {@code {key: value}} map; and no file name at all means the full nested structure from
     * {@link com.ultikits.ultitools.manager.ConfigManager#toJson()}.
     */
    private static void applyConfigUpdate(String fileName, String configContent) throws IOException {
        if (SERVER_PROPERTIES_FILE.equals(fileName)) {
            ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
            if (spm == null) {
                throw new IOException("ServerPropertiesManager is not available");
            }
            // Goes through applySetAll rather than handleServerProperties in order to get a return
            // value back. The latter is void, so this path used to be able only to unconditionally
            // report success — the status the panel got meant "the message finished processing," not
            // "the config took effect," and those two things diverge the moment the SAFE_KEYS
            // whitelist blocks a key. See issue #281.
            // Both messages are still sent: applySetAll sends its own server_properties_result, and
            // an exception thrown here is turned by the caller into config_update_response's error.
            ServerPropertiesManager.SetAllResult result = spm.applySetAll(
                    com.google.gson.JsonParser.parseString(configContent).getAsJsonObject());
            if (!result.isSuccess()) {
                throw new IOException(result.describeFailure());
            }
            return;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            ConfigEditorUtils.updateConfigMap(configContent);
            return;
        }
        ConfigEditorUtils.updateConfigMap(fileName, configContent);
    }

    /**
     * Sends back one {@code config_update_response}.
     *
     * <p>The payload sits in {@code data}, matching every other plugin-to-Worker message
     * (see {@code CommandExecutionManager.sendCommandResult}). This one used to be a flat write with
     * fields hanging directly off the top level; the Worker side reads both shapes
     * (ultipanel-api-worker#30), so this change did not need to ship simultaneously with the panel.
     */
    private static void sendConfigUpdateResponse(String requestId, boolean success, String error) {
        if (requestId == null || currentWebSocketClient() == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        payload.addProperty("status", success ? "success" : "error");
        if (error != null) {
            payload.addProperty("error", error);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "config_update_response");
        response.add("data", payload);
        response.addProperty("serverId", currentWebSocketClient().getServerId());
        currentWebSocketClient().sendMessage(response);
    }
    
    // ========== System base message handlers ==========

    /**
     * Handles a ping message
     */
    private static void handlePing(JsonObject message) {
        // Send pong response
        JsonObject pongResponse = new JsonObject();
        pongResponse.addProperty("type", "pong");
        pongResponse.addProperty("timestamp", System.currentTimeMillis());
        
        JsonObject pongData = new JsonObject();
        pongData.addProperty("timestamp", System.currentTimeMillis());
        pongResponse.add("data", pongData);
        
        currentWebSocketClient().sendMessage(pongResponse);
        UltiTools.getInstance().getLogger().log(Level.FINE, "Responded to ping with pong");
    }
    
    /**
     * Handles a pong message
     */
    private static void handlePong(JsonObject data) {
        UltiTools.getInstance().getLogger().log(Level.FINE, "Received pong response");
        if (data != null && data.has("timestamp") && !data.get("timestamp").isJsonNull()) {
            long serverTimestamp = data.get("timestamp").getAsLong();
            long currentTime = System.currentTimeMillis();
            long latency = currentTime - serverTimestamp;
            UltiTools.getInstance().getLogger().log(Level.FINE, "WebSocket latency: " + latency + "ms");
        }
    }
    
    /**
     * Handles a subscribe message
     */
    private static void handleSubscribe(JsonObject data) {
        if (data != null) {
            boolean subscribed = safeGetBoolean(data, "subscribed", false);
            String serverId = safeGetString(data, "serverId");
            String message = safeGetString(data, "message");
            if (subscribed) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    String.format("成功订阅服务器: %s - %s", serverId, message));
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("订阅服务器失败: %s - %s", serverId, message));
            }
        }
    }
    
    /**
     * Handles an unsubscribe message
     */
    private static void handleUnsubscribe(JsonObject data) {
        if (data != null) {
            String serverId = safeGetString(data, "serverId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("已取消订阅服务器: %s", serverId));
        }
    }
    
    /**
     * Handles a notification message
     */
    private static void handleNotification(JsonObject data) {
        if (data != null) {
            String message = safeGetString(data, "message");
            String clientId = safeGetString(data, "clientId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[服务器通知] %s (客户端ID: %s)", message, clientId));
        }
    }
    
    /**
     * Handles an error message
     */
    private static void handleError(JsonObject data) {
        if (data != null) {
            String errorMessage = safeGetString(data, "message");
            UltiTools.getInstance().getLogger().log(Level.SEVERE,
                String.format("[WebSocket错误] %s", errorMessage));
        }
    }
    
    // ========== Server monitoring message handlers ==========

    /**
     * Handles a player event
     */
    private static void handlePlayerEvent(JsonObject data) {
        if (data != null) {
            String eventType = safeGetString(data, "eventType");
            JsonObject player = data.has("player") && data.get("player").isJsonObject()
                ? data.getAsJsonObject("player") : null;
            if (player != null) {
                String playerName = safeGetString(player, "name");
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    String.format("[玩家事件] %s: %s", eventType, playerName));
            }
        }
    }
    
    // ========== Operation control message handlers ==========

    /**
     * Handles a command execution result
     */
    private static void handleCommandResult(JsonObject data) {
        // command_result messages are echoed back from DO — already logged by
        // CommandExecutionManager, so we only log at FINE (debug) level here.
        if (data != null) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[命令执行结果] %s", data));
        }
    }
    
    /**
     * Handles a file operation result
     */
    private static void handleFileOperationResult(JsonObject data) {
        if (data != null) {
            String operationId = safeGetString(data, "operationId");
            boolean success = safeGetBoolean(data, "success", false);
            String operation = safeGetString(data, "operation");
            String path = safeGetString(data, "path");
            String message = safeGetString(data, "message");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[文件操作结果] ID: %s, 操作: %s, 路径: %s, 成功: %s, 消息: %s",
                    operationId, operation, path, success, message));
            if (!success && message != null) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("文件操作失败: %s", message));
            }
        }
    }
    
    // ========== Data stream message handlers ==========

    /**
     * Handles a backup operation
     */
    private static void handleBackupOperation(JsonObject data) {
        if (data != null) {
            String operation = safeGetString(data, "operation");
            String operationId = safeGetString(data, "operationId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[备份操作] 操作类型: %s, ID: %s", operation, operationId));
        }
    }
    
    /**
     * Handles backup progress
     */
    private static void handleBackupProgress(JsonObject data) {
        if (data != null) {
            String operationId = safeGetString(data, "operationId");
            double progress = safeGetDouble(data, "progress", 0.0);
            String currentStep = safeGetString(data, "currentStep");
            boolean completed = safeGetBoolean(data, "completed", false);
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[备份进度] ID: %s, 进度: %.1f%%, 当前步骤: %s, 完成: %s",
                    operationId, progress, currentStep, completed));
        }
    }
    
    // ========== Config management message handlers ==========

    /**
     * Handles a config upload
     */
    private static void handleConfigUpload(JsonObject data) {
        if (data != null) {
            // Only handle explicit config upload requests (carrying a requestId); ignore server acknowledgement messages
            if (data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                String configType = data.get("configType").getAsString();
                String configName = data.get("configName").getAsString();
                
                if (configType == null || configType.trim().isEmpty()) {
                    sendErrorResponse("Valid configuration type is required");
                    return;
                }
                
                UltiTools.getInstance().getLogger().log(Level.FINE, 
                    String.format("[配置上传] 类型: %s, 名称: %s", configType, configName));
                
                try {
                    // Handle the config upload logic
                    handleConfigUploadLogic(data);

                    // Send success response
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "upload_config_response");
                    response.addProperty("status", "success");
                    response.addProperty("serverId", currentWebSocketClient().getServerId());
                    response.addProperty("requestId", requestId);
                    currentWebSocketClient().sendMessage(response);

                } catch (Exception e) {
                    sendErrorResponse("Failed to upload config: " + e.getMessage());
                }
            } else {
                // Recognize and ignore server acknowledgement messages
                if (data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器配置上传确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器配置上传消息，但不包含requestId，忽略处理");
                }
            }
        }
    }
    
    /**
     * Handles the config upload logic
     */
    private static void handleConfigUploadLogic(JsonObject data) throws Exception {
        String configType = data.get("configType").getAsString();
        String configName = data.get("configName").getAsString();
        Object configContent = data.get("configContent");
        String format = data.get("format").getAsString();
        boolean backup = data.get("backup").getAsBoolean();

        UltiTools.getInstance().getLogger().log(Level.FINE, 
            String.format("处理配置上传: 类型=%s, 名称=%s, 格式=%s, 备份=%s", 
                configType, configName, format, backup));

        // Handle different config files based on config type
        switch (configType) {
            case "plugin_config":
                // Handle plugin config
                if (configContent instanceof JsonObject) {
                    ConfigEditorUtils.updateConfigMap(new Gson().toJson(configContent));
                }
                break;
            case "server_properties":
                // Handle server.properties config
                UltiTools.getInstance().getLogger().log(Level.FINE, "Processing server.properties config");
                break;
            case "permissions":
                // Handle permissions config
                UltiTools.getInstance().getLogger().log(Level.FINE, "Processing permissions config");
                break;
            default:
                throw new IllegalArgumentException("Unsupported config type: " + configType);
        }
    }

    // ========== Utility methods ==========

    /**
     * Sends an error response
     */
    private static void sendErrorResponse(String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("type", "error");
        errorResponse.addProperty("timestamp", System.currentTimeMillis());
        
        JsonObject errorData = new JsonObject();
        errorData.addProperty("message", errorMessage);
        errorResponse.add("data", errorData);
        
        currentWebSocketClient().sendMessage(errorResponse);
    }
    
    /**
     * Handles a plugin list request
     */
    private static void handlePluginListRequest(JsonObject data) {
        try {
            // Only handle explicit plugin list requests (carrying a requestId); ignore server acknowledgement messages
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                
                JsonObject response = new JsonObject();
                response.addProperty("type", "plugin_list");
                response.addProperty("serverId", currentWebSocketClient().getServerId());
                response.addProperty("timestamp", System.currentTimeMillis());
                response.addProperty("requestId", requestId);
                
                JsonObject responseData = new JsonObject();
                JsonArray plugins = new JsonArray();
                
                // Collect all plugin info
                for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                    JsonObject pluginInfo = new JsonObject();
                    pluginInfo.addProperty("name", plugin.getName());
                    pluginInfo.addProperty("version", plugin.getDescription().getVersion());
                    pluginInfo.addProperty("enabled", plugin.isEnabled());
                    pluginInfo.addProperty("author", String.join(", ", plugin.getDescription().getAuthors()));
                    pluginInfo.addProperty("description", plugin.getDescription().getDescription());
                    plugins.add(pluginInfo);
                }
                
                responseData.add("plugins", plugins);
                responseData.addProperty("totalCount", plugins.size());
                response.add("data", responseData);
                
                currentWebSocketClient().sendMessage(response);
            } else {
                // Recognize and ignore server acknowledgement messages
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器插件列表确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器插件列表消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Error handling plugin list request: " + e.getMessage());
        }
    }

    /**
     * Handles a server status request
     */
    private static void handleServerStatusRequest(JsonObject data) {
        try {
            // Only handle explicit status requests (carrying a requestId); ignore server acknowledgement messages
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                UltiTools.getInstance().getLogger().log(Level.FINE, 
                    String.format("收到服务器状态请求，请求ID: %s", requestId));

                // Immediately send the current server status, including the request id
                UltiTools.getInstance().getServerMonitorManager().sendServerStatusWithRequestId(requestId);
            } else {
                // Ignore server acknowledgement messages and other non-request messages
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器状态确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器状态消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "处理服务器状态请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handles a metrics data request
     */
    private static void handleMetricsRequest(JsonObject data) {
        try {
            // Only handle explicit metrics data requests (carrying a requestId); ignore server acknowledgement messages
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                UltiTools.getInstance().getServerMonitorManager().sendMetricsDataWithRequestId(requestId);
            } else {
                // Recognize and ignore server acknowledgement messages
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器性能数据确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器性能数据消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Error handling metrics request: " + e.getMessage());
        }
    }

    /**
     * Uploads the local config to the server
     */
    private static void uploadConfig(UltiPanelWebSocketClient client) {
        JsonObject configMessage = new JsonObject();
        configMessage.addProperty("type", "upload_config");
        
        JsonObject data = new JsonObject();
        data.addProperty("configType", "plugin_config");  // The required config type
        data.addProperty("configName", "UltiTools.yml");   // The config file name
        data.addProperty("configContent", ConfigEditorUtils.getConfigMapString());
        data.addProperty("format", "yaml");                // The format info
        data.addProperty("backup", true);                  // The backup flag
        data.addProperty("comment", ConfigEditorUtils.getCommentMapString());
        data.addProperty("serverId", client.getServerId());
        
        configMessage.add("data", data);
        configMessage.addProperty("serverId", client.getServerId());
        
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("正在上传本地配置..."));
        client.sendMessage(configMessage);
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("配置上传成功!"));
    }

    /**
     * Upload server.properties safe keys to cloud for panel editing.
     */
    private static void uploadServerProperties(UltiPanelWebSocketClient client) {
        ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
        if (spm == null) return;

        Map<String, String> props = spm.getSafeProperties();
        if (props.isEmpty()) return;

        JsonObject propsJson = new JsonObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            propsJson.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject message = new JsonObject();
        message.addProperty("type", "server_properties_result");
        message.addProperty("serverId", client.getServerId());

        message.add("data", propsJson);

        UltiTools.getInstance().getLogger().log(Level.FINE, "正在上传服务器属性配置...");
        client.sendMessage(message);
        UltiTools.getInstance().getLogger().log(Level.FINE, "服务器属性配置上传成功!");
    }

    /**
     * Re-initialize the WebSocket connection on the current session, with a fresh token if needed.
     */
    public static void reinitWebSocket() {
        reinitWebSocket(CloudSession.current());
    }

    /**
     * Re-initialize the WebSocket connection with a fresh token, on a specific session.
     * Disconnects the old client (if any), refreshes the token if needed, and creates a new
     * WebSocket client -- all against {@code session}, never against whatever
     * {@link CloudSession#current()} happens to be when this runs (D-16). The
     * reconnect-exhausted handler that invokes this captures its own session lexically at
     * registration time (see {@link #initWebsocket(CloudSession)}); the no-arg
     * {@link #reinitWebSocket()} overload above is a convenience for callers that are always
     * operating on the current session (tests, mostly), where the two coincide.
     *
     * @param session the session whose WebSocket client is being re-initialized
     */
    static void reinitWebSocket(CloudSession session) {
        // Gate one: no more reconnecting after logout.
        // This is the line that makes `/ulticloud logout` actually take effect — before it existed,
        // logout only cleared the credential, and this chain kept reconnecting with the
        // already-invalidated token, running a 401 loop that measurement showed only stopped with a
        // fresh login or a server restart. See issue #223. As of 16-08 Task 2 the check is this
        // session's own currency, not a shared flag -- a session that has been invalidated (by
        // disableCloud(), or by being superseded via CloudSession.startNew()) fails this gate
        // permanently, regardless of what is current by the time this runs.
        if (!session.isCurrent()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Cloud features are disabled — skipping WebSocket re-initialization");
            return;
        }

        // Gate two: the global budget. The client's own limit of 5 attempts is per-instance, and
        // this method builds a new instance every time, so that per-instance cap places no
        // constraint on the whole. See issue #181. The budget itself is session-owned now: a new
        // session's backoff starts fresh by construction, so nothing needs to reset it across
        // logins the way the old shared field did.
        if (!session.getBackoff().shouldContinue()) {
            // Finish saying this before tearing down: the disableCloud() call below shuts off the
            // log upload channel, and this line has to go out before that happens.
            UltiTools.getInstance().getLogger().log(Level.WARNING, String.format(
                "WebSocket re-initialization gave up after %d attempts. Cloud features are now idle. "
                    + "Run /ulticloud login to retry, or restart the server.",
                CloudSession.MAX_REINIT_ATTEMPTS));
            // "now idle" must actually be true. This used to be a single enabled-flag flip
            // call: the state machine did stop, but the heartbeat thread, the log transporter and
            // root logger handler, the player event listener, and the token refresh schedule all
            // kept running — the log line declared idleness while things were still leaking. A
            // terminal state and logout are the same event and should go through the same teardown
            // path.
            //
            // Reusing disableCloud() is safe: it invalidates CloudSession.current(), which -- as
            // long as no other session has been installed in between -- is this same session, so
            // even if its own stopWebsocket() triggers the onClose reconnect chain, it gets caught
            // by gate one at the top of this method.
            disableCloud();
            return;
        }

        UltiTools.getInstance().getLogger().log(Level.INFO, String.format(
            "Re-initializing WebSocket connection (attempt %d/%d)...",
            session.getBackoff().getAttemptCount() + 1, CloudSession.MAX_REINIT_ATTEMPTS));
        session.getBackoff().getNextDelay();   // Record one attempt; the actual wait is handled by the client-side scheduler

        // Disconnect old client
        UltiPanelWebSocketClient oldClient = session.getWebSocketClient();
        if (oldClient != null) {
            try {
                oldClient.disconnect();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Error disconnecting old WebSocket: " + e.getMessage());
            }
            session.setWebSocketClient(null);
        }

        // Ensure token is valid — refresh if needed
        TokenEntity currentToken = session.getToken();
        if (currentToken == null || currentToken.isExpired()) {
            if (currentToken != null && currentToken.getRefresh_token() != null
                    && !currentToken.getRefresh_token().isEmpty()) {
                TokenEntity refreshed = session.refresh(currentToken.getRefresh_token());
                if (refreshed != null) {
                    UltiTools.getInstance().getLogger().log(Level.INFO,
                        "Token refreshed for WebSocket re-initialization");
                } else {
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        "Token refresh failed — cannot re-initialize WebSocket");
                    return;
                }
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "No valid token available — cannot re-initialize WebSocket");
                return;
            }
        }

        // Second confirmation. Between the currency check at the top of this method and here, a
        // token refresh has happened in between — a network call, and that window can be several
        // seconds wide. If a logout happens inside this window, it must be seen here, otherwise a
        // newly-authenticated client gets built that resurrects the state machine that was just
        // turned off.
        if (!session.isCurrent()) {
            UltiTools.getInstance().getLogger().log(Level.INFO,
                "Cloud features were disabled during re-initialization — aborting");
            return;
        }

        // Create new WebSocket connection
        try {
            initWebsocket(session);
            // Deliberately does not log "re-initialized successfully" here.
            // initWebsocket() returning only means the client was built and connect() was
            // dispatched — connect() is asynchronous, and the handshake and authentication have not
            // happened yet. Measurement showed a 401 immediately following this line. The success
            // message is now logged by onOpen (see initWebsocket's onConnectHandler), which is the
            // point where the connection is actually up. See issue #223.
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "WebSocket re-initialization dispatched — awaiting handshake");
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "WebSocket re-initialization failed: " + e.getMessage());
        }
    }

    /**
     * Closes the cloud connection and invalidates the current session, putting the reconnection
     * state machine into an explicit disabled state.
     * <p>
     * Called by {@code /ulticloud logout}. The difference from {@link #stopWebsocket()} is that the
     * latter only disconnects the current client, and the reconnection chain will bring it back up;
     * this method invalidates {@link CloudSession#current()} first, so
     * {@link #reinitWebSocket(CloudSession)} returns immediately afterward and the state machine
     * does not resurrect itself.
     * <p>
     * Also strips the log handler and the transport thread off the root logger, and stops the
     * server monitor and the player-event listener — all part of what makes the statement "cloud
     * features are disabled" true. As of 16-08 Task 2 this no longer needs its own
     * the former global lifecycle lock: {@link CloudSession#invalidate()} and
     * {@link CloudSession#initializeManagers()} both synchronize on the session instance itself, so
     * wiring and teardown are mutually exclusive on that lock without a separate global one. See
     * the two review rounds on PR #264 for the original race this replaces.
     */
    public static void disableCloud() {
        doDisableCloud();
    }

    /** The actual teardown performed by {@link #disableCloud()}. */
    private static void doDisableCloud() {
        // Order still matters here, but for a narrower reason than before Task 2: the log
        // transporter's flush must run BEFORE the WebSocket client is closed, or sendBatch() finds
        // the socket already down and sends nothing (BoundedFlush's own test class covers the
        // disconnected-with-a-non-empty-queue case, which is exactly what would happen every time if
        // this order were reversed). Everything else CloudSession#invalidate() folds into one call
        // is no longer order-sensitive against the OTHER steps here (D-16/D-18) -- it is
        // order-sensitive only against this one.
        teardownStep("shutting down log stream manager",
            () -> UltiTools.getInstance().getLogStreamManager().shutdown());

        // Invalidating the session covers what used to be four separate steps: flip the enabled
        // flag off, stop the refresh schedule, stop the magic-link polling, and invalidate anything
        // already in flight -- plus, as of Task 2, closing and clearing the WebSocket client. One
        // call, one lock (the session's own), so none of those pieces can be caught mid-transition
        // by a concurrent activation. See CloudSession#invalidate()'s own javadoc for why this
        // single call is sufficient where the old code needed a careful multi-step order.
        teardownStep("invalidating the current cloud session (stops the refresh scheduler, the "
                + "polling, closes the WebSocket client, and discards anything already in flight)",
            () -> CloudSession.current().invalidate());

        // Stop server monitoring. It carries its own ScheduledExecutorService (batch_update every 5
        // seconds) plus two main-thread Bukkit scheduled tasks (1Hz TPS/CPU, a world/player/plugin
        // snapshot every 5 seconds). Before this line existed at all, stopMonitoring() had no caller
        // anywhere in src/main — written, tested, just never wired up. Without stopping it, the main
        // thread would keep iterating every world and chunk every 5 seconds after "cloud features
        // are disabled."
        teardownStep("stopping server monitor", () -> {
            if (UltiTools.getInstance().getServerMonitorManager() != null) {
                UltiTools.getInstance().getServerMonitorManager().stopMonitoring();
            }
        });

        // Strip the player event listener. Still receiving player events after cloud is disabled is
        // pure waste — the isConnected() check inside the event handler only suppresses sending a
        // message; the listener itself keeps running. See issue #180.
        teardownStep("shutting down player event manager", () -> {
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                UltiTools.getInstance().getPlayerEventManager().shutdown();
            }
        });
    }

    /**
     * Runs one teardown step; a failure is only logged at FINE, never thrown out.
     * <p>
     * Every teardown step must run to the best of its ability: any step throwing would skip every
     * step after it, and those steps are exactly what makes the statement "cloud features are
     * disabled" true. This used to be six identical try/catch blocks; extracting it only states that
     * invariant once — the behaviour is unchanged.
     *
     * @param what   the action description written to the log on failure
     * @param action the teardown action
     */
    private static void teardownStep(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error " + what + ": " + e.getMessage());
        }
    }

    /**
     * Called when a reconnection succeeds: resets the current session's outer budget.
     * <p>
     * Only a <b>genuinely successful handshake</b> is entitled to reset the budget. Resetting it
     * inside {@code reinitWebSocket} instead would treat "a client was built" as success, the budget
     * would never run out, and the gate would amount to nothing added.
     */
    static void onWebSocketConnected() {
        CloudSession.current().getBackoff().reset();
    }

    /**
     * Atomically, inside the session's own lock: "re-check that {@code session} is still current →
     * connect → start the refresh schedule."
     * <p>
     * Making only the credential-write step atomic against logout is not enough: after committing a
     * credential, magic-link polling still has to do {@code initWebsocket()} +
     * {@code session.startTokenRefreshScheduler()}, and that sequence is what actually connects the
     * server back. If logout lands in the gap between "commit succeeded" and "activation started,"
     * teardown tears down a connection that has not been built yet, and the polling thread goes
     * ahead and builds it anyway — undoing the logout.
     * <p>
     * This method synchronizes on {@code session} itself, the same monitor
     * {@link CloudSession#invalidate()} uses, so the two can only ever happen as a whole, one after
     * the other: either activation completes first and is then torn down (clean), or teardown
     * happens first and this method, re-checking {@code session}'s own currency while holding the
     * lock, sees it is no longer current and returns false directly (also clean). Checking
     * {@code session.isCurrent()} rather than comparing against a shared counter is exactly what
     * makes this re-check ordering-independent (D-16/D-18): the session this call was handed either
     * still is what it was, or it is not, regardless of how many other sessions have come and gone
     * in between. As of 16-08 Task 2 there is no separate "enable" step: a session that passes this
     * check is, by definition, the thing "enabled" now means.
     * <p>
     * Deliberately does <b>not</b> call {@code loginWithToken()} while holding the lock — that is an
     * HTTP round trip, and doing it under the lock would block {@code /ulticloud logout} on the main
     * thread for several seconds. It only registers the server with the panel and does not change
     * local state, so running it again outside the lock is harmless.
     *
     * @param session the session the caller recorded when this login/activation started
     * @return {@code true} if activated; {@code false} if the session had already been invalidated
     *         and activation was abandoned
     * @throws IOException if establishing the connection fails
     */
    static boolean activateCloudIfCurrent(CloudSession session) throws IOException {
        synchronized (session) {
            if (!session.isCurrent()) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    "Cloud activation aborted — a logout happened while this login was completing");
                return false;
            }
            initWebsocket(session);
            session.startTokenRefreshScheduler();
            return true;
        }
    }

    /**
     * Ensures the current session is usable, replacing it with a fresh one if it has been
     * invalidated.
     * <p>
     * <b>Only an explicit action should call this</b>: cloud login at server startup, and after a
     * successful {@code /ulticloud login}. {@link #initWebsocket()} deliberately does not call it —
     * it is also reused by {@link #reinitWebSocket()}, and calling it there would let an in-flight
     * reconnection resurrect a state machine that had just been turned off by logout.
     * <p>
     * As of 16-08 Task 2 there is no separate enabled flag to flip: "enabled" simply
     * means {@link CloudSession#current()} is not invalidated. If it already is not (the common
     * case -- most callers reach this with a perfectly good session already installed), this
     * method does nothing; replacing a working session here for no reason would gratuitously drop
     * its token, its WebSocket client and its backoff progress. Only a session that
     * {@link CloudSession#invalidate()} already ran on (typically {@code disableCloud()}, without a
     * following {@code /ulticloud login}) gets replaced.
     */
    public static void enableCloud() {
        if (!CloudSession.current().isCurrent()) {
            CloudSession.startNew();
        }
    }

    /** Lets a test assert whether the state machine is currently enabled -- i.e. whether the current session is still current. */
    static boolean isCloudEnabled() {
        return CloudSession.current().isCurrent();
    }

    /**
     * Disconnects the current session's WebSocket client, if any, without invalidating the session
     * or touching the reconnection state machine -- the reconnect chain will bring the connection
     * back up on its own. Used by {@link com.ultikits.ultitools.UltiTools#onDisable()}, where a
     * full {@link #disableCloud()} teardown is unnecessary (the JVM is going away regardless).
     */
    public static void stopWebsocket() {
        UltiPanelWebSocketClient client = currentWebSocketClient();
        if (client == null) {
            return;
        }
        client.disconnect();
    }

    private static UltiPanelWebSocketClient getPanelWebsocketClient(TokenEntity token) throws IOException {
        String apiUrl = UltiTools.getEnv().getString("api-url");
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new IOException("API URL not configured in env.yml");
        }
        apiUrl = apiUrl.trim();

        // Derive WebSocket URL from the API base URL
        String wsUrl;
        if (apiUrl.startsWith("https://")) {
            wsUrl = "wss://" + apiUrl.substring("https://".length()) + "/ws";
        } else if (apiUrl.startsWith("http://")) {
            wsUrl = "ws://" + apiUrl.substring("http://".length()) + "/ws";
        } else {
            wsUrl = "wss://" + apiUrl + "/ws";
        }

        try {
            return new UltiPanelWebSocketClient(wsUrl, CommonUtils.getUltiToolsUUID(), token.getAccess_token());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid WebSocket URL: " + wsUrl, e);
        }
    }

    // ========== Null-safe JSON accessors ==========

    private static String safeGetString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static boolean safeGetBoolean(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }

    private static long safeGetLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsLong();
    }

    private static double safeGetDouble(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsDouble();
    }
}
