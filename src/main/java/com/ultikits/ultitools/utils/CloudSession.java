package com.ultikits.ultitools.utils;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import com.ultikits.ultitools.websocket.PanelConnectionLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.websocket.ExponentialBackoffStrategy;

/**
 * The single owner of one UltiCloud login's entire lifetime.
 * <p>
 * A session owns the protocol v2 server credential, the device-login poll it started, the reconnect
 * backoff state and its agent connection (D-16). {@code login} constructs a new
 * session via {@link #startNew()}; {@code logout} replaces the current one the same way. Everything
 * the replaced session started -- an in-flight poll, a reconnect attempt --
 * lapses on its own once {@link #invalidate()} has run, because {@link #commitCredential(ServerCredential)}
 * and every gate this class exposes check this <b>specific instance's</b> {@link #invalidated} flag,
 * never a shared counter another session's activity could also advance. (The 6.3.0 user token, its
 * store and its refresh scheduler were removed in 6.3.1, plan 22-24: protocol v2 never sends it.)
 * <p>
 * This replaces the credential-generation convention {@code CloudAuthManager} used before 6.3.0.
 * That convention's own javadoc stated the invariant this class now enforces, and is worth quoting
 * verbatim because the reason has not changed, only the mechanism that satisfies it:
 * <blockquote>
 * cancellation is not invalidation. {@code stopTokenRefreshScheduler()} and {@code stopPolling()}
 * both use {@code cancel(false)} plus {@code shutdown()}, and both only promise not to schedule a
 * new execution -- neither constrains a task that has already entered an HTTP request. [...] every
 * in-flight asynchronous credential operation records the generation it saw when it started, and
 * compares against it [...] before committing its result; the teardown path [...] advances the
 * generation, so any late-arriving result is discarded unconditionally.
 * </blockquote>
 * A generation counter can only tell a late result that it is late; it cannot stop the scheduler,
 * the WebSocket client, or the panel-manager wiring an old login started from outliving the
 * credential that authorised them. Session identity can, and does: invalidating a session tears
 * down everything it owns in one call, so no separate teardown-ordering discipline is needed for
 * any of the five timing windows issue #298 named.
 * <p>
 * <b>Global lock order (16-10 gap-closure addendum, issues #465/#466).</b> The locks involved
 * across this class, {@link CloudAuthManager} and {@link PluginInitiationUtils}, and every method
 * that takes more than one of them <b>must</b> acquire them in this fixed order -- outer to inner:
 * <ol>
 * <li>{@code CloudAuthManager.class} -- held for the duration of
 * {@link CloudAuthManager#logout()}, {@link CloudAuthManager#logoutReporting(java.util.function.Consumer)}
 * and {@link CloudAuthManager#reconnect()} (all three are {@code synchronized} methods). They DO
 * nest it with the locks below, in this list's order: logout's and logoutReporting's own
 * {@code synchronized (CloudSession.class)} block calls {@link #invalidate()} on
 * the session monitor inside it, and reconnect takes the session monitor inside
 * {@code CloudSession#reconnectNow()}. No path in this package acquires them the other way
 * round.</li>
 * <li>{@code CloudSession.class} -- held by {@link #startNew()} and by
 * {@link CloudAuthManager#login} (16-10 gap-closure addendum, issue #466) for its own
 * re-check-through-replacement region.</li>
 * <li>a specific session's own monitor ({@code synchronized (this)} / {@code synchronized(session)})
 * -- held by {@link #invalidate()}, {@link #stopPolling()}, (protocol v2, plan 22-01)
 * {@link #commitCredential(ServerCredential)},
 * {@link #beginDeviceLogin}, {@link #startDevicePolling} and the device-step scheduling they share,
 * and by every caller in {@code PluginInitiationUtils} that needs to
 * read-then-act on a session's currency ({@code reinitWebSocket}'s second confirmation,
 * {@code doDisableCloud}'s two global-manager steps).</li>
 * <li>{@link CloudStateStore}'s JVM-wide write lock (plan 22-07) -- taken by every load, flush
 * and detach of {@code cloud-outbox.json}, always below the session monitor: {@link
 * #invalidate()}'s and the shutdown flush's write cannot interleave with the 30 s task's, which
 * holds only this lock and never the session monitor. Its inner locks are the {@link CloudOutbox}
 * and {@link CommandDedupeSet} monitors, which are leaves.</li>
 * <li>The {@link AgentConnection} monitor and {@code CredentialStore.LOCK}, taken by
 * {@link ServerCredentialStore}, are siblings: never nested in one another, always below the
 * session monitor. AgentConnection never calls out while holding its
 * monitor. Its scheduler callbacks take the session monitor first and reject invalidated or
 * replaced sessions and replaced connections before acting.</li>
 * <li>(plan 22-20) The private lock of the connection's {@link AgentProtocol} -- below the session
 * monitor (taken inside it by {@link #offerReliableEvent} and {@link #stopConnection()}) and above the
 * {@link AgentConnection} monitor and the {@link CloudOutbox} and {@link CommandDedupeSet} leaf
 * monitors, which it takes while handing a frame to the socket or applying the welcome's limits. It
 * is never held while calling a listener or this session, so the inbound path -- which reads the
 * current protocol under the session monitor, then releases it before {@link AgentProtocol#onText}
 * -- cannot invert it.</li>
 * </ol>
 * Teardown under any of these locks uses only non-blocking {@code AgentConnection.stop()} and
 * executor {@code shutdown()}. It never waits for the agent thread (no awaitTermination, Future.get,
 * join, connectBlocking or closeBlocking): that thread may itself be waiting for the session monitor.
 * {@link #startNew()}'s own order -- {@code CloudSession.class} outer, the replaced session's
 * monitor inner -- is the anchor every other method's order is chosen to match; inverting it
 * anywhere (acquiring a session monitor first, then trying to enter a {@code synchronized
 * (CloudSession.class)} block while still holding it) is exactly the AB-BA deadlock issue #466
 * fixed. No method in this package acquires a session monitor and then blocks trying to acquire
 * {@code CloudSession.class} it does not already hold. {@link #commitCredential(ServerCredential)}
 * writes through {@link ServerCredentialStore}, so {@code CredentialStore}'s own lock is taken inside
 * the session monitor -- never the other way round.
 * <p>
 * <b>Protocol v2 device login (plan 22-01).</b> A session also owns one RFC 8628 device
 * authorization: {@link #beginDeviceLogin} requests the device code and {@link #startDevicePolling}
 * polls for the owner's confirmation, both as steps on this session's own poll executor, so a
 * replaced or logged-out session's late steps lapse by the same {@link #invalidated} check
 * {@link #commitCredential(ServerCredential)} uses. The granted {@code ucs_} credential is persisted only through
 * {@link #commitCredential(ServerCredential)}.
 *
 * @since 6.3.0
 */
final class CloudSession {

    // All field declarations precede all methods (PMD FieldDeclarationsShouldBeAtStartOfClass) --
    // moved here in plan 16-10 (Gate 1); no field's own meaning changed, only its position.

