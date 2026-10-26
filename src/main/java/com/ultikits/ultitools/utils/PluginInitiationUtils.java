package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Start-up and shutdown entry points of the UltiCloud connection.
 * <p>
 * As of 6.3.1 the connection speaks UltiCloud protocol v2 only: {@link #initWebsocket()} starts the current
 * session's agent connection, and every inbound request is routed by the v2 agent router that
 * {@link CloudFeatures} registers per connection. The protocol v1 inbound dispatch this class used to hold (its
 * message-type table, the capability gate in front of it, the per-type v1 handlers and the v1 handshake callback)
 * was removed in 6.3.1 (D-01: no v1 adapter); every public member keeps its 6.3.0 signature (D-29).
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class PluginInitiationUtils {

    static final String LEGACY_TOKEN_REMOVED_KEY = "The account login saved by an earlier UltiTools version is no longer used and was removed from %s. Run /ulticloud login to re-attach this server.";

    /**
     * The elapsed-time threshold above which a {@link PanelMessageEvent} publish is considered
     * slow enough to warn about, in milliseconds. Set below one server tick (50ms at the nominal
     * 20 TPS) so a subscriber costing a visible fraction of the tick budget is named before
     * players feel it — this constant is the runtime half of D-24's mitigation; {@link
     * PanelMessageEvent}'s javadoc is the other half, stating the contract a reader sees before
     * ever hitting this warning at runtime.
     */
    private static final long SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS = 20L;

    /**
     * Whether the framework accepts inbound panel work (UltiTools-Reborn#621). Set by
     * {@code UltiTools#onEnable} and cleared as the very first step of {@code UltiTools#onDisable},
     * before any manager shuts down and before the WebSocket is closed. Starts {@code true} so a
     * framework that was never disabled (and every unit test that does not touch it) behaves as
     * before. See {@link #isAcceptingPanelMessages()} for the full condition.
     */
    private static volatile boolean acceptingPanelMessages = true;

    /**
     * Starts accepting inbound panel work again; called by {@code UltiTools#onEnable}.
     *
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static void startAcceptingPanelMessages() {
        acceptingPanelMessages = true;
    }

    /**
     * Stops accepting inbound panel work (UltiTools-Reborn#621); called as the very first step of
     * {@code UltiTools#onDisable}. From this moment every scheduler hand-off reachable from the
     * UltiCloud connection's threads is dropped with one FINE line instead of scheduling a task for a
     * plugin that is being disabled.
     * <p>
     * As of 6.3.1 (protocol v2, maintainer decision of 2026-10-26) every request that changes server
     * state -- a command, a power action, a player action, a file write or delete, a
     * {@code server.properties} or configuration update, a module request, a credential rotation --
     * is refused from this moment with a failure reply saying the server is shutting down (a command
     * is acknowledged and answered with a failed {@code command.result}, so it is never run again
     * after a restart); read-only requests are still answered until {@code onDisable} closes the
     * connection. Before it returns, this method waits (at most {@value #PANEL_CHANGE_DRAIN_MS} ms)
     * for every state change that started earlier to finish -- including the work such a request
     * handed to another thread: a module responder running for a {@code module.request}, the
     * player-link handler running for a {@code player.link.completed} -- so each one, and its
     * action-log entry, is complete before {@code onDisable} closes the action log. A request handed
     * to the server thread is not waited for: it runs in a later tick, which never comes once the
     * server stops, and Bukkit cancels the task when UltiTools disables. Such a request accepted
     * before UltiTools' own disable starts -- while the server is already stopping, for example
     * while plugins loaded after UltiTools disable -- is not carried out and gets no result.
     * Must not be called by a thread inside such a change.
     *
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static void stopAcceptingPanelMessages() {
        acceptingPanelMessages = false;
        awaitPanelChanges();
    }

    /**
     * How long {@link #stopAcceptingPanelMessages()} waits for the state changes already running
     * (a file write, a {@code server.properties} update, a module responder or player-link handler
     * a request was handed to); each takes milliseconds, so the bound is reached only by a stalled
     * disk or a module handler that does not end.
     */
    static final long PANEL_CHANGE_DRAIN_MS = 5000L;

    /**
     * Guards {@link #panelChangesRunning}; {@link #stopAcceptingPanelMessages()} waits on it for the
     * count to reach zero (22-23 shutdown-window round). A count, not a read-write lock, because a
     * permit is returned by whichever thread ends the change: a request handed to a module
     * responder's worker or to the player-link handler thread keeps its permit until that work
     * ends (22-23 second verification).
     */
    private static final Object PANEL_CHANGES = new Object();

    /** How many permits {@link #enterPanelChange()} gave that were not returned yet. Guarded by {@link #PANEL_CHANGES}. */
    private static int panelChangesRunning;

    /** {@link ShutdownGate}'s production signal: {@link #enterPanelChange()} and {@link #exitPanelChange()}. */
    static final ShutdownGate.Signal PANEL_CHANGE_SIGNAL = new ShutdownGate.Signal() {
        @Override
        public boolean enter() {
            return enterPanelChange();
        }

        @Override
        public void exit() {
            exitPanelChange();
        }
    };

    /**
     * The shutdown signal of every remote state change ({@link ShutdownGate}): takes a permit and
     * returns {@code true} only while {@link #isAcceptingPanelMessages()} holds; the caller then
     * calls {@link #exitPanelChange()} exactly once, from any thread, when the change and its
     * action-log entry are done, including work it handed to another thread. Returns {@code false},
     * holding nothing, from the first step of {@code onDisable} on. Never blocks for longer than
     * another caller's check.
     *
     * @return whether the state change may run
     */
    static boolean enterPanelChange() {
        synchronized (PANEL_CHANGES) {
            // The flag is cleared before stopAcceptingPanelMessages takes this monitor, so a permit
            // given here is always one the wait sees.
            if (!isAcceptingPanelMessages()) {
                return false;
            }
            panelChangesRunning++;
            return true;
        }
    }

    /** Returns a permit {@link #enterPanelChange()} gave; may run on a thread other than the one that entered. */
    static void exitPanelChange() {
        synchronized (PANEL_CHANGES) {
            if (panelChangesRunning > 0) {
                panelChangesRunning--;
            }
            if (panelChangesRunning == 0) {
                PANEL_CHANGES.notifyAll();
            }
        }
    }

    private static void awaitPanelChanges() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PANEL_CHANGE_DRAIN_MS);
        synchronized (PANEL_CHANGES) {
            try {
                while (panelChangesRunning > 0) {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        PanelConnectionLog.log(Level.WARNING, "UltiCloud: a remote change was still running "
                                + PANEL_CHANGE_DRAIN_MS + " ms after UltiTools started disabling; it may finish "
                                + "after the action log closed.");
                        return;
                    }
                    PANEL_CHANGES.wait(remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether inbound panel work may run now: the accepting flag is set, and UltiTools is not being
     * disabled. Bukkit turns {@code isEnabled()} to {@code false} before it calls
     * {@code onDisable()}, so the second half covers the moment between the two; Paper refuses every
     * task of a disabled plugin with {@link IllegalPluginAccessException}. No instance (a unit test
     * without one) is not "disabling": the hand-off sites below need an instance anyway.
     *
     * @return {@code true} when inbound panel work may be dispatched and scheduled
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static boolean isAcceptingPanelMessages() {
        if (!acceptingPanelMessages) {
            return false;
        }
        UltiTools instance = UltiTools.getInstance();
        return instance == null || instance.isEnabled();
    }

    /**
     * The one check every scheduler hand-off reachable from the WebSocket thread goes through
     * (UltiTools-Reborn#621, swept by class: the inbound event publish, the queued config callbacks,
     * the remote command dispatch, and the monitoring tasks a completing handshake starts). Runs
     * {@code scheduling} -- exactly one call into the Bukkit scheduler -- only while
     * {@link #isAcceptingPanelMessages()} holds; otherwise, and when the plugin is disabled between
     * that check and the scheduler's own check (the scheduler then throws
     * {@link IllegalPluginAccessException}, the only exception caught here), the work is dropped
     * with one FINE line naming it. Nothing is lost that a stopping server could still use: the
     * scheduler would refuse the task anyway.
     *
     * @param what       what is being scheduled, for the FINE line
     * @param scheduling the scheduler call
     * @param <T>        what the scheduler call returns
     * @return what {@code scheduling} returned, or {@code null} when the work was dropped
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static <T> T scheduleUnlessDisabling(String what, Supplier<T> scheduling) {
        if (!isAcceptingPanelMessages()) {
            logDroppedWhileDisabling(what);
            return null;
        }
        try {
            return scheduling.get();
        } catch (IllegalPluginAccessException disabledMeanwhile) {
            logDroppedWhileDisabling(what);
            return null;
        }
    }

    private static void logDroppedWhileDisabling(String what) {
        PanelConnectionLog.log(Level.FINE, "Panel work '" + what + "' dropped: UltiTools is disabling");
    }

    /**
     * Test-only: installs {@code client} as {@link CloudSession#current()}'s legacy WebSocket client and
     * returns whatever was there before. Package-private, reached via reflection from
     * {@code CapabilityGateIndependenceTest} (package {@code manager}), which drives the legacy manager
     * wiring kept unreachable until 6.4.0 (S-67); {@code CloudSession} itself is package-private and cannot
     * be named from outside {@code utils}.
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
     * Loads the saved server credential into the current session without any network I/O.
     * No user token is loaded, refreshed or sent to a legacy registration endpoint.
     *
     * @return whether a saved server credential was adopted
     */
    public static boolean resumeSavedCredentialOnStartup() {
        try {
            ServerCredentialStore.LegacyTokenRemoval removal;
            try {
                removal = ServerCredentialStore.removeLegacyToken();
            } catch (com.ultikits.ultitools.exceptions.DataAccessException failure) {
                return false;
            }
            if (removal == ServerCredentialStore.LegacyTokenRemoval.REMOVED_UNLINKED) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, String.format(
                        UltiTools.getInstance().i18n(LEGACY_TOKEN_REMOVED_KEY),
                        CredentialStore.stateDirectory().resolve(CredentialStore.CREDENTIAL_FILE_NAME).toAbsolutePath()));
            }
            enableCloud();
            return CloudSession.current().loadServerCredential();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "UltiCloud credential could not be loaded (server will continue without cloud features): "
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * No-op: the protocol v2 server credential never expires and has no refresh token.
     * @deprecated No replacement is needed for non-expiring server credentials; removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    public static void startTokenRefreshScheduler() {
    }

    /** Stops device-login polling; the server credential never expires, so no refresh scheduler exists. */
    public static void stopCredentialSchedulers() {
        CloudSession.current().stopPolling();
    }

    /**
     * Login to UltiPanel using an existing token (a saved 6.3.0 token).
     * Registers or updates the server without needing username/password.
     * <p>
     * Package-private as of plan 16-09 (D-18) -- a public static method accepting a
     * {@link TokenEntity} is exactly the static bypass D-18's structural invariant forbids. Reached
     * only through {@link #resumeSavedCredentialOnStartup()}; the 6.3.0 magic-link login that also
     * called it was replaced by the protocol v2 device login in 6.3.1 (plan 22-01).
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

    /** Starts the supplied session's v2 connection; a stale or unlinked session does nothing. */
    static void initWebsocket(CloudSession session) throws IOException {
        session.startConnection();
    }

    /**
     * The single file {@code operation} name to {@link Capability} mapping, read by
     * {@code FileOperationManager}'s action-log recording (D-22, Plan 06-04 Task 1). The mapping is unchanged
     * from 6.3.0 and covers the operation names of the protocol v2 file requests ({@code files.read},
     * {@code files.list}, {@code files.write}, {@code files.delete}): {@code write} needs
     * {@link Capability#FILE_WRITE}, {@code delete} needs {@link Capability#FILE_DELETE}, and {@code read},
     * {@code list} (listing is reading) and an unrecognised or absent operation resolve to
     * {@link Capability#FILE_READ}, the most permitted of the three, so an unknown verb is still gated.
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
     * Whether {@code messageType} is a message type of the framework's own protocol: the single source
     * {@code PanelResponderRegistry.registerResponder} consults before letting a module claim a message type
     * (D-26, WIRE-16, Plan 06-08 Task 1).
     * <p>
     * As of 6.3.1 the answer comes from the UltiCloud protocol v2 message registry (the frozen 2.0.0 contract's
     * message table, for example {@code command.execute}, {@code files.read} or {@code module.request}); a
     * module's responder key ({@code <module>.<action>}, for example {@code ultimail.inbox.list}) is not one of
     * them, and neither is a removed protocol v1 type name. Exact {@code String} membership: no case folding,
     * no Unicode normalization.
     *
     * @param messageType the message type to check, possibly {@code null}
     * @return {@code true} if the framework's own protocol defines this exact type
     */
    public static boolean isFrameworkOwnedType(String messageType) {
        return messageType != null && AgentMessageTypes.types().contains(messageType);
    }

    /**
     * Bridges a panel message the framework has already handled onto the module-facing
     * {@link EventBus} (WIRE-16). This is the single publish site, invoked from the UltiCloud
     * protocol v2 module bridge ({@link ModuleRequestHandler}), which publishes one event per
     * module-owned request. It is kept because {@code PanelMessageEvent} is public since 6.3.0 and
     * a module subscribing to observe its own panel traffic keeps receiving it now that the
     * protocol v1 inbound path is removed (6.3.1).
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
    static void publishPanelMessageEvent(String type, JsonObject message, JsonObject data) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            EventBus eventBus = instance.getEventBus();
            if (eventBus == null) {
                return;
            }
            // Built here, on the dispatching thread, not inside the main-thread task: the event's
            // constructor copies data and the envelope now, so the event carries the request as
            // received whatever any other thread does to those objects before the task runs.
            PanelMessageEvent event = new PanelMessageEvent(type, data, message);
            scheduleUnlessDisabling("PanelMessageEvent " + type, () -> Bukkit.getScheduler().runTask(instance, () -> {
                // Two long reads and a comparison on the fast path — no allocation, no logging,
                // until the slow branch below is actually taken.
                long startNanos = System.nanoTime();
                eventBus.publish(event);
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
            }));
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[PanelMessageEvent] Failed to publish event for type " + type, e);
        }
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
     * firing every 5 seconds is the panel's only source of liveness updates — turning monitoring off
     * stops those updates, and whether the panel then shows the server as offline depends on the
     * panel's own status cache (the Worker's cache has no expiry), so the panel may keep showing the
     * last status. Either way the symptom (frozen dashboard values) points operators in the wrong
     * direction (they go check the network and the token, not the config). D-08 already
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

    /** Restarts the current session's v2 connection without loading or refreshing a user token. */
    public static void reinitWebSocket() {
        reinitWebSocket(CloudSession.current());
    }

    static void reinitWebSocket(CloudSession session) {
        synchronized (session) {
            if (!session.isCurrent() || session != CloudSession.current()) {
                return;
            }
            session.stopConnection();
            session.startConnection();
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
     * Also strips the log handler and the transport thread off the root logger (as of plan 16-10,
     * WR-01, this now happens INSIDE {@link CloudSession#invalidate()} rather than as a separate,
     * unlocked step before it -- see that method's own javadoc), and stops the server monitor and
     * the player-event listener — all part of what makes the statement "cloud features are
     * disabled" true. As of 16-08 Task 2 this no longer needs its own
     * the former global lifecycle lock: {@link CloudSession#invalidate()} and
     * {@link CloudSession#initializeManagers()} both synchronize on the session instance itself, so
     * wiring and teardown are mutually exclusive on that lock without a separate global one. See
     * the two review rounds on PR #264 for the original race this replaces.
     * <p>
     * <b>CR-01 (16-REVIEW-cloud.md):</b> {@link #disableCloud(CloudSession)} -- the overload internal
     * callers use -- returns the exact session that overload tore down, captured by the caller
     * before any teardown step runs. {@link CloudAuthManager#logout()} reads its disk-clear decision
     * off that return value rather than a second, independent call to {@link CloudSession#current()}
     * taken after teardown returns -- eliminating the specific race where a concurrent
     * {@code login()} installs a new session in the gap between those two reads (see
     * {@code CloudAuthManager#logout()}'s own javadoc for the full account). This particular
     * overload keeps its original {@code void} return -- {@code disableCloud()} was {@code public}
     * in the 6.2.5 release, and changing a released public method's return type is a genuine binary
     * incompatibility japicmp correctly rejects; there is no same-release exception available for a
     * signature that already shipped.
     */
    public static void disableCloud() {
        doDisableCloud(CloudSession.current());
    }

    /**
     * The {@code session}-targeted, {@link CloudSession}-returning form of {@link #disableCloud()}
     * (CR-01/WR-02, 16-REVIEW-cloud.md) -- package-private, since it is new-in-6.3.0 and reached
     * only by other members of this package ({@code CloudAuthManager#logout()},
     * {@link #reinitWebSocket(CloudSession)}'s budget-exhaustion branch).
     * <p>
     * {@link #reinitWebSocket(CloudSession)}'s budget-exhaustion branch calls this on the specific
     * session whose backoff ran out, rather than the no-arg {@link #disableCloud()} (which always
     * targets whatever {@link CloudSession#current()} happens to be at the moment it runs). A fresh
     * login racing in between that branch's own currency check and this call would otherwise let a
     * stale exhaustion event for an old session tear down an unrelated, brand-new one. Likewise,
     * {@code CloudAuthManager#logout()} calls this with {@code CloudSession.current()} captured at
     * its own call site -- immediately before teardown begins -- and acts on the returned reference
     * afterward rather than re-reading {@link CloudSession#current()} a second, independent time.
     *
     * @param session the session to invalidate and tear down
     * @return {@code session}, unchanged -- returned so the caller can act on exactly what was torn
     *         down without a second read of {@link CloudSession#current()}
     */
    static CloudSession disableCloud(CloudSession session) {
        return doDisableCloud(session);
    }

    /**
     * The actual teardown performed by {@link #disableCloud()} / {@link #disableCloud(CloudSession)}.
     * <p>
     * {@code session} is the caller's own choice of target, captured before this method does
     * anything else -- {@link CloudSession#invalidate()} below acts on exactly this reference, never
     * a fresh read of {@link CloudSession#current()} taken partway through teardown (CR-01).
     * <p>
     * <b>Round-1 external review finding, second pass (16-10, PR #464):</b> the two steps below act
     * on GLOBAL, session-independent singletons (exactly one server monitor and one player-event
     * manager for the whole plugin, not one per session) -- but "session-independent" does not mean
     * "safe to run unconditionally". If {@code session} has already been superseded by a newer one
     * by the time this runs (the same narrow window WR-02 already accounts for on the invalidation
     * half), and that newer session's own handshake has already wired these same managers up, an
     * unconditional stop here would tear down wiring that belongs to the session that is actually
     * current now -- with nothing left to ever restart it, since no further handshake is coming.
     * Gating both steps on {@code session} still being current closes that: a stale teardown call
     * for an already-superseded session leaves whatever the current session has wired alone.
     *
     * @param session the session to invalidate
     * @return {@code session}
     */
    private static CloudSession doDisableCloud(CloudSession session) {
        // Invalidating the session covers what used to be five separate steps: shut down the log
        // stream manager, stop the refresh schedule, stop the magic-link polling, close the
        // WebSocket client, and invalidate anything already in flight. One call, one lock (the
        // session's own), so none of those pieces can be caught mid-transition by a concurrent
        // activation. See CloudSession#invalidate()'s own javadoc for why this single call is
        // sufficient where the old code needed a careful multi-step order. This step always runs,
        // regardless of currency -- a session invalidating itself again after already being
        // superseded is a harmless no-op (see CloudSession#invalidate()'s own idempotency note).
        teardownStep("invalidating the cloud session (shuts down the log stream manager, stops the "
                + "refresh scheduler, the polling, closes the WebSocket client, and discards "
                + "anything already in flight)",
            session::invalidate);

        // Round-1 review, fourth pass (16-10, PR #464): the currency check and the two
        // global-manager teardown steps below must be ATOMIC with session replacement, not just
        // consulted once before them. A one-time, unlocked read here (the prior fix's shape) still
        // leaves a TOCTOU window: another login's startNew() -- and, immediately afterward, that
        // fresh session's own onOpen wiring the same global managers -- can land in the gap between
        // this check and the actual stopMonitoring()/shutdown() calls, so the stale teardown below
        // still runs and undoes wiring that belongs to a session that, by the time it executes,
        // already exists and is already current.
        //
        // synchronized (CloudSession.class) closes this window using the SAME monitor
        // CloudSession#startNew() itself already synchronizes on -- no new lock object, no
        // cross-package change. The reasoning: startNew() is the only thing that can make
        // `session != CloudSession.current()` become true, and a newly-installed session's own
        // wireManagers() call cannot begin before startNew() itself returns (the session object
        // does not exist yet). Serializing this whole check-then-act block against startNew()
        // therefore serializes it, transitively, against the new session's wiring too: either this
        // block finishes entirely before any subsequent startNew() call can even start (so nothing
        // new existed yet for the teardown to clobber), or a startNew() call already completed
        // before this block started (in which case the very first read inside it already sees the
        // new session as current and returns immediately, before touching either global manager).
        synchronized (CloudSession.class) {
            if (session != CloudSession.current()) {
                // session has already been superseded -- see the reasoning above for why the two
                // global-manager steps below must not run in that case.
                return session;
            }

            // Stop server monitoring. It carries its own ScheduledExecutorService (batch_update
            // every 5 seconds) plus two main-thread Bukkit scheduled tasks (1Hz TPS/CPU, a
            // world/player/plugin snapshot every 5 seconds). Before this line existed at all,
            // stopMonitoring() had no caller anywhere in src/main — written, tested, just never
            // wired up. Without stopping it, the main thread would keep iterating every world and
            // chunk every 5 seconds after "cloud features are disabled."
            teardownStep("stopping server monitor", () -> {
                if (UltiTools.getInstance().getServerMonitorManager() != null) {
                    UltiTools.getInstance().getServerMonitorManager().stopMonitoring();
                }
            });

            // Strip the player event listener. Still receiving player events after cloud is
            // disabled is pure waste — the isConnected() check inside the event handler only
            // suppresses sending a message; the listener itself keeps running. See issue #180.
            teardownStep("shutting down player event manager", () -> {
                if (UltiTools.getInstance().getPlayerEventManager() != null) {
                    UltiTools.getInstance().getPlayerEventManager().shutdown();
                }
            });
        }

        return session;
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
     * case -- most callers reach this with a perfectly good session already installed), the session
     * itself is not replaced; doing so for no reason would gratuitously drop its token and its
     * WebSocket client. Only a session that {@link CloudSession#invalidate()} already ran on
     * (typically {@code disableCloud()}, without a following {@code /ulticloud login}) gets
     * replaced.
     * <p>
     * <b>16-10 gap-closure addendum (round-12 review, PR #464):</b> {@link CloudSession#getBackoff()}
     * is reset unconditionally below, regardless of whether the session itself needed replacing --
     * unlike the token and the WebSocket client, reconnect-attempt history is NOT worth preserving
     * across a call to this method. Both of this method's own documented call sites -- cloud login
     * at server startup, and after a successful {@code /ulticloud login} -- are, by definition, a
     * fresh start of the connection-attempt cycle, matching the pre-6.3.0 global backoff's own
     * behaviour (every {@code enableCloud()} call reset it). Without this, {@code UltiTools#onDisable()}
     * stops the current session's schedulers and WebSocket client but never invalidates the session
     * itself (a deliberate choice -- a full teardown at JVM shutdown is pointless work), so a plugin
     * disabled and re-enabled in the same classloader (e.g. {@code /reload}) resumes with whatever
     * backoff attempt count it had accumulated before the disable, exhausting its retry budget
     * prematurely instead of getting the fresh budget a restart implies.
     */
    public static void enableCloud() {
        if (!CloudSession.current().isCurrent()) {
            CloudSession.startNew();
        }
        CloudSession.current().getBackoff().reset();
    }

    /** Lets a test assert whether the state machine is currently enabled -- i.e. whether the current session is still current. */
    static boolean isCloudEnabled() {
        return CloudSession.current().isCurrent();
    }

    /**
     * Stops the v2 connection and invalidates pending login results during plugin shutdown.
     * <p>
     * The pending reliable-delivery state is written before this returns: {@code invalidate()}
     * flushes the outbox and the command-dedupe set (D-15), so a stop persists what the 30 s tick
     * has not written yet.
     */
    public static void stopWebsocket() {
        CloudSession.current().invalidate();
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

}
