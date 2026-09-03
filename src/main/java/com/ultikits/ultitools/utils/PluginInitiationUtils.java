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
    /** WebSocket client for panel communication */
    private static UltiPanelWebSocketClient panelWS;
    /** Authentication token for API requests */
    private static TokenEntity token;

    /** {@code server_properties} is handled by its own dedicated manager — it is not a real config file path. */
    private static final String SERVER_PROPERTIES_FILE = "server_properties";

    /**
     * Whether the cloud connection is in the "should stay connected" state.
     * <p>
     * This is the <b>single switch</b> for the entire reconnection chain. Before it existed, four
     * places each independently decided whether to keep reconnecting, and none of them owned the
     * decision: {@code UltiPanelWebSocketClient.onClose} counted per instance (5 attempts),
     * {@code reinitWebSocket} reset the count every time it built a new instance,
     * {@code ulticloud logout} only cleared the credential and never touched the state machine, and
     * only {@code onDisable} actually tore everything down cleanly. The result was the plugin
     * continuing to hammer the panel with an already-invalidated credential after logout. See issue
     * #181 and #223.
     * <p>
     * The rule now is a single one: <b>{@code reinitWebSocket} only rebuilds the connection when
     * this flag is true.</b>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean cloudEnabled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * The mutual-exclusion lock between wiring up and tearing down the cloud managers.
     * <p>
     * {@link #cloudEnabled} alone is not enough: it can only express state, not "no one may cut in
     * between the check and the action." After {@code initializeManagers()} reads true but before it
     * actually wires anything up, {@code disableCloud()} can cut in, flip the switch off, and tear
     * the listeners down cleanly — and then the former continues on and wires them right back up,
     * leaving listeners in place after logout, still able to send events to the panel. See the two
     * review rounds on PR #264.
     * <p>
     * Once both sides hold this same lock, the two can only ever happen as a whole, one after the
     * other: either wire-up completes first and is then torn down (clean), or teardown happens first
     * and the wiring side, re-checking while holding the lock, sees false and returns immediately
     * (also clean).
     */
    private static final Object cloudLifecycleLock = new Object();

    /** The global cap on outer reinit attempts. Once exceeded, the state machine enters a terminal state and only {@code /ulticloud login} or a restart recovers it. */
    private static final int MAX_REINIT_ATTEMPTS = 10;

    /**
     * The global budget and backoff for the outer reconnection (reinit loop).
     * <p>
     * The client's own limit of 5 attempts is a <b>per-instance</b> cap, and {@code reinitWebSocket}
     * builds a brand-new instance every time — so the per-instance cap places no constraint at all
     * on the whole, which is exactly how the loop became unbounded. This strategy spans instances
     * instead; only one successful {@code onOpen} resets it.
     * <p>
     * This is what actually drives the retry: {@link ExponentialBackoffStrategy} is live code, held
     * as this field, and every attempt advances its backoff delay.
     */
    private static final ExponentialBackoffStrategy reinitBackoff =
            ExponentialBackoffStrategy.withMaxAttempts(MAX_REINIT_ATTEMPTS);

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
    // (buildInboundHandlers() does not reference cloudEnabled/reinitBackoff/etc.; see the
    // Phase 06 Codacy remediation commit for the verification).

    /**
     * Login to UltiPanel using an existing token (from magic-link or saved token).
     * Registers or updates the server without needing username/password.
     *
     * @param existingToken the pre-authenticated token
     * @return true if server registration/update succeeded
     * @throws IOException if an I/O error occurs
     */
    public static boolean loginWithToken(TokenEntity existingToken) throws IOException {
        token = existingToken;
        String uuid = CommonUtils.getUltiToolsUUID();
        int port = org.bukkit.Bukkit.getServer().getPort();
        String domain = "";
        boolean ssl = true;

        try (Response uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token)) {
            if (uuidResponse.getStatus() == 404) {
                String serverName = org.bukkit.Bukkit.getServer().getName();
                if (serverName == null || serverName.trim().isEmpty()) {
                    serverName = "MC Server";
                }
                if (serverName.length() > 64) {
                    serverName = serverName.substring(0, 64);
                }
                try (Response registerResponse = HttpRequestUtils.registerServer(uuid, serverName, port, domain, ssl, token)) {
                    if (!registerResponse.isOk()) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "Server registration failed: HTTP " + registerResponse.getStatus() + " - " + registerResponse.body());
                        return false;
                    }
                }
            } else if (uuidResponse.isOk()) {
                try (Response updateResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token)) {
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
     * Initialize websocket.
     */
    public static void initWebsocket() throws IOException {
        if (token == null || token.getAccess_token() == null) {
            throw new IOException("Cannot initialize WebSocket: no auth token available");
        }
        if (token.isExpired()) {
            throw new IOException("Cannot initialize WebSocket: auth token has expired");
        }

        // Deliberately does **not** set cloudEnabled here.
        //
        // It used to call set(true) at this point, and that was wrong: reinitWebSocket also reaches
        // this line, so an in-flight reconnection could resurrect a state machine that had just been
        // turned off by logout — the two run on different threads, with a token-refresh network call
        // in between, and that window can be several seconds wide.
        //
        // Now only an explicit action turns it on: UltiTools.onEnable at startup, and CloudAuthManager
        // after a successful magic-link login — both call enableCloud(). See the PR review on issue
        // #223.

        // Wire everything up through a local reference for the whole method; don't write the static
        // field and then read it back: the callbacks registered below fire asynchronously, and by the
        // time they fire the static panelWS may no longer be this instance.
        UltiPanelWebSocketClient client = getPanelWebsocketClient();
        panelWS = client;

        // Set the message handler
        client.setMessageHandler(PluginInitiationUtils::handleInboundMessage);

        // Set the on-connect-success handler
        client.setOnConnectHandler(() -> onWebSocketOpened(client));

        // Set the reconnect-exhausted handler — attempts to refresh the token and re-establish the connection
        client.setOnReconnectExhaustedHandler(PluginInitiationUtils::reinitWebSocket);

        // Connect to the WebSocket server
        client.connect();
    }

    /**
     * The wiring performed once a handshake succeeds.
     * <p>
     * <b>The parameter is this handshake's own client; the method body never re-reads the static
     * {@code panelWS}.</b> onOpen is an asynchronous callback: by the time it runs,
     * {@code disableCloud()} may already have nulled out the static field ({@code /ulticloud logout},
     * or the reconnect budget being exhausted — the latter runs on the WebSocket thread), or
     * {@code reinitWebSocket} may already have swapped it for a different instance. Re-reading the
     * static field would make {@code subscribeToServer} / {@code uploadConfig} /
     * {@code uploadServerProperties} all silently hit a stale or null reference —
     * {@link #initializeManagers()} is guarded by a lock-held re-check, but the code around it here is
     * not.
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
            if (panelWS != null) {
                response.addProperty("serverId", panelWS.getServerId());
                panelWS.sendMessage(response);
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
        if (panelWS == null) {
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
        response.addProperty("serverId", panelWS.getServerId());
        panelWS.sendMessage(response);
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
     * Wires all WebSocket managers up to the current connection.
     * <p>
     * This method hangs off {@code onConnectHandler}, and an in-flight handshake can still land
     * after {@code /ulticloud logout}. Without a guard, the listeners {@code disableCloud()} just
     * tore down would be reinstalled verbatim by this late-arriving onOpen — the exact same "no one
     * owns the decision" defect from #181/#223, resurfacing in a different place.
     * <p>
     * <b>Checking {@link #cloudEnabled} alone is not enough.</b> That is only a read taken outside
     * the lock: after it reads true but before this method actually wires anything up,
     * {@code disableCloud()} can cut in, flip the switch off and tear everything down cleanly, and
     * then this method continues on and wires the listeners right back up. So wiring and teardown
     * must both land on the same {@link #cloudLifecycleLock}, and the switch must be re-checked
     * <b>while holding the lock</b>. See the two review rounds on PR #264.
     * <p>
     * Package-private rather than private — only so it can be tested.
     */
    static void initializeManagers() {
        synchronized (cloudLifecycleLock) {
            // Re-check while holding the lock: disableCloud() holds the same lock, so by this point
            // it has either not started yet or has already run to completion — it cannot be stuck
            // in the middle.
            if (!cloudEnabled.get()) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "云连接已关闭，跳过管理器初始化（这是一次登出之后迟到的握手）");
                return;
            }
            wireManagers();
        }
    }

    /**
     * The actual wiring performed by {@link #initializeManagers()}. Callers must hold
     * {@link #cloudLifecycleLock}.
     * <p>
     * D-11/D-12: the four outbound capabilities ({@code monitoring}/{@code logs}/
     * {@code player-events}/{@code server-properties}) decide here, via
     * {@link Capability#isEnabled()}, whether to <b>start collecting</b> data at all — not whether
     * to discard it at the send-side after collection. The latter would still leave data already
     * gathered into memory, just never transmitted, and D-12 explicitly rejects that
     * "exposed but not transmitted" shape. Every client-reference wiring call is deliberately kept
     * unconditional: assigning a client reference by itself starts no collection, and running it
     * unconditionally is what guarantees every manager getter is always non-null and every manager
     * always exists (D-11) — the dispatch table has two manager-getter dereferences with no null
     * check.
     */
    private static void wireManagers() {
        try {
            // Wire up the server monitor manager — reference assignment is kept separate from
            // "whether to start monitoring"; see this method's javadoc
            UltiTools.getInstance().getServerMonitorManager().setWebSocketClient(panelWS);
            if (Capability.MONITORING.isEnabled()) {
                // Start monitoring (sends status immediately and then periodically)
                UltiTools.getInstance().getServerMonitorManager().startMonitoring();
            } else {
                logSkippedCapability(Capability.MONITORING);
            }

            // Wire up the command execution manager
            UltiTools.getInstance().getCommandExecutionManager().setWebSocketClient(panelWS);

            // Wire up the file operation manager
            UltiTools.getInstance().getFileOperationManager().setWebSocketClient(panelWS);

            // Wire up the server properties manager
            if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                UltiTools.getInstance().getServerPropertiesManager().setWebSocketClient(panelWS);
            }

            // Wire up the log stream manager — while logs is disabled, SystemLogHandler is never
            // attached to the root logger
            if (UltiTools.getInstance().getLogStreamManager() != null) {
                if (Capability.LOGS.isEnabled()) {
                    UltiTools.getInstance().getLogStreamManager().initialize(panelWS);
                } else {
                    logSkippedCapability(Capability.LOGS);
                }
            }

            // Wire up the player event manager — while player-events is disabled, the Bukkit
            // listener is never registered
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                if (Capability.PLAYER_EVENTS.isEnabled()) {
                    UltiTools.getInstance().getPlayerEventManager().initialize(panelWS);
                } else {
                    logSkippedCapability(Capability.PLAYER_EVENTS);
                }
            }

            UltiTools.getInstance().getLogger().log(Level.FINE, "所有WebSocket管理器已初始化并启动监控");
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "初始化管理器时出错: " + e.getMessage(), e);
        }
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
     *
     * @param capability the capability that was skipped
     */
    private static void logSkippedCapability(Capability capability) {
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
        if (requestId == null || panelWS == null) {
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
        response.addProperty("serverId", panelWS.getServerId());
        panelWS.sendMessage(response);
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
        
        panelWS.sendMessage(pongResponse);
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
                    response.addProperty("serverId", panelWS.getServerId());
                    response.addProperty("requestId", requestId);
                    panelWS.sendMessage(response);

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
        
        panelWS.sendMessage(errorResponse);
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
                response.addProperty("serverId", panelWS.getServerId());
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
                
                panelWS.sendMessage(response);
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
     * Re-initialize the WebSocket connection with a fresh token.
     * Disconnects the old client (if any), refreshes the token if needed,
     * and creates a new WebSocket client.
     */
    public static void reinitWebSocket() {
        // Gate one: no more reconnecting after logout.
        // This is the line that makes `/ulticloud logout` actually take effect — before it existed,
        // logout only cleared the credential, and this chain kept reconnecting with the
        // already-invalidated token, running a 401 loop that measurement showed only stopped with a
        // fresh login or a server restart. See issue #223.
        if (!cloudEnabled.get()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Cloud features are disabled — skipping WebSocket re-initialization");
            return;
        }

        // Gate two: the global budget. The client's own limit of 5 attempts is per-instance, and
        // this method builds a new instance every time, so that per-instance cap places no
        // constraint on the whole. See issue #181.
        if (!reinitBackoff.shouldContinue()) {
            // Finish saying this before tearing down: the disableCloud() call below shuts off the
            // log upload channel, and this line has to go out before that happens.
            UltiTools.getInstance().getLogger().log(Level.WARNING, String.format(
                "WebSocket re-initialization gave up after %d attempts. Cloud features are now idle. "
                    + "Run /ulticloud login to retry, or restart the server.",
                MAX_REINIT_ATTEMPTS));
            // "now idle" must actually be true. This used to be a single cloudEnabled.set(false)
            // call: the state machine did stop, but the heartbeat thread, the log transporter and
            // root logger handler, the player event listener, the token refresh schedule, and the
            // static panelWS/token references all kept running — the log line declared idleness
            // while things were still leaking. A terminal state and logout are the same event and
            // should go through the same teardown path.
            //
            // Reusing disableCloud() is safe: its first action is flipping cloudEnabled off, so even
            // if its own stopWebsocket() triggers the onClose reconnect chain, it gets caught by gate
            // one at the top of this method; its incidental reinitBackoff.reset() is likewise
            // harmless — gate one has already sealed things off, so the budget never gets consumed
            // again, and recovery can only come through /ulticloud login, which resets it anyway.
            disableCloud();
            return;
        }

        UltiTools.getInstance().getLogger().log(Level.INFO, String.format(
            "Re-initializing WebSocket connection (attempt %d/%d)...",
            reinitBackoff.getAttemptCount() + 1, MAX_REINIT_ATTEMPTS));
        reinitBackoff.getNextDelay();   // Record one attempt; the actual wait is handled by the client-side scheduler

        // Disconnect old client
        if (panelWS != null) {
            try {
                panelWS.disconnect();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Error disconnecting old WebSocket: " + e.getMessage());
            }
            panelWS = null;
        }

        // Ensure token is valid — refresh if needed
        if (token == null || token.isExpired()) {
            if (token != null && token.getRefresh_token() != null && !token.getRefresh_token().isEmpty()) {
                TokenEntity refreshed = CloudAuthManager.refreshToken(token.getRefresh_token());
                if (refreshed != null) {
                    token = refreshed;
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

        // Second confirmation. Between the cloudEnabled check at the top of this method and here,
        // a token refresh has happened in between — a network call, and that window can be several
        // seconds wide. If a logout happens inside this window, it must be seen here, otherwise a
        // newly-authenticated client gets built that resurrects the state machine that was just
        // turned off.
        if (!cloudEnabled.get()) {
            UltiTools.getInstance().getLogger().log(Level.INFO,
                "Cloud features were disabled during re-initialization — aborting");
            return;
        }

        // Create new WebSocket connection
        try {
            initWebsocket();
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
     * Closes the cloud connection and puts the reconnection state machine into an explicit
     * disabled state.
     * <p>
     * Called by {@code /ulticloud logout}. The difference from {@link #stopWebsocket()} is that the
     * latter only disconnects the current client, and the reconnection chain will bring it back up;
     * this method first flips {@link #cloudEnabled} off, so {@link #reinitWebSocket()} returns
     * immediately afterward and the state machine does not resurrect itself.
     * <p>
     * Also strips the log handler and the transport thread off the root logger, and stops the token
     * refresh schedule — all part of what makes the statement "cloud features are disabled" true.
     * <p>
     * The whole method holds {@link #cloudLifecycleLock}, mutually exclusive with
     * {@code initializeManagers()}. Without it, an in-flight onOpen could cut in between "flip off"
     * and "tear down" and reinstall what was about to be torn down. See the two review rounds on PR
     * #264.
     */
    public static void disableCloud() {
        synchronized (cloudLifecycleLock) {
            doDisableCloud();
        }
    }

    /** The actual teardown performed by {@link #disableCloud()}. Callers must hold {@link #cloudLifecycleLock}. */
    private static void doDisableCloud() {
        // The order of the first three steps is the easiest place in this method to get backwards,
        // and getting it backwards leaks in either direction:
        //
        //   1. Close the gate — flip cloudEnabled off, so the reinit chain produces no new refreshes.
        //   2. Stop the producers — stop the refresh schedule and the magic-link polling, so no new
        //      task is dispatched.
        //   3. Invalidate in-flight work — advance the credential generation, so any result already
        //      in an HTTP request becomes uniformly stale.
        //
        // Invalidation **must** come after stopping the producers. The other way around (invalidate
        // first, then stop) would let any new task started in between snapshot the already-advanced
        // generation, making it "legitimate" after all — its late-returning response would still
        // write the credential back, which is exactly the trap of moving invalidation to the very
        // front.
        //
        // Nor can invalidation wait for the whole teardown to finish: teardown still has to close the
        // log stream, stop monitoring, strip listeners, and disconnect the socket, which is not a
        // short process, and during that time an in-flight poll could still commit successfully — the
        // caller (the logout command) would then see a credential that "did not exist before teardown,
        // exists after it."
        //
        // The last safeguard lives in clearToken(): it also advances a generation itself, and that
        // happens after every producer has stopped, so any result still in flight by that point is
        // already stale.
        cloudEnabled.set(false);
        reinitBackoff.reset();

        teardownStep("stopping token refresh scheduler",
            CloudAuthManager::stopTokenRefreshScheduler);

        // Stop any magic-link polling still in progress. Without this, a "logout right after login,
        // second-guessing the decision" sequence could quietly log the server back in the next time
        // polling picks up a completed result — that branch itself calls enableCloud() plus
        // initWebsocket().
        teardownStep("stopping magic-link polling", CloudAuthManager::stopPolling);

        teardownStep("invalidating in-flight credential operations",
            CloudAuthManager::invalidateCredentialOperations);

        // Order matters here: close the log transporter first, then disconnect the socket.
        // The other way around, the transporter's flush would find the socket already closed and
        // sendBatch() would send nothing at all. (flushLogs itself is already bounded too — both
        // layers are needed: the right order gives queued logs a chance to actually go out, and the
        // bound covers the case where the socket is already down.)
        teardownStep("shutting down log stream manager",
            () -> UltiTools.getInstance().getLogStreamManager().shutdown());

        // Stop server monitoring. It carries its own ScheduledExecutorService (batch_update every 5
        // seconds) plus two main-thread Bukkit scheduled tasks (1Hz TPS/CPU, a world/player/plugin
        // snapshot every 5 seconds). Before this line, stopMonitoring() had no caller anywhere in
        // src/main — written, tested, just never wired up. Without stopping it, the main thread would
        // keep iterating every world and chunk every 5 seconds after "cloud features are disabled."
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

        stopWebsocket();
        panelWS = null;

        // Clear the token held by this class. It is a **separate** copy from the credential
        // CloudAuthManager clears — without clearing it, an in-flight reinit would still be holding
        // a usable refresh token.
        token = null;
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
     * Called when a reconnection succeeds: resets the outer budget.
     * <p>
     * Only a <b>genuinely successful handshake</b> is entitled to reset the budget. Resetting it
     * inside {@code reinitWebSocket} instead would treat "a client was built" as success, the budget
     * would never run out, and the gate would amount to nothing added.
     */
    static void onWebSocketConnected() {
        reinitBackoff.reset();
    }

    /**
     * Atomically, inside the cloud lifecycle lock: "re-check the credential generation → turn the
     * state machine on → connect → start the refresh schedule."
     * <p>
     * Making only the credential-write step atomic against logout is not enough: after committing a
     * credential, magic-link polling still has to do {@code enableCloud()} +
     * {@code initWebsocket()} + {@code startTokenRefreshScheduler()}, and that sequence is what
     * actually connects the server back. If logout lands in the gap between "commit succeeded" and
     * "activation started," teardown tears down a connection that has not been built yet, and the
     * polling thread goes ahead and builds it anyway — undoing the logout.
     * <p>
     * This method contends for the same {@link #cloudLifecycleLock} as {@code disableCloud()}, so
     * the two can only ever happen as a whole, one after the other: either activation completes
     * first and is then torn down (clean), or teardown happens first and this method, re-checking
     * the generation while holding the lock, sees it has changed and returns false directly (also
     * clean).
     * <p>
     * Deliberately does <b>not</b> call {@code loginWithToken()} while holding the lock — that is an
     * HTTP round trip, and doing it under the lock would block {@code /ulticloud logout} on the main
     * thread for several seconds. It only registers the server with the panel and does not change
     * local state, so running it again outside the lock is harmless.
     *
     * @param generation the credential generation the caller recorded when it started
     * @return {@code true} if activated; {@code false} if the generation had already changed and
     *         activation was abandoned
     * @throws IOException if establishing the connection fails
     */
    public static boolean activateCloudIfCurrent(long generation) throws IOException {
        synchronized (cloudLifecycleLock) {
            if (generation != CloudAuthManager.currentCredentialGeneration()) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    "Cloud activation aborted — a logout happened while this login was completing");
                return false;
            }
            // Explicit turn-on: a fresh login after logout must be able to pull the state machine
            // back up.
            enableCloud();
            initWebsocket();
            CloudAuthManager.startTokenRefreshScheduler();
            return true;
        }
    }

    /**
     * Sets the state machine to "should stay connected" and resets the outer reconnection budget.
     * <p>
     * <b>Only an explicit action should call this</b>: cloud login at server startup, and after a
     * successful {@code /ulticloud login}. {@link #initWebsocket()} deliberately does not call it —
     * it is also reused by {@link #reinitWebSocket()}, and setting it there would let an in-flight
     * reconnection resurrect a state machine that had just been turned off by logout.
     */
    public static void enableCloud() {
        cloudEnabled.set(true);
        reinitBackoff.reset();
    }

    /** Lets a test assert whether the state machine is currently enabled. */
    static boolean isCloudEnabled() {
        return cloudEnabled.get();
    }

    public static void stopWebsocket() {
        if (panelWS == null){
            return;
        }
        panelWS.disconnect();
    }

    private static UltiPanelWebSocketClient getPanelWebsocketClient() throws IOException {
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