    /**
     * The global cap on outer reinit attempts. Once exceeded, the state machine enters a terminal
     * state and only {@code /ulticloud login} or a restart recovers it. Package-private (not
     * private) so {@link PluginInitiationUtils#reinitWebSocket(CloudSession)} can name it in its own
     * "gave up after %d attempts" log line -- the two classes are in the same package.
     */
    static final int MAX_REINIT_ATTEMPTS = 10;

    /**
     * How often the session persists its reliable-delivery state (D-15): the outbox and the
     * command-dedupe set are written to {@code .ultikits/cloud-outbox.json} only when one of them
     * changed since the last write, but the change itself is observed on this cadence.
     */
    private static final long STATE_FLUSH_INTERVAL_MS = 30_000L;

    /**
     * The language-catalogue key of the one SEVERE line printed when the gateway's welcome names a
     * different server id than the linked one (contract answer 21, SEMANTICS 16).
     */
    static final String SERVER_ID_MISMATCH_KEY = "UltiCloud answered this server's connection with a different "
            + "server ID than the one it is linked to. Reconnecting has stopped and the credential is kept. "
            + "Contact UltiCloud support, or run /ulticloud logout and then /ulticloud login to link this server again.";

    /** Added to the device poll interval for each {@code slow_down} answer (RFC 8628 section 3.5). */
    private static final long DEVICE_SLOW_DOWN_INCREMENT_MS = 5_000L;

    /** Test seam: replaces the device-login REST client of every session; {@code null} = default. */
    private static volatile DeviceAuthorizationClient deviceClientOverride;
    /** Test seam: replaces the device-step scheduler of every session; {@code null} = poll executor. */
    private static volatile DeviceStepScheduler deviceSchedulerOverride;
    /** Test seam: replaces the device-login clock of every session; {@code null} = wall clock. */
    private static volatile LongSupplier deviceClockOverride;

    /** The current session. Never {@code null} -- initialised eagerly so every static facade
     * method on {@link CloudAuthManager} always has something to delegate to, even before any
     * login has ever happened. */
    private static volatile CloudSession current = new CloudSession();

    private volatile boolean invalidated;

    /**
     * {@code true} if {@link #invalidate()} found -- and cancelled -- a pending authentication still
     * in flight on this session (as of 6.3.1 the device-code request or poll; a magic-link poll in
     * 6.3.0), and that fact has not yet been reported and reset by
     * {@link #consumePendingCancellation()}.
     * <p>
     * <b>Real-machine UAT fix, plan 16-08 ({@code ultitools.ulticloud.logout.neg-mid-poll}).</b>
     * Cancelling a pending authentication attempt IS a real logout, even though it never committed a
     * credential. Without this field, {@code /ulticloud logout} run while a login's poll is still
     * running reported "Not currently logged in to UltiCloud." even though it genuinely tore the
     * poll down -- observed on a real Paper 1.21.11 server. The safety half of this row (a stale
     * approval landing after this logout must not reactivate the server) was never in question:
     * {@link #commitCredential(ServerCredential)}'s own {@link #invalidated} check rejects it
     * regardless of this field. Only the reported OUTCOME of the logout that cancelled the poll was
     * wrong.
     * <p>
     * Captured in {@link #invalidate()}, immediately before {@link #stopPolling()} clears
     * {@link #pollTask} -- reading {@link #pollTask} any later would always see {@code null} and this
     * field could never become {@code true}. Reset by {@link #consumePendingCancellation()}, so a
     * second, independent {@code logout()} call on this same (already-invalidated) session instance
     * -- e.g. a second {@code /ulticloud logout} before any new login replaces it -- does not keep
     * reporting success for a poll this session already reported cancelling once.
     */
    private volatile boolean pendingAuthenticationCancelled;

    /**
     * The protocol v2 server credential this session holds, or {@code null} -- the one owner of the current server
     * credential (plan 22-21 fix). Every change of the stored {@code cloud_credential} entry is made under this
     * session's monitor together with this field: {@link #loadServerCredential()} (adopt from disk),
     * {@link #commitCredential(ServerCredential)} (device login), {@link #rotateCredential(String, String)}
     * ({@code credential.rotate}, disk first), {@link #completeCredentialLogout(ServerCredential)} and the listener's
     * rejection path (delete-if-matches, then cleared). Every reader in the session -- the bearer of each attempt, the
     * protocol's server id, the linked checks -- and {@code CloudAuthManager}'s status, login and logout read this
     * field; they read the file only while the session holds no credential.
     */
    private volatile ServerCredential credential;

    /**
     * The bounded buffer of unacknowledged reliable events of this session's credential, or
     * {@code null} while no credential is adopted (or after the identity was discarded).
     * Written only while holding this session's monitor; volatile because the 30 s flush task on
     * the agent thread reads it without taking that monitor.
     */
    private volatile CloudOutbox cloudOutbox;

    /** The recently seen command ids of this session's credential, or {@code null}. */
    private volatile CommandDedupeSet commandDedupe;

    /** The state file of this session's credential, or {@code null}. */
    private volatile CloudStateStore cloudState;

    /** The server id whose buffered state the three fields above hold, or {@code null}. */
    private volatile String cloudStateServerId;

    /**
     * The id set, state file and server id above as one reference (plan 22-21), so the shared
     * {@link SessionDedupeLedger} never sees one session's set with another identity's file. Set and
     * cleared with them, under this session's monitor; read without it.
     */
    private volatile SessionDedupeLedger.Target ledgerTarget;

    /** The pending 30 s state-flush task of this session's connection, or {@code null}. */
    private volatile ScheduledFuture<?> stateFlushTask;

    /** Whether the current state-write failure streak already logged its one WARNING (T-22-31). */
    private volatile boolean stateFlushFailureLogged;

    private AgentConnection agentConnection;
    /**
     * The protocol layer of the current connection's open attempt (plan 22-20), or {@code null}
     * between attempts. One per upgraded socket: created when the upgrade is accepted, closed when
     * the connection leaves OPEN. Written under this session's monitor.
     */
    private volatile AgentProtocol agentProtocol;
    private ScheduledExecutorService agentExecutor;
    private LongSupplier agentClock = System::currentTimeMillis;
    private static volatile URI agentEndpointOverride;
    private static volatile ReconnectPolicy agentPolicyOverride;
    private static volatile AgentConnection.Timing agentTimingOverride;
    private static volatile java.util.function.Supplier<ScheduledExecutorService> agentExecutorOverride;
    private static volatile AgentConnection.SocketFactory agentSocketsOverride;
    private static volatile LongSupplier agentClockOverride;

    /** The REST client of the device authorization grant. */
    private final DeviceAuthorizationClient deviceClient = new DeviceAuthorizationClient();

    private ScheduledExecutorService pollExecutor;
    private ScheduledFuture<?> pollTask;

    /**
     * The global budget and backoff for this session's outer reconnection (reinit loop).
     * <p>
     * The client's own limit of 5 attempts is a <b>per-instance</b> cap, and
     * {@code PluginInitiationUtils.reinitWebSocket} builds a brand-new client instance every time --
     * so the per-instance cap places no constraint at all on the whole, which is exactly how the
     * loop became unbounded (issue #181). This strategy spans client instances instead; only one
     * successful {@code onOpen} resets it. Being a fresh field on every new session is also what
     * makes D-16's "the old session's backoff counter does not carry over" true for free -- a new
     * session's budget starts at zero attempts by construction, with no reset call needed.
     */
    private final ExponentialBackoffStrategy backoff = ExponentialBackoffStrategy.withMaxAttempts(MAX_REINIT_ATTEMPTS);

    // No explicit constructor: this class needs none, and the implicit no-arg constructor the
    // compiler generates is already package-private (matching this top-level class's own default
    // visibility) -- exactly what an explicit `CloudSession() {}` used to spell out redundantly
    // (PMD UnnecessaryConstructor, plan 16-10 Gate 1). "Constructed only by startNew() and by tests
    // in this package" is a fact about who calls it, not about the constructor's own accessibility,
    // and is already stated in this class's own javadoc above.

    /**
     * The session every {@code CloudAuthManager}/{@code PluginInitiationUtils} static facade
     * method currently delegates to.
     *
     * @return the current session, never {@code null}
     */
    static CloudSession current() {
        return current;
    }

    /**
     * Constructs a new session, installs it as {@link #current()}, and invalidates whatever session
     * was current before -- the single operation both {@code login} and {@code logout} perform
     * (D-16). Invalidating the previous session happens <b>after</b> it has been replaced as
     * {@link #current()}, but that ordering is not itself load-bearing: {@link #invalidate()}'s own
     * gate is this specific instance's {@link #invalidated} flag, not a comparison against
     * {@link #current()}, so a caller already holding a reference to the old session sees it become
     * invalid regardless of when the swap becomes visible to a third reader.
     *
     * @return the new, now-current session
     */
    static synchronized CloudSession startNew() {
        CloudSession previous = current;
        CloudSession next = new CloudSession();
        current = next;
        previous.invalidate();
        return next;
    }

    /**
     * Test-only: replaces {@link #current()} with a brand-new, non-invalidated session, without
     * invalidating whatever was current before. Production code has no equivalent -- every real
     * replacement goes through {@link #startNew()}, which does invalidate the session it replaces.
     * Exists only because this class's static {@link #current} field is JVM-wide and this module's
     * Surefire configuration runs with no {@code forkCount} (issue #250), so a test suite that
     * leaves a stale, already-invalidated session installed as current would leak that state into
     * every test class that runs afterward in the same JVM.
     */
    static void resetForTesting() {
        current.stopConnection();
        current.stopPolling();
        current = new CloudSession();
        setDeviceSeamsForTesting(null, null, null);
        setAgentEndpointOverrideForTesting(null);
        setAgentTimingOverrideForTesting(null, null);
        setAgentRuntimeForTesting(null, null, null);
    }

    // ---- Invalidate: the contract D-18 requires ----

    /**
     * Marks this session invalid and tears down everything it owns: the log stream manager (as of
     * plan 16-10, WR-01), the device-login poll, the pending reliable-delivery state (D-15) and the
     * agent connection. Safe to call on a session that never started any of those and safe
     * to call more than once -- both are exercised directly by this class's own tests, independently
     * of the static {@link #current()} holder.
     * <p>
     * <b>Real-machine UAT fix, plan 16-08:</b> {@link #pendingAuthenticationCancelled} is captured
     * here -- reading {@link #pollTask} BEFORE {@link #stopPolling()} below clears it -- so
     * {@link #hasAnythingToClear()} can report that this call genuinely cancelled a real, in-flight
     * authentication attempt even though no credential was ever committed. See that
     * field's own javadoc for the full account.
     */
    synchronized void invalidate() {
        invalidated = true;
        shutdownLogStreamManager();
        if (pollTask != null) {
            pendingAuthenticationCancelled = true;
        }
        stopPolling();
        // D-15: a pending outbox or command-dedupe change must reach the disk before the
        // connection that owns it goes away -- this is also the flush the plugin's shutdown
        // performs, through PluginInitiationUtils#stopWebsocket(). It is a no-op on a session
        // whose identity was discarded, because that store is detached.
        flushCloudState();
        stopConnection();
    }

    /**
     * Shuts down the framework's log stream manager, if one is configured. Extracted so
     * {@link #invalidate()} reads as a flat list of teardown steps, and tolerant of a {@code null}
     * manager (unmocked in the majority of this package's tests): no manager is simply nothing to
     * tear down. As of 6.3.1 no framework path wires the log stream manager (its protocol v1 wiring
     * was removed, plan 22-24); the shutdown still runs so a module that wired it through the kept
     * public API is detached on logout as in 6.3.0.
     */
    private void shutdownLogStreamManager() {
        UltiTools plugin = UltiTools.getInstance();
        if (plugin == null) {
            return;
        }
        try {
            com.ultikits.ultitools.manager.LogStreamManager logStreamManager = plugin.getLogStreamManager();
            if (logStreamManager != null) {
                logStreamManager.shutdown();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE,
                "Error shutting down log stream manager during session invalidation: " + e.getMessage());
        }
    }

    /**
     * Test-only: marks this session invalidated without touching the poller or the connection -- the
     * flag-setting half of {@link #invalidate()}, isolated so a test can establish "logout has been
     * decided" as a precondition and then permute the mechanical teardown actions
     * ({@link #stopPolling()}, {@link #stopConnection()}) in any order afterward, proving their
     * relative order does not change the outcome (D-16/D-18, issue #298's fourth acceptance
     * criterion). Production code never calls this in isolation;
     * {@link #invalidate()} always sets the same flag together with the cleanup, in one atomic call.
     */
    synchronized void markInvalidatedForTesting() {
        invalidated = true;
    }

    /**
     * @return {@code true} if this session has not been invalidated
     */
    boolean isCurrent() {
        return !invalidated;
    }

    /**
     * @return {@code true} if this session's teardown is responsible for something: this session's
     *         server {@link #credential}, or (real-machine UAT fix, plan 16-08) a device-login poll
     *         that was still in flight and just got cancelled by {@link #invalidate()} (see
     *         {@link #pendingAuthenticationCancelled}'s own javadoc). {@code false} only if both are
     *         absent. {@code CloudAuthManager#logout()} combines this with the credential stored in
     *         {@code credentials.json}, which it reads when the session holds none.
     */
    boolean hasAnythingToClear() {
        return credential != null || pendingAuthenticationCancelled;
    }

    synchronized boolean consumePendingCancellation() {
        boolean cancelled = pendingAuthenticationCancelled;
        pendingAuthenticationCancelled = false;
        return cancelled;
    }

    synchronized void completeCredentialLogout(ServerCredential retiring) {
        try {
            ServerCredentialStore.deleteIfMatches(retiring.getCredentialId());
        } finally {
            credential = null;
        }
    }

    /**
     * @return this session's own reconnect backoff strategy -- never shared with any other session
     */
    ExponentialBackoffStrategy getBackoff() {
        return backoff;
    }

    /** Stops this session's device-login steps (the request and the polling) and their executor. */
    synchronized void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (pollExecutor != null) {
            pollExecutor.shutdown();
            pollExecutor = null;
        }
    }

    // ---- Agent connection (protocol v2) ----

    /**
     * Loads framework-owned state without rewriting it or performing any network request.
     * <p>
     * Adopting the credential also adopts its reliable-delivery state (D-15): the outbox and the
     * command-dedupe set are created and loaded from {@code .ultikits/cloud-outbox.json} here, so
     * an event captured before a restart is still in the buffer afterwards.
     */
    synchronized boolean loadServerCredential() {
        if (invalidated || this != current()) {
            return false;
        }
        ServerCredential loaded = ServerCredentialStore.load();
        // Assigned even when the store is empty, exactly as before: a session whose saved
        // credential was deleted out of band must not keep reporting its own stale copy as linked.
        credential = loaded;
        if (loaded == null) {
            return false;
        }
        adoptCloudState(loaded);
        return true;
    }

    /** Starts this session's single connection owner; repeated calls never restart a terminal state. */
    synchronized void startConnection() {
        if (invalidated || this != current() || credential == null || agentConnection != null) {
            return;
        }
        URI endpoint = agentEndpointOverride != null ? agentEndpointOverride
                : AgentEndpoint.fromApiUrl(HttpRequestUtils.getBaseUrl());
        agentExecutor = agentExecutorOverride != null ? agentExecutorOverride.get()
                : Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "UltiTools-Cloud-Agent");
            thread.setDaemon(true);
            return thread;
        });
        SessionAgentListener listener = new SessionAgentListener();
        ReconnectPolicy policy = agentPolicyOverride != null ? agentPolicyOverride
                : new ReconnectPolicy(() -> ThreadLocalRandom.current().nextDouble());
        AgentConnection.Timing timing = agentTimingOverride != null ? agentTimingOverride
                : AgentConnection.Timing.defaults();
        agentClock = agentClockOverride != null ? agentClockOverride : System::currentTimeMillis;
        AgentConnection.SocketFactory sockets = agentSocketsOverride != null ? agentSocketsOverride
                : AgentConnection.realSockets(AgentConnection.CONNECT_TIMEOUT_MS, PanelConnectionLog::log);
        agentConnection = new AgentConnection(endpoint, listener::readBearer,
                CloudAuthManager.runningFrameworkVersion(), agentExecutor, listener,
                sockets, policy, agentClock, timing, PanelConnectionLog::log);
        listener.connection = agentConnection;
        agentExecutor.execute(listener.connection::start);
        // The state cadence lives on the connection's own scheduler (D-15), so it exists exactly
        // as long as this session owns a connection and is cancelled with it.
        scheduleStateFlush();
    }

    /** Stops and detaches the connection without ever waiting for its scheduler thread. */
    synchronized void stopConnection() {
        cancelStateFlushTask();
        AgentConnection previous = agentConnection;
        agentConnection = null;
        AgentProtocol protocol = agentProtocol;
        agentProtocol = null;
        if (protocol != null) {
            protocol.onClose();
        }
        if (previous != null) {
            previous.stop();
        }
        if (agentExecutor != null) {
            agentExecutor.shutdown();
            agentExecutor = null;
        }
    }

    // ---- Reliable-delivery state (protocol v2, plan 22-07) ----

    /**
     * Adds one reliable event to this session's outbox, if it can be captured at all (D-14, S-16):
     * a session that holds no credential returns silently, so an unlinked server writes no state
     * file, and so does one whose operator turned {@code ultipanel.capabilities.player-events}
     * off. The connection's own state is deliberately not consulted: buffering while disconnected
     * is the point of the outbox.
     *
     * @param type    the envelope type, for example {@code player.join}
     * @param payload the event payload
     */
    synchronized void offerReliableEvent(String type, JsonObject payload) {
        CloudOutbox buffer = cloudOutbox;
        if (buffer == null || !Capability.PLAYER_EVENTS.isEnabled()) {
            return;
        }
        CloudOutbox.Entry entry = buffer.enqueue(type, payload);
        // Established: send it now with its own id; it stays in the outbox until acked (D-11).
        AgentProtocol protocol = agentProtocol;
        if (protocol != null) {
            protocol.sendReliable(entry);
        }
    }

    /**
     * @return the protocol layer of the current connection's open attempt, or {@code null} between
     *         attempts (plan 22-20; 22-21 registers the track handlers on its router)
     */
    AgentProtocol agentProtocol() {
        return agentProtocol;
    }

    /**
     * Builds the protocol layer of one upgraded attempt of {@code connection} (plan 22-20) and, while
     * the plugin's {@link CloudFeatures} are installed, registers every inbound handler on it (plan
     * 22-21): the hello declares what the operator's switches allow ({@link AgentCapabilities}, S-65),
     * reports the operator settings, and the router re-checks the switch of every request (S-66).
     * Without installed features (a session driven by a test alone) the hello declares the framework
     * capabilities of {@link #frameworkCapabilities()}, reports power actions as off, and the operator
     * gate admits every capability the welcome accepted.
     *
     * @param connection the attempt's connection
     * @return the protocol, not yet opened
     */
    private AgentProtocol newProtocol(AgentConnection connection) {
        ServerCredential linked = credential;
        String serverId = linked == null ? null : linked.getServerId();
        CloudFeatures features = CloudFeatures.installed();
        if (features == null) {
            return new AgentProtocol(connection, cloudOutbox, commandDedupe, serverId,
                    CloudSession::frameworkCapabilities, CloudSession::frameworkSettings, capability -> true,
                    capability -> "This capability is not available on this server.", agentClock);
        }
        AgentCapabilities capabilities = features.capabilities();
        AgentProtocol protocol = new AgentProtocol(connection, cloudOutbox, commandDedupe, serverId,
                capabilities::helloCapabilities, features::helloSettings, capabilities::enabled,
                capabilities::refusal, agentClock);
        // The inbound handlers of this connection, registered before its hello is sent. A rotation goes through
        // this session, the one owner of the current credential (plan 22-21 fix).
        features.register(protocol.router(), protocol.channel(), protocol, this::rotateCredential, agentExecutor);
        return protocol;
    }

    /**
     * The hello's capabilities from the operator's {@link Capability} switches (S-65, without the
     * error-report and module entries 22-21 adds): {@code power} and {@code link} are always
     * declared, {@code backups} never.
     *
     * @return the capability names, in a stable order
     */
    static List<String> frameworkCapabilities() {
        List<String> names = new ArrayList<>();
        addIf(names, Capability.MONITORING, "telemetry");
        addIf(names, Capability.LOGS, "logs");
        addIf(names, Capability.PLAYER_EVENTS, "events:players", "events:commands");
        addIf(names, Capability.COMMANDS, "commands");
        addIf(names, Capability.PLAYER_ACTIONS, "players:actions");
        addIf(names, Capability.FILE_READ, "files:read", "config:read");
        addIf(names, Capability.FILE_WRITE, "files:write", "config:write");
        addIf(names, Capability.FILE_DELETE, "files:delete");
        addIf(names, Capability.SERVER_PROPERTIES, "properties:read", "properties:write");
        names.add("power");
        names.add("link");
        return names;
    }

    private static void addIf(List<String> names, Capability capability, String... contractNames) {
        if (capability.isEnabled()) {
            names.addAll(Arrays.asList(contractNames));
        }
    }

    /** @return the hello's operator settings until 22-21: power actions reported as off */
    static JsonObject frameworkSettings() {
        JsonObject settings = new JsonObject();
        settings.addProperty("powerActionsEnabled", false);
        return settings;
    }

    /**
     * @return this session's outbox, or {@code null} while no credential is adopted (plan 22-08
     *         sends and acknowledges its entries)
     */
    CloudOutbox cloudOutbox() {
        return cloudOutbox;
    }

    /**
     * @return this session's command-dedupe set, or {@code null} while no credential is adopted
     *         (plan 22-09 applies it to inbound command messages)
     */
    CommandDedupeSet commandDedupe() {
        return commandDedupe;
    }

    /** @return this session's state file writer, or {@code null} while no credential is adopted */
    CloudStateStore cloudStateStore() {
        return cloudState;
    }

    /**
     * @return this session's id set, state file and server id as one value for the shared ledger, or {@code null}
     *         while no credential is adopted (plan 22-21)
     */
    SessionDedupeLedger.Target ledgerTarget() {
        return ledgerTarget;
    }

    /**
     * Writes the pending outbox and command-dedupe changes, if there are any. Called from
     * {@link #invalidate()} -- which {@code PluginInitiationUtils#stopWebsocket()} runs at plugin
     * shutdown -- so a stop persists what a 30 s tick has not yet written (D-15). A failure is
     * logged and swallowed: teardown must not be blocked by an unwritable state file.
     */
    synchronized void flushCloudState() {
        CloudStateStore store = cloudState;
        String serverId = cloudStateServerId;
        if (store == null || serverId == null) {
            return;
        }
        flushStateQuietly(store, serverId);
    }

    /**
     * Forgets this session's reliable-delivery state for good (S-12, S-32): the 30 s task is
     * cancelled, the store is detached and its file deleted under the store's write lock, and the
     * in-memory buffer and command-id set are cleared. Every later flush of that store is a no-op,
     * so neither the tick, nor {@link #invalidate()}, nor the shutdown flush can bring
     * {@code cloud-outbox.json} back for an identity that no longer exists. A new credential
     * adopted afterwards starts from a fresh store and an empty file.
     */
    synchronized void discardCloudState() {
        cancelStateFlushTask();
        ledgerTarget = null;
        CloudStateStore store = cloudState;
        cloudState = null;
        cloudStateServerId = null;
        CloudOutbox buffer = cloudOutbox;
        cloudOutbox = null;
        CommandDedupeSet dedupe = commandDedupe;
        commandDedupe = null;
        if (store != null) {
            try {
                store.detachAndDelete();
            } catch (RuntimeException failure) {
                PanelConnectionLog.log(Level.WARNING,
                        "UltiCloud: the buffered state file could not be deleted (" + failure.getMessage()
                        + "). Delete it by hand; it is no longer used.");
            }
        }
        if (buffer != null) {
            buffer.clear();
        }
        if (dedupe != null) {
            dedupe.clear();
        }
    }

    /**
     * Creates and loads this session's reliable-delivery state for {@code adopted} (D-15). The
     * caller holds this session's monitor. A state file written by hand for another identity is
     * discarded by {@link CloudStateStore#load(String)}, and an unresolvable state location leaves
     * the session without persistence rather than failing the login.
     *
     * @param adopted the credential this session just adopted
     */
    private void adoptCloudState(ServerCredential adopted) {
        if (cloudState != null) {
            if (adopted.getServerId().equals(cloudStateServerId)) {
                return;
            }
            // The buffered state belongs to the identity this session is leaving behind.
            discardCloudState();
        }
        CloudOutbox buffer = new CloudOutbox(CloudOutbox.DEFAULT_CAPACITY, System::currentTimeMillis);
        CommandDedupeSet dedupe = new CommandDedupeSet(CommandDedupeSet.DEFAULT_DEDUPE_MAX,
                CommandDedupeSet.DEFAULT_DEDUPE_TTL_MS, System::currentTimeMillis);
        CloudStateStore store;
        try {
            store = new CloudStateStore(CloudStateStore.defaultPath(), buffer, dedupe);
        } catch (RuntimeException failure) {
            PanelConnectionLog.log(Level.WARNING,
                    "UltiCloud: the reliable delivery state has nowhere to live (" + failure.getMessage()
                    + "). Buffered player join/leave events are kept in memory only.");
            return;
        }
        cloudOutbox = buffer;
        commandDedupe = dedupe;
        cloudState = store;
        cloudStateServerId = adopted.getServerId();
        store.load(adopted.getServerId());
        ledgerTarget = new SessionDedupeLedger.Target(dedupe, store, adopted.getServerId());
    }

    /**
     * Arms the state cadence from whatever this session currently holds; called once per
     * connection, since the flush task lives on the connection's own executor.
     */
    private void scheduleStateFlush() {
        CloudStateStore store = cloudState;
        String serverId = cloudStateServerId;
        ScheduledExecutorService executor = agentExecutor;
        if (store == null || serverId == null || executor == null) {
            return;
        }
        scheduleStateFlush(store, serverId, executor);
    }

    private void scheduleStateFlush(CloudStateStore store, String serverId,
            ScheduledExecutorService executor) {
        try {
            stateFlushTask = executor.schedule(new StateFlushTick(store, serverId, executor),
                    STATE_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // The connection's executor is already shut down; the shutdown flush has run.
            stateFlushTask = null;
        }
    }

    private synchronized void cancelStateFlushTask() {
        ScheduledFuture<?> pending = stateFlushTask;
        stateFlushTask = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    /**
     * One state write; never throws. A failed write keeps the store's dirty flags set, so the next
     * tick retries, and only the first failure of a streak is reported (T-22-31).
     *
     * @param store    the store to write
     * @param serverId the linked server's id
     */
    private void flushStateQuietly(CloudStateStore store, String serverId) {
        try {
            store.flushIfDirty(serverId);
            stateFlushFailureLogged = false;
        } catch (RuntimeException failure) {
            if (!stateFlushFailureLogged) {
                stateFlushFailureLogged = true;
                PanelConnectionLog.log(Level.WARNING,
                        "UltiCloud: the reliable delivery state could not be saved (" + failure.getMessage()
                        + "). It is retried every 30 seconds; buffered player join/leave events are lost if "
                        + "the server stops first.");
            }
        }
    }

    /**
     * One 30 s tick (D-15). Runs on the agent thread and therefore takes <b>only</b> the state
     * store's write lock -- never this session's monitor (S-37) -- so a flush under the session
     * monitor, in {@link #invalidate()}, cannot deadlock with it. It keeps re-arming itself for as
     * long as this session still holds the store it was created for; a discarded identity nulls
     * that field, which ends the chain.
     */
    private final class StateFlushTick implements Runnable {
        private final CloudStateStore store;
        private final String serverId;
        private final ScheduledExecutorService executor;

        StateFlushTick(CloudStateStore store, String serverId, ScheduledExecutorService executor) {
            this.store = store;
            this.serverId = serverId;
            this.executor = executor;
        }

        @Override
        public void run() {
            flushStateQuietly(store, serverId);
            if (cloudState == store) {
                scheduleStateFlush(store, serverId, executor);
            }
        }
    }

    CloudAuthManager.ReconnectResult.Kind reconnectNow() {
        AgentConnection captured;
        synchronized (this) {
            if (invalidated || this != current() || credential == null) {
                return CloudAuthManager.ReconnectResult.Kind.NOT_LINKED;
            }
            captured = agentConnection;
        }
        AgentConnection.State state = captured == null ? AgentConnection.State.IDLE : captured.state();
        switch (state) {
            case OPEN:
                return captured.isEstablished() ? CloudAuthManager.ReconnectResult.Kind.ALREADY_CONNECTED
                        : CloudAuthManager.ReconnectResult.Kind.ALREADY_CONNECTING;
            case CONNECTING:
                return CloudAuthManager.ReconnectResult.Kind.ALREADY_CONNECTING;
            case UPGRADE_REQUIRED:
                return CloudAuthManager.ReconnectResult.Kind.UPGRADE_REQUIRED;
            case CREDENTIAL_REJECTED:
            case SERVER_ID_MISMATCH:
                // A server-ID mismatch is left only by a restart or a new login (contract answer 21).
                return CloudAuthManager.ReconnectResult.Kind.NOT_LINKED;
            case IDLE:
            case STOPPED:
                return restartForManualReconnect(captured);
            default:
                synchronized (this) {
                    if (invalidated || this != current() || agentConnection != captured) {
                        return CloudAuthManager.ReconnectResult.Kind.NOT_LINKED;
                    }
                    agentExecutor.execute(captured::reconnectNow);
                }
                return CloudAuthManager.ReconnectResult.Kind.STARTED;
        }
    }

    private synchronized CloudAuthManager.ReconnectResult.Kind restartForManualReconnect(AgentConnection captured) {
        if (invalidated || this != current() || credential == null || agentConnection != captured) {
            return CloudAuthManager.ReconnectResult.Kind.NOT_LINKED;
        }
        stopConnection();
        startConnection();
        return CloudAuthManager.ReconnectResult.Kind.STARTED;
    }

    synchronized AgentConnection.State connectionState() {
        return agentConnection == null ? AgentConnection.State.STOPPED : agentConnection.state();
    }

    synchronized String connectionDescription() {
        switch (connectionState()) {
            case OPEN:
                // Upgraded is not connected: only a valid welcome establishes the connection (D-12).
                return agentConnection.isEstablished() ? "connected" : "connecting";
            case CONNECTING:
                return "connecting";
            case BACKOFF:
                return "reconnecting in " + retryDelayUnits(1000L) + " s (attempt " + agentConnection.attempt() + ")";
            case UPGRADE_REQUIRED:
                return "stopped — this UltiTools version is no longer accepted; upgrade UltiTools";
            case SUPERSEDED:
                return "stopped — another connection is using this server's credential";
            case SERVER_ID_MISMATCH:
                return "stopped — UltiCloud answered with a different server ID than this server is linked to; "
                        + "contact UltiCloud support, or run /ulticloud logout and then /ulticloud login";
            case PLAN_LIMITED:
                return "paused — plan limit reached; retrying every 15 minutes (next in "
                        + retryDelayUnits(60000L) + " min). Run /ulticloud reconnect after renewing.";
            default:
                return "not connected";
        }
    }

    private long retryDelayUnits(long unitMillis) {
        long remaining = Math.max(0L, agentConnection.nextRetryAtMillis() - agentClock.getAsLong());
        return (remaining + unitMillis - 1L) / unitMillis;
    }

    static void setAgentEndpointOverrideForTesting(URI endpoint) {
        agentEndpointOverride = endpoint;
    }

    static void setAgentTimingOverrideForTesting(ReconnectPolicy policy, AgentConnection.Timing timing) {
        agentPolicyOverride = policy;
        agentTimingOverride = timing;
    }

    static void setAgentRuntimeForTesting(java.util.function.Supplier<ScheduledExecutorService> executors,
            AgentConnection.SocketFactory sockets, LongSupplier clock) {
        agentExecutorOverride = executors;
        agentSocketsOverride = sockets;
        agentClockOverride = clock;
    }

    private final class SessionAgentListener implements AgentConnection.Listener {
        private AgentConnection connection;
        private String attemptCredentialId;
        private boolean rejectionReported;
        private boolean upgradeReported;
        private boolean supersededReported;
        private boolean mismatchReported;

        private boolean live() {
            return !invalidated && CloudSession.this == current() && agentConnection == connection;
        }

        /**
         * The bearer of the next attempt: the session's own {@link #credential}, the one owner of the current
         * credential (plan 22-21 fix), so an attempt after a rotation authenticates with the rotated secret and
         * {@link #attemptCredentialId} names the credential this attempt really used.
         */
        private String readBearer() {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return null;
                }
                ServerCredential held = credential;
                attemptCredentialId = held == null ? null : held.getCredentialId();
                return held == null ? null : held.bearerValue();
            }
        }

        /**
         * The upgrade was accepted: a fresh protocol layer sends {@code hello}. The connection is
         * established -- counters reset, heartbeat started, "connected" -- only when the protocol
         * accepts the welcome (plan 22-20, D-12), not here.
         */
        @Override
        public void onOpened() {
            AgentProtocol protocol;
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                protocol = newProtocol(connection);
                agentProtocol = protocol;
            }
            protocol.onOpen();
        }

        @Override
        public void onCredentialRejected(int code, String requestId) {
            synchronized (CloudSession.this) {
                if (!live() || rejectionReported) {
                    return;
                }
                rejectionReported = true;
                try {
                    ServerCredentialStore.deleteIfMatches(attemptCredentialId);
                    PanelConnectionLog.log(Level.WARNING,
                            "UltiCloud rejected this server's credential (it was revoked or is no longer valid). "
                            + "The saved credential was deleted. Run /ulticloud login to link this server again."
                            + requestSuffix(requestId));
                } catch (DataAccessException failure) {
                    PanelConnectionLog.log(Level.WARNING,
                            "UltiCloud rejected this server's credential, but the saved credential could not be deleted. "
                            + "Cloud features are stopped. Check credentials.json and run /ulticloud login again."
                            + requestSuffix(requestId));
                } finally {
                    // S-12: the buffered state belongs to the credential that was just revoked.
                    discardCloudState();
                    credential = null;
                }
            }
        }

        @Override
        public void onUpgradeRequired(String minFramework, String requestId) {
            synchronized (CloudSession.this) {
                if (!live() || upgradeReported) {
                    return;
                }
                upgradeReported = true;
                PanelConnectionLog.log(Level.WARNING,
                        "UltiCloud refused this UltiTools version's protocol (HTTP 426). "
                        + "Cloud features are stopped until UltiTools is upgraded; this server stays linked."
                        + requestSuffix(requestId)
                        + (minFramework == null ? "" : " UltiCloud requires UltiTools " + minFramework + " or later."));
            }
        }

        @Override
        public void onSuperseded() {
            synchronized (CloudSession.this) {
                if (!live() || supersededReported) {
                    return;
                }
                supersededReported = true;
                PanelConnectionLog.log(Level.WARNING,
                        "UltiCloud closed this server's connection three times because another connection uses the same credential. "
                        + "Reconnecting has stopped; make sure only one server uses this credential, then restart the server "
                        + "or run /ulticloud login.");
            }
        }

        @Override
        public void onPlanLimited(UpgradeRejection notice) {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                String reason = "pro_expired_over_limit".equals(notice.planReason())
                        ? "the Pro plan expired and the account has more servers than its plan allows"
                        : notice.planReason() == null ? "plan_limit" : notice.planReason();
                PanelConnectionLog.log(Level.WARNING,
                        "UltiCloud has paused this server's connection because the account's plan limit was exceeded ("
                        + reason + "). This server stays linked and UltiTools retries every 15 minutes; renew the plan "
                        + "or choose this server to keep, then wait for the next retry or run /ulticloud reconnect."
                        + (notice.graceEndedAt() == null ? "" : " Grace period ended at " + notice.graceEndedAt() + ".")
                        + (notice.manageUrl() == null ? "" : " Manage the plan: " + notice.manageUrl())
                        + requestSuffix(notice.requestId()));
            }
        }

        @Override
        public void onServerIdMismatch() {
            synchronized (CloudSession.this) {
                if (!live() || mismatchReported) {
                    return;
                }
                mismatchReported = true;
                UltiTools plugin = UltiTools.getInstance();
                String text = plugin == null ? null : plugin.i18n(SERVER_ID_MISMATCH_KEY);
                PanelConnectionLog.log(Level.SEVERE, text == null ? SERVER_ID_MISMATCH_KEY : text);
            }
        }

        @Override
        public void onStateChanged(AgentConnection.State state) {
            AgentProtocol ended = null;
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                if (state != AgentConnection.State.OPEN && agentProtocol != null) {
                    ended = agentProtocol;
                    agentProtocol = null;
                }
            }
            if (ended != null) {
                ended.onClose();
            }
        }

        /** Hands the frame to the current protocol layer, outside the session monitor. */
        @Override
        public void onText(String text) {
            AgentProtocol protocol;
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                protocol = agentProtocol;
            }
            if (protocol != null) {
                protocol.onText(text);
            }
        }
    }

    private static String requestSuffix(String requestId) {
        return requestId == null ? "" : " (UltiCloud request ID " + requestId + ")";
    }

    // ---- Device authorization (protocol v2, plan 22-01) ----

    /** @return {@code true} if this session committed a server credential */
    boolean hasCredential() {
        return credential != null;
    }

    /** @return the server credential this session committed, or {@code null} */
    ServerCredential getCredential() {
        return credential;
    }

    /**
     * Persists {@code newCredential} through {@link ServerCredentialStore}, but only if this session
     * has not been invalidated and is still current, so a grant that
     * arrives after {@code /ulticloud logout} or after a newer login replaced this session is
     * discarded and never written.
     *
     * @param newCredential the credential the device authorization granted
     * @return {@code true} if committed; {@code false} if this session was already invalidated, in
     *         which case nothing was written
     * @throws DataAccessException if the write fails
     */
    synchronized boolean commitCredential(ServerCredential newCredential) {
        if (invalidated || this != current()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Discarding a credential result that arrived after this session was invalidated");
            return false;
        }
        ServerCredentialStore.save(newCredential);
        this.credential = newCredential;
        adoptCloudState(newCredential);
        return true;
    }

    /**
     * Rotates this session's credential (plan 22-21, D-10): the {@code credential.rotate} persister of every
     * connection this session owns. Under this session's monitor, {@link ServerCredentialStore#replace} first makes
     * the new secret durable (one atomic, owner-only write) and only then is {@link #credential} replaced, so the
     * in-memory credential and the file change in one step and the caller acknowledges only a durable rotation. The
     * next connection attempt ({@code readBearer}) and {@code /ulticloud logout} both read {@link #credential}, so
     * neither can use the retired secret. Nothing is written, and the gateway gets no acknowledgement, when this
     * session was invalidated or replaced, holds no credential, or the file holds another server identity.
     *
     * @param secret       the new {@code ucs_} secret
     * @param credentialId the new credential's public id
     * @return {@code true} when the new credential is durable and held
     * @throws DataAccessException if the write fails; the old credential then stays in place in memory and on disk
     */
    synchronized boolean rotateCredential(String secret, String credentialId) {
        ServerCredential held = credential;
        if (invalidated || this != current() || held == null) {
            return false;
        }
        if (!ServerCredentialStore.replace(held.getServerId(), secret, credentialId)) {
            return false;
        }
        credential = held.rotated(secret, credentialId);
        return true;
    }

    /**
     * Requests a device code on this session's poll executor and, once UltiCloud answers, reports it
     * through {@code listener} and starts polling. Schedules nothing on an invalidated session.
     *
     * @param listener receives the code or the failure; called on the poll executor's thread
     */
    synchronized void beginDeviceLogin(CloudAuthManager.DeviceLoginListener listener) {
        scheduleDeviceStep(() -> requestDeviceCode(listener), 0L);
    }

    // Every failure of a step must reach the operator: an exception thrown inside a
    // ScheduledExecutorService task is otherwise kept silently in its Future.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void requestDeviceCode(CloudAuthManager.DeviceLoginListener listener) {
        if (!isCurrent()) {
            return;
        }
        DeviceAuthorizationClient.AuthorizeResult result;
        try {
            result = deviceClient().authorize(CloudAuthManager.runningFrameworkVersion(),
                CloudAuthManager.suggestedServerName(), CloudAuthManager.storedLegacyServerUuid());
        } catch (RuntimeException e) {
            result = DeviceAuthorizationClient.AuthorizeResult.failed(
                "unexpected " + e.getClass().getSimpleName(), -1);
        }
        if (!result.isGranted()) {
            stopPolling();
            if (isCurrent()) {
                listener.onRequestFailed(result.failureReason());
            }
            return;
        }
        DeviceAuthorizationClient.Grant grant = result.grant();
        if (!isCurrent()) {
            return;
        }
        listener.onCode(grant.verificationUri(), grant.userCode(), grant.verificationUriComplete(),
            grant.expiresInSeconds());
        startDevicePolling(grant, listener);
    }

    /**
     * Polls {@code POST /v1/device/token} for {@code grant} on this session's poll executor until it
     * reaches a terminal outcome. Schedules nothing on an invalidated session: a late call after
     * {@code /ulticloud logout} -- the device-code request returned after the logout -- creates no
     * executor (the round-1 review property the 6.3.0 magic-link {@code startPolling} carried).
     *
     * @param grant    the device code to poll
     * @param listener receives the outcome; called on the poll executor's thread
     */
    synchronized void startDevicePolling(DeviceAuthorizationClient.Grant grant,
            CloudAuthManager.DeviceLoginListener listener) {
        if (invalidated) {
            return;
        }
        // The deadline is fixed once, when the device code arrived: now + expiresIn.
        DevicePoll poll = new DevicePoll(grant, listener, now() + grant.expiresInSeconds() * 1000L,
            grant.intervalSeconds() * 1000L);
        scheduleDeviceStep(() -> pollDeviceOnce(poll), poll.intervalMillis);
    }

    /**
     * Schedules one device-login step on this session's poll executor, creating it on first use.
     * The step becomes {@link #pollTask}, so {@link #invalidate()} both cancels it and records that a
     * pending authentication was cancelled.
     *
     * @return {@code false} if this session is invalidated and nothing was scheduled
     */
    private synchronized boolean scheduleDeviceStep(Runnable step, long delayMillis) {
        if (invalidated) {
            return false;
        }
        DeviceStepScheduler override = deviceSchedulerOverride;
        if (override != null) {
            pollTask = override.schedule(step, delayMillis);
            return true;
        }
        if (pollExecutor == null) {
            pollExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "UltiCloud-device-login");
                thread.setDaemon(true);
                return thread;
            });
        }
        pollTask = pollExecutor.schedule(step, delayMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * One poll step. RFC 8628 section 3.5 semantics: {@code authorization_pending} polls again after
     * the interval; {@code slow_down} first adds {@value #DEVICE_SLOW_DOWN_INCREMENT_MS} ms to it,
     * cumulatively; a transient failure (network, 5xx, 429, unreadable answer) polls again after the
     * interval, or after {@code max(interval, Retry-After)} when UltiCloud answered 429; denied,
     * expired and rejected stop. Reaching the deadline while still pending or failing transiently is
     * the same as expired -- checked before each poll, so no request is sent after it.
     */
    // A step runs on a ScheduledExecutorService, which would keep an exception silently in the
    // step's Future; every failure must end the login visibly instead.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void pollDeviceOnce(DevicePoll poll) {
        if (!isCurrent()) {
            return;
        }
        if (now() >= poll.deadline) {
            finishDeviceLogin(poll, DeviceAuthorizationClient.PollResult.Kind.EXPIRED, null, null);
            return;
        }
        DeviceAuthorizationClient.PollResult result;
        try {
            result = deviceClient().pollToken(poll.grant.deviceCode());
        } catch (RuntimeException e) {
            result = DeviceAuthorizationClient.PollResult.transientFailure(-1);
        }
        switch (result.kind()) {
            case GRANTED:
                completeDeviceLogin(poll, result.credential());
                return;
            case SLOW_DOWN:
                poll.intervalMillis += DEVICE_SLOW_DOWN_INCREMENT_MS;
                scheduleDeviceStep(() -> pollDeviceOnce(poll), poll.intervalMillis);
                return;
            case TRANSIENT:
                long retryAfterMillis = result.retryAfterSeconds() * 1000L;
                scheduleDeviceStep(() -> pollDeviceOnce(poll), Math.max(poll.intervalMillis, retryAfterMillis));
                return;
            case EXPIRED:
            case DENIED:
            case REJECTED:
                finishDeviceLogin(poll, result.kind(), result.code(), result.message());
                return;
            case PENDING:
            default:
                scheduleDeviceStep(() -> pollDeviceOnce(poll), poll.intervalMillis);
        }
    }

    /** Stops polling and reports a terminal outcome other than a grant, if this session is current. */
    private void finishDeviceLogin(DevicePoll poll, DeviceAuthorizationClient.PollResult.Kind kind,
            String code, String message) {
        stopPolling();
        if (!isCurrent()) {
            return;
        }
        Logger logger = UltiTools.getInstance().getLogger();
        if (kind == DeviceAuthorizationClient.PollResult.Kind.DENIED) {
            logger.log(Level.WARNING,
                "UltiCloud: linking was denied on the confirmation page. Run /ulticloud login to try again.");
            poll.listener.onDenied();
        } else if (kind == DeviceAuthorizationClient.PollResult.Kind.REJECTED) {
            logger.log(Level.WARNING,
                "UltiCloud: the device code request was rejected (" + code + "): " + message);
            poll.listener.onRejected(code, message);
        } else {
            logger.log(Level.WARNING,
                "UltiCloud: the device code expired before it was confirmed. Run /ulticloud login to get a new code.");
            poll.listener.onExpired();
        }
    }

    /** @return the device-login REST client: the test override, else this session's own */
    private DeviceAuthorizationClient deviceClient() {
        DeviceAuthorizationClient override = deviceClientOverride;
        return override != null ? override : deviceClient;
    }

    /** @return the device-login clock in epoch milliseconds: the test override, else the wall clock */
    private static long now() {
        LongSupplier override = deviceClockOverride;
        return override != null ? override.getAsLong() : System.currentTimeMillis();
    }

    private void completeDeviceLogin(DevicePoll poll, ServerCredential granted) {
        boolean committed;
        try {
            committed = commitCredential(granted);
        } catch (DataAccessException e) {
            stopPolling();
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "UltiCloud: the server was linked, but its credential could not be saved ("
                    + e.getMessage() + "). Revoke it in the panel and run /ulticloud login again.");
            return;
        }
        stopPolling();
        if (!committed) {
            return;
        }
        ApiRateLimiter.reset("login");
        UltiTools.getInstance().getLogger().log(Level.INFO,
            "UltiCloud: this server is now linked as " + granted.getServerName() + " (server ID "
                + granted.getServerId() + ").");
        startConnection();
        poll.listener.onLinked(granted.getServerName(), granted.getServerId());
    }

    /**
     * Test-only: replaces the device-login REST client, step scheduler and clock for every session,
     * so polling arithmetic is tested without sleeping. Pass {@code null}s to restore the defaults
     * (this session's own client, its poll executor, {@link System#currentTimeMillis()});
     * {@link #resetForTesting()} does so too.
     *
     * @param client    the client, or {@code null}
     * @param scheduler the scheduler, or {@code null}
     * @param clock     the clock in epoch milliseconds, or {@code null}
     */
    static void setDeviceSeamsForTesting(DeviceAuthorizationClient client, DeviceStepScheduler scheduler,
            LongSupplier clock) {
        deviceClientOverride = client;
        deviceSchedulerOverride = scheduler;
        deviceClockOverride = clock;
    }

    /** Schedules one device-login step; the test seam of {@link #setDeviceSeamsForTesting}. */
    interface DeviceStepScheduler {

        /**
         * @param step        the step to run
         * @param delayMillis the delay before it runs
         * @return the step's future, which {@link #stopPolling()} cancels
         */
        ScheduledFuture<?> schedule(Runnable step, long delayMillis);
    }

    /** The mutable state of one device-code poll; touched only by its poll steps, one at a time. */
    private static final class DevicePoll {

        private final DeviceAuthorizationClient.Grant grant;
        private final CloudAuthManager.DeviceLoginListener listener;
        /** Epoch milliseconds after which the code counts as expired. */
        private final long deadline;
        /** The current interval; grows by {@link #DEVICE_SLOW_DOWN_INCREMENT_MS} per slow_down. */
        private long intervalMillis;

        DevicePoll(DeviceAuthorizationClient.Grant grant, CloudAuthManager.DeviceLoginListener listener,
                long deadline, long intervalMillis) {
            this.grant = grant;
            this.listener = listener;
            this.deadline = deadline;
            this.intervalMillis = intervalMillis;
        }
    }
}
