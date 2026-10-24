package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;

import com.ultikits.ultitools.websocket.PanelConnectionLog;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.manager.EarlyLogCapture;
import com.ultikits.ultitools.websocket.ExponentialBackoffStrategy;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * The single owner of one UltiCloud login's entire lifetime.
 * <p>
 * A session owns the token, the poll and refresh schedulers it created, the panel WebSocket client,
 * the reconnect backoff state, and the panel-manager wiring (D-16). {@code login} constructs a new
 * session via {@link #startNew()}; {@code logout} replaces the current one the same way. Everything
 * the replaced session started -- an in-flight poll, an in-flight refresh, a reconnect attempt --
 * lapses on its own once {@link #invalidate()} has run, because {@link #commit(TokenEntity)} and
 * every gate this class exposes check this <b>specific instance's</b> {@link #invalidated} flag,
 * never a shared counter another session's activity could also advance.
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
 * {@link CloudAuthManager#logout()} and {@link CloudAuthManager#reconnect()} (both are
 * {@code synchronized} methods). They DO nest it with the locks below, in this list's order:
 * logout's own {@code synchronized (CloudSession.class)} block calls {@link #invalidate()} on
 * the session monitor inside it, and reconnect takes the session monitor inside
 * {@code CloudSession#reconnectNow()}. No path in this package acquires them the other way
 * round.</li>
 * <li>{@code CloudSession.class} -- held by {@link #startNew()} and by
 * {@link CloudAuthManager#login} (16-10 gap-closure addendum, issue #466) for its own
 * re-check-through-replacement region.</li>
 * <li>a specific session's own monitor ({@code synchronized (this)} / {@code synchronized(session)})
 * -- held by {@link #commit(TokenEntity)}, {@link #invalidate()}, the two scheduler start/stop
 * pairs, (protocol v2, plan 22-01) {@link #commitCredential(ServerCredential)},
 * {@link #beginDeviceLogin}, {@link #startDevicePolling} and the device-step scheduling they share,
 * and by every caller in {@code PluginInitiationUtils} that needs to
 * read-then-act on a session's currency ({@code reinitWebSocket}'s second confirmation,
 * {@code activateCloudIfCurrent}, {@code doDisableCloud}'s two global-manager steps).</li>
 * <li>{@link #LOG_WIRING_LOCK} (16-10 gap-closure addendum, issue #465) -- the innermost lock,
 * held only inside {@link #shutdownLogStreamManager()} and inside {@link #wireManagers()}'s own
 * {@code LOGS} branch, both of which are themselves only ever reached from within a
 * {@code synchronized (this)} region ({@link #invalidate()} and {@link #initializeManagers()}
 * respectively) -- so this lock is never acquired without the session monitor already held, and
 * never used to acquire anything further.</li>
 * <li>{@link CloudStateStore}'s JVM-wide write lock (plan 22-07) -- taken by every load, flush
 * and detach of {@code cloud-outbox.json}, always below the session monitor: {@link
 * #invalidate()}'s and the shutdown flush's write cannot interleave with the 30 s task's, which
 * holds only this lock and never the session monitor. Its inner locks are the {@link CloudOutbox}
 * and {@link CommandDedupeSet} monitors, which are leaves.</li>
 * <li>The {@link AgentConnection} monitor and {@code CredentialStore.LOCK}, taken by
 * {@link ServerCredentialStore}, are siblings of {@code LOG_WIRING_LOCK}: never nested in one
 * another, always below the session monitor. AgentConnection never calls out while holding its
 * monitor. Its scheduler callbacks take the session monitor first and reject invalidated or
 * replaced sessions and replaced connections before acting.</li>
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
 * {@link #commit(TokenEntity)} uses. The granted {@code ucs_} credential is persisted only through
 * {@link #commitCredential(ServerCredential)}.
 *
 * @since 6.3.0
 */
final class CloudSession {

    // All field declarations precede all methods (PMD FieldDeclarationsShouldBeAtStartOfClass) --
    // moved here in plan 16-10 (Gate 1); no field's own meaning changed, only its position.

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How often to check if the access token needs refreshing (1 hour) */
    private static final long TOKEN_REFRESH_CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    /** Refresh the token when it has less than this many seconds remaining (2 hours) */
    private static final long TOKEN_REFRESH_THRESHOLD_SECONDS = 2 * 60 * 60L;
    /** Basic auth header for OAuth2 client credentials (client:112233) */
    private static final String OAUTH2_BASIC_AUTH = "Basic Y2xpZW50OjExMjIzMw==";
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

    private final TokenStore tokenStore = new TokenStore();

    private volatile TokenEntity token;
    private volatile boolean invalidated;

    /**
     * The nearest ancestor session's last-known token, or {@code null} if either there is no
     * ancestor (this is the first session ever created) or that ancestor never itself held a
     * token. Set only by {@link #startNew()}, consulted only by {@link #clearPersisted()} as a
     * fallback when THIS session's own {@link #token} is {@code null} -- deliberately never
     * consulted by {@link #hasValidToken()} or any other connection-status gate (D-18's own
     * invariant is unchanged: a session with no token of its own is not "connected", regardless of
     * what an ancestor once held).
     * <p>
     * <b>16-10 gap-closure addendum (round-13 review, PR #464), P1:</b> without this, a session
     * exhausted by {@code reinitWebSocket}'s budget (which deliberately leaves its own
     * {@link #token} unset-but-still-in-memory -- clearing the credential is logout's job, not
     * exhaustion's, per {@link #hasValidToken()}'s own round-10 note) that is then replaced by
     * {@code /ulticloud login} via {@link #startNew()} left its still-valid, still-persisted
     * credential unreachable by any LATER {@code /ulticloud logout} call: the new (blank) session
     * has no token of its own, so {@link TokenStore#clearIfMatches(TokenEntity)}'s compare-and-delete
     * had nothing to compare against, and {@code logout()} reported "nothing to clear" while the
     * old credential survived on disk -- a subsequent restart would reload and reconnect with it
     * despite the operator's explicit logout. Chained forward across however many blank sessions
     * intervene (each inherits the nearest non-null ancestor value, not just its immediate
     * predecessor's own possibly-{@code null} field), so the credential stays reachable no matter
     * how many replacements happen before a logout finally runs.
     */
    private volatile TokenEntity predecessorToken;

    /**
     * {@code true} if {@link #invalidate()} found -- and cancelled -- a pending authentication still
     * in flight on this session (as of 6.3.1 the device-code request or poll; a magic-link poll in
     * 6.3.0), and that fact has not yet been reported and reset by
     * {@link #clearPersisted()}.
     * <p>
     * <b>Real-machine UAT fix, plan 16-08 ({@code ultitools.ulticloud.logout.neg-mid-poll}).</b>
     * Cancelling a pending authentication attempt IS a real logout, even though it never reached
     * {@link #commit(TokenEntity)} and therefore never set {@link #token} or
     * {@link #predecessorToken} -- both of which {@link #hasAnythingToClear()} used to consult
     * exclusively. Without this field, {@code /ulticloud logout} run while a login's magic-link poll
     * is still running (no token committed yet, no predecessor either) reported "Not currently
     * logged in to UltiCloud." even though it genuinely tore the poll down -- observed on a real
     * Paper 1.21.11 server. The safety half of this row (a stale approval landing after this logout
     * must not reactivate the server) was never in question: {@link #commit(TokenEntity)}'s own
     * {@link #invalidated} check already rejects it regardless of this field. Only the reported
     * OUTCOME of the logout that cancelled the poll was wrong.
     * <p>
     * Captured in {@link #invalidate()}, immediately before {@link #stopPolling()} clears
     * {@link #pollTask} -- reading {@link #pollTask} any later would always see {@code null} and this
     * field could never become {@code true}. Reset back to {@code false} in
     * {@link #clearPersisted()}'s own {@code finally} block, alongside {@link #token} and
     * {@link #predecessorToken}, for the same reason those two are reset there: a second,
     * independent {@code logout()} call on this same (already-invalidated) session instance -- e.g. a
     * second {@code /ulticloud logout} before any new login replaces it -- must not keep reporting
     * success for a poll this session already reported cancelling once.
     */
    private volatile boolean pendingAuthenticationCancelled;

    /**
     * The protocol v2 server credential this session committed, or {@code null}. Written only by
     * {@link #commitCredential(ServerCredential)}.
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

    /** The pending 30 s state-flush task of this session's connection, or {@code null}. */
    private volatile ScheduledFuture<?> stateFlushTask;

    /** Whether the current state-write failure streak already logged its one WARNING (T-22-31). */
    private volatile boolean stateFlushFailureLogged;

    private AgentConnection agentConnection;
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
    private ScheduledExecutorService refreshExecutor;
    private ScheduledFuture<?> refreshTask;

    /**
     * This session's own WebSocket client, or {@code null} if none is connected. Task 2 of plan
     * 16-08 moved this off {@code PluginInitiationUtils}'s static {@code panelWS} field -- the
     * client dies with the session that built it ({@link #invalidate()} disconnects and clears it),
     * so a reconnect-exhaustion callback or a late handshake checked against THIS session can never
     * disagree with what client is actually installed here.
     */
    private volatile UltiPanelWebSocketClient webSocketClient;

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

    /**
     * The innermost lock in this class's documented lock order (see the class javadoc) -- guards
     * {@link #logStreamOwner} only. A dedicated lock object, not {@code this}/a session monitor and
     * not {@code CloudSession.class}: the ownership check it protects must be readable from
     * {@link #shutdownLogStreamManager()} (called from {@link #invalidate()}, under the tearing-down
     * session's own monitor) while comparing against whichever session's monitor
     * {@link #wireManagers()} (called from {@link #initializeManagers()}, under the wiring
     * session's own, possibly DIFFERENT, monitor) last held when it wrote it -- two different
     * sessions' monitors can never be compared against each other directly, so the ownership record
     * itself needs its own lock, held only ever as the innermost one (16-10 gap-closure addendum,
     * issue #465).
     */
    private static final Object LOG_WIRING_LOCK = new Object();

    /**
     * Which session's log-stream wiring is currently installed on the JVM-wide
     * {@link com.ultikits.ultitools.manager.LogStreamManager} singleton, or {@code null} if no
     * session has ever wired it (or the last session to do so has already cleanly detached it).
     * Guarded exclusively by {@link #LOG_WIRING_LOCK}.
     * <p>
     * <b>Deliberately tolerant of {@code null}, not "unowned means owned by nobody, so skip."</b> A
     * great many call paths in this package's own tests (and at least one production path this
     * class does not control -- a direct {@code LogStreamManager.getInstance().initialize(...)} call
     * bypassing {@link #wireManagers()} entirely) install log-stream wiring WITHOUT ever routing
     * through this class, so this field can legitimately be {@code null} even while a handler is, in
     * fact, attached. {@link #shutdownLogStreamManager()} therefore only ever refuses to shut down
     * when this field names ANOTHER, specific, still-current session -- never merely because it is
     * unset -- so every pre-existing "logout detaches the handler" guarantee this class already made
     * before issue #465 continues to hold exactly as before for every caller that never populates
     * this field.
     */
    private static CloudSession logStreamOwner;

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
        // 16-10 gap-closure addendum (round-13 review), P1: carry the nearest non-null ancestor
        // token forward -- see #predecessorToken's own javadoc for why. Reads `previous.token`
        // before `previous.invalidate()` below touches anything (invalidate() never clears
        // `token` itself, but reading it first keeps this independent of that fact).
        next.predecessorToken = previous.token != null ? previous.token : previous.predecessorToken;
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
        // 16-10 gap-closure addendum: logStreamOwner is a second JVM-wide static this class owns
        // (issue #465) -- left unset, a leftover reference from a previous test class would make
        // shutdownLogStreamManager() wrongly believe a DIFFERENT session still owns the wiring and
        // skip a shutdown this fresh test genuinely expects, exactly the "no forkCount" leak issue
        // #250 already documents for #current itself.
        synchronized (LOG_WIRING_LOCK) {
            logStreamOwner = null;
        }
    }

    // ---- Commit / invalidate: the contract D-18 requires ----

    /**
     * Persists {@code token} through this session's own {@link TokenStore}, but only if this
     * session has not been invalidated. This is the <b>only</b> path that writes a token to disk
     * (D-18) -- there is no static, session-independent save.
     *
     * @param newToken the token to persist
     * @return {@code true} if committed; {@code false} if this session was already invalidated, in
     *         which case nothing was written
     * @throws IOException if the underlying write fails
     */
    synchronized boolean commit(TokenEntity newToken) throws IOException {
        if (invalidated) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Discarding a credential result that arrived after this session was invalidated");
            return false;
        }
        tokenStore.save(newToken);
        this.token = newToken;
        return true;
    }

    /**
     * Marks this session invalid and tears down everything it owns: the log stream manager (as of
     * plan 16-10, WR-01), the poll and refresh schedulers, and (as of plan 16-08 Task 2) the
     * WebSocket client. Safe to call on a session that never started any of those (a freshly
     * constructed session with nothing scheduled and no client yet) and safe to call more than once
     * -- both are exercised directly by this class's own tests, independently of the static
     * {@link #current()} holder.
     * <p>
     * <b>WR-01 (16-REVIEW-cloud.md):</b> the log-stream-manager shutdown used to run in
     * {@code PluginInitiationUtils.doDisableCloud()}, <i>before</i> this method and with no lock at
     * all. A late {@code onWebSocketOpened} landing in that unlocked gap would observe this session
     * as still current -- because it genuinely still was, at that exact instant -- and re-attach the
     * log handler that step had just detached; {@link #invalidate()} would then run and close the
     * WebSocket client, but never re-run the log-stream shutdown a second time. Moving the shutdown
     * inside this method closes that gap: {@link #initializeManagers()} (which re-wires the log
     * stream manager) and this method both synchronize on {@code this}, so one of them always runs
     * to completion before the other can even enter -- there is no window in which "wiring" can see
     * a stale "still current" read while "teardown" is genuinely underway. The original ordering
     * concern this step's comment used to document -- log flush before the WebSocket client closes,
     * so {@code sendBatch()} does not find the socket already down with a non-empty queue -- is
     * preserved: this step still runs before {@link #closeWebSocketClient()} below, just now under
     * the same lock as everything else.
     * <p>
     * <b>Real-machine UAT fix, plan 16-08:</b> {@link #pendingAuthenticationCancelled} is captured
     * here -- reading {@link #pollTask} BEFORE {@link #stopPolling()} below clears it -- so
     * {@link #hasAnythingToClear()} can report that this call genuinely cancelled a real, in-flight
     * authentication attempt even though nothing was ever committed to {@link #token}. See that
     * field's own javadoc for the full account.
     */
    synchronized void invalidate() {
        invalidated = true;
        shutdownLogStreamManager();
        if (pollTask != null) {
            pendingAuthenticationCancelled = true;
        }
        stopPolling();
        stopTokenRefreshScheduler();
        closeWebSocketClient();
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
     * manager (unmocked in the majority of this package's tests) exactly like
     * {@link #closeWebSocketClient()} already tolerates a {@code null} client -- neither condition
     * is an error, both are simply nothing to tear down.
     * <p>
     * <b>16-10 gap-closure addendum, issue #465:</b> before this fix, this step ran unconditionally
     * whenever ANY session invalidated -- including a stale, already-superseded session's own
     * redundant second invalidation (e.g. a delayed reconnect-exhaustion callback for an old session
     * arriving after a replacement session had already completed its own handshake and wired its own
     * log handler). Because {@link com.ultikits.ultitools.manager.LogStreamManager} is a JVM-wide
     * singleton, that unconditional shutdown would detach the REPLACEMENT session's own,
     * currently-valid wiring -- with no later handshake ever coming to re-attach it, since the
     * replacement session's own {@code onOpen} had already run. Gating on {@link #logStreamOwner}
     * (held under {@link #LOG_WIRING_LOCK}, the innermost lock in this class's documented order)
     * closes that: this session's own teardown only ever shuts the manager down if it is the one
     * that most recently wired it (or nothing has ever recorded ownership at all -- see
     * {@link #logStreamOwner}'s own javadoc for why an unset owner must still shut down, not skip).
     * A session that finds a DIFFERENT, specific owner recorded leaves the manager alone and does
     * NOT clear the record -- the owning session's own eventual teardown is what does that.
     */
    private void shutdownLogStreamManager() {
        UltiTools plugin = UltiTools.getInstance();
        if (plugin == null) {
            return;
        }
        try {
            synchronized (LOG_WIRING_LOCK) {
                if (logStreamOwner != null && logStreamOwner != this) {
                    // A different, still-current session's own wiring is installed -- this
                    // (older, already-superseded) session's teardown must not detach it (#465).
                    return;
                }
                com.ultikits.ultitools.manager.LogStreamManager logStreamManager =
                    plugin.getLogStreamManager();
                if (logStreamManager != null) {
                    logStreamManager.shutdown();
                }
                logStreamOwner = null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE,
                "Error shutting down log stream manager during session invalidation: " + e.getMessage());
        }
    }

    /**
     * Disconnects and clears this session's WebSocket client, if any. Extracted out of
     * {@link #invalidate()} so the client-close step is a named, independently callable action --
     * exactly the shape issue #298's fourth acceptance criterion (teardown order is not a
     * correctness precondition) needs to test each of the four named steps in isolation, in
     * whatever order a test chooses. Production code only ever reaches this through
     * {@link #invalidate()}.
     */
    synchronized void closeWebSocketClient() {
        if (webSocketClient != null) {
            try {
                webSocketClient.disconnect();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Error disconnecting WebSocket during session invalidation: " + e.getMessage());
            }
            webSocketClient = null;
        }
    }

    /**
     * Test-only: marks this session invalidated without touching the poller, the refresher, or
     * the WebSocket client -- the flag-setting half of {@link #invalidate()}, isolated so a test
     * can establish "logout has been decided" as a precondition and then permute the four
     * mechanical teardown actions ({@link #stopPolling()}, {@link #stopTokenRefreshScheduler()},
     * {@link #closeWebSocketClient()}, {@link #clearPersisted()}) in any order afterward, proving
     * their relative order does not change the outcome (D-16/D-18, issue #298's fourth acceptance
     * criterion). Production code never calls this in isolation; {@link #invalidate()} always sets
     * the same flag together with the poller/refresher/client cleanup, in one atomic call.
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

    /** @return the token this session currently holds, or {@code null} */
    TokenEntity getToken() {
        return token;
    }

    /**
     * @return {@code true} if this session's teardown genuinely cancelled or is responsible for
     *         clearing something -- this session's own {@link #token}, (round-13 review, P1) the
     *         nearest ancestor's {@link #predecessorToken}, or (real-machine UAT fix, plan 16-08) a
     *         magic-link poll that was still in flight and just got cancelled by
     *         {@link #invalidate()} (see {@link #pendingAuthenticationCancelled}'s own javadoc).
     *         {@code false} only if all three are absent, meaning this teardown genuinely found
     *         nothing pending -- neither a credential on disk nor an authentication attempt in
     *         progress. {@code CloudAuthManager#logout()} gates its "nothing to clear" early return
     *         on this, not on {@link #getToken()} alone, so a session that never itself held a token
     *         but replaced one that did (exhaustion followed by {@code /ulticloud login}), or one
     *         that cancelled a poll before it ever committed anything, still reports a genuine
     *         logout occurred.
     */
    boolean hasAnythingToClear() {
        return credential != null || token != null || predecessorToken != null || pendingAuthenticationCancelled;
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
            token = null;
            predecessorToken = null;
        }
    }

    /** @return this session's WebSocket client, or {@code null} if none is connected */
    UltiPanelWebSocketClient getWebSocketClient() {
        return webSocketClient;
    }

    /**
     * Installs {@code client} as this session's WebSocket client, replacing (without closing) any
     * previous one. Callers that need the previous client closed first should read
     * {@link #getWebSocketClient()}, close it themselves, and only then call this.
     *
     * @param client the client to install, or {@code null} to clear the reference without closing it
     */
    void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    /**
     * @return this session's own reconnect backoff strategy -- never shared with any other session
     */
    ExponentialBackoffStrategy getBackoff() {
        return backoff;
    }

    /**
     * Removes the persisted credential from disk, but ONLY the copy this specific session itself
     * is responsible for, and clears this session's in-memory token unconditionally (a purely
     * local field on an instance nothing else can observe).
     * <p>
     * Called by {@code CloudAuthManager.logout()} (plan 16-10) on the session
     * {@link PluginInitiationUtils#disableCloud(CloudSession)} actually tore down.
     * <p>
     * <b>Round-1 external review finding (16-10, PR #464):</b> {@code credentials.json} is one
     * shared document across every session that has ever existed. A blind, unconditional disk
     * clear here -- the original 16-09 shape -- could remove a credential a NEWER session
     * committed while this (older, being-logged-out) session's teardown was still in flight: the
     * new session passes its own currency check, its commit succeeds and writes to the shared
     * document, and this method's own unconditional clear would then wipe that fresh write, even
     * though the operator's re-login had already genuinely succeeded. Delegating to
     * {@link TokenStore#clearIfMatches(TokenEntity)} with this session's own {@link #token} turns
     * the disk clear into a compare-and-delete: it only ever removes the credential THIS session
     * itself last knows to be persisted, never a value some other session wrote afterward.
     * <p>
     * <b>Round-5 external review finding (16-10, PR #464):</b> if the disk-side clear fails --
     * {@code credentials.json} is unwritable or the underlying file is corrupt -- the old
     * shape returned before ever reaching the in-memory clear, so {@link #token} stayed set to
     * the value this session (already invalidated by the caller's {@code logout()}) still
     * believed was valid. At the time of this finding, {@link #hasValidToken()} did not consult
     * {@link #invalidated} at all (see round-10's own fix on that method for the separate,
     * independent gap this exposed), so every subsequent {@code /ulticloud login} kept reporting
     * "Already logged in" against a session nothing could ever make current again, until the
     * server restarted. The disk write and the in-memory clear are two independent effects with
     * no ordering relationship the caller can rely on to fix this the other way around, so the
     * in-memory half is moved into a {@code finally} block: it now always runs, whether the disk
     * write succeeds, fails with a propagated exception, or is never reached at all. This remains
     * correct and necessary on its own even after round-10's fix -- this method is package-private
     * and callable directly (as this class's own tests do) on a session that has not itself been
     * invalidated yet, a case round-10's {@link #invalidated} check does not cover.
     * <p>
     * <b>Round-13 external review finding (16-10 gap-closure addendum, PR #464), P1:</b> if THIS
     * session never itself held a token (the common shape after {@code /ulticloud login} replaces
     * an exhausted-but-never-logged-out predecessor -- see {@link #predecessorToken}'s own
     * javadoc), comparing against {@code token} alone compares against {@code null}, which
     * {@link TokenStore#clearIfMatches(TokenEntity)} always treats as "no match" -- so the
     * predecessor's still-persisted, still-valid credential was never reachable by this session's
     * own logout, surviving on disk despite the operator's explicit intent. Falling back to
     * {@link #predecessorToken} widens the compare-and-delete's target WITHOUT reopening the
     * round-1 race that method's own javadoc documents: a concurrent, genuinely newer commit still
     * writes a different access token value, so the comparison still correctly fails against it
     * either way.
     * <p>
     * <b>Real-machine UAT fix, plan 16-08:</b> also resets {@link #pendingAuthenticationCancelled}
     * back to {@code false} in the same {@code finally} block, for the same reason {@link #token} and
     * {@link #predecessorToken} are reset here -- a second, independent {@code logout()} call on this
     * same (already-invalidated) session must not keep reporting success for a poll this session
     * already reported cancelling once.
     *
     * @throws IOException if the underlying write fails
     */
    void clearPersisted() throws IOException {
        try {
            tokenStore.clearIfMatches(token != null ? token : predecessorToken);
        } finally {
            this.token = null;
            this.predecessorToken = null;
            this.pendingAuthenticationCancelled = false;
        }
    }

    /**
     * @return {@code true} if this session has not been invalidated AND holds a non-expired token
     *         with an access token
     */
    // Round-10 external review finding (16-10, PR #464): this check used to consult only the
    // token's own shape, never invalidated. PluginInitiationUtils#reinitWebSocket's exhaustion
    // branch invalidates a session (via disableCloud(session)) while deliberately leaving its
    // token in memory -- clearing the credential is logout's job, not exhaustion's, so this is not
    // itself a bug in that branch. But without an invalidated check here, an operator whose
    // session had already exhausted its reconnect budget would see /ulticloud login report
    // "Already logged in" and /ulticloud status report "Connected", directly contradicting the
    // exhaustion message that told them to run login to recover -- the socket and managers were
    // already torn down, only the in-memory token shape still looked fine. isCurrent() is the same
    // invalidated flag every other gate in this class already checks.
    boolean hasValidToken() {
        TokenEntity current = this.token;
        return isCurrent() && current != null && current.getAccess_token() != null && !current.isExpired();
    }

    // ---- Loading from disk (startup) ----

    /**
     * Loads a previously-persisted credential from disk into this session. If the access token is
     * expired but a refresh token exists, attempts a refresh (through this same session, so the
     * refreshed result is subject to the same {@link #commit(TokenEntity)} guard as every other
     * asynchronous credential operation).
     *
     * @return the loaded (or refreshed) token, or {@code null}
     */
    TokenEntity loadFromDisk() {
        try {
            CredentialStore.ReadResult result = tokenStore.readRaw();
            if (result.isAbsent()) {
                return null;
            }
            if (result.isParseFailure()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Saved credential file exists but is empty or not valid JSON; it was left "
                        + "untouched and no saved token was loaded. The SEVERE message from the "
                        + "credential store names the file and what to check.");
                return null;
            }

            TokenEntity loaded = tokenStore.parseToken(result.data());
            if (loaded == null) {
                return null;
            }
            loaded.decodeJwtPayload();

            if (loaded.isExpired()) {
                if (loaded.getRefresh_token() != null && !loaded.getRefresh_token().isEmpty()) {
                    UltiTools.getInstance().getLogger().log(Level.INFO,
                        "Saved cloud token has expired, attempting automatic refresh...");
                    TokenEntity refreshed = refresh(loaded.getRefresh_token());
                    if (refreshed != null) {
                        UltiTools.getInstance().getLogger().log(Level.INFO,
                            "Cloud token refreshed successfully!");
                        return refreshed;
                    }
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        "Token refresh failed. Use /ulticloud login to re-authenticate.");
                } else {
                    UltiTools.getInstance().getLogger().log(Level.INFO,
                        "Saved cloud token has expired and no refresh token available. Use /ulticloud login.");
                }
                return null;
            }

            this.token = loaded;
            return loaded;
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Failed to load saved cloud token: " + e.getMessage());
            return null;
        }
    }

    // ---- Refresh ----

    /**
     * Refreshes the access token using {@code refreshTokenValue}, committing the result through
     * this session. Calls {@code POST /oauth/token} with {@code grant_type=refresh_token}.
     * <p>
     * A caller obtains {@code this} before making the call -- typically {@link #current()} at the
     * moment the refresh was decided -- so whatever session this instance is, that is the session
     * the result is checked against, no matter how much time this blocking HTTP call takes or how
     * many logouts happen while it is in flight.
     *
     * @param refreshTokenValue the refresh token string
     * @return the refreshed token if the HTTP call succeeded AND this session was still current
     *         when the result came back; {@code null} otherwise
     */
    TokenEntity refresh(String refreshTokenValue) {
        String apiUrl = HttpRequestUtils.getBaseUrl();
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Cannot refresh token: API URL not configured");
            return null;
        }
        apiUrl = apiUrl.trim();

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", OAUTH2_BASIC_AUTH);

            Map<String, Object> formData = new HashMap<>();
            formData.put("grant_type", "refresh_token");
            formData.put("refresh_token", refreshTokenValue);

            SimpleHttpClient.Response response = SimpleHttpClient.post(
                apiUrl + "/oauth/token",
                headers,
                formData
            );

            if (response.isOk()) {
                TokenEntity newToken = GSON.fromJson(response.body(), TokenEntity.class);
                if (newToken != null && newToken.getAccess_token() != null) {
                    newToken.decodeJwtPayload();
                    if (!commit(newToken)) {
                        return null;
                    }
                    return newToken;
                }
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Token refresh returned invalid token data");
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Token refresh failed: HTTP " + response.getStatus() + " - " + response.body());
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Token refresh error: " + e.getMessage());
        }
        return null;
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
        buffer.enqueue(type, payload);
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
                return CloudAuthManager.ReconnectResult.Kind.ALREADY_CONNECTED;
            case CONNECTING:
                return CloudAuthManager.ReconnectResult.Kind.ALREADY_CONNECTING;
            case UPGRADE_REQUIRED:
                return CloudAuthManager.ReconnectResult.Kind.UPGRADE_REQUIRED;
            case CREDENTIAL_REJECTED:
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
                return "connected";
            case CONNECTING:
                return "connecting";
            case BACKOFF:
                return "reconnecting in " + retryDelayUnits(1000L) + " s (attempt " + agentConnection.attempt() + ")";
            case UPGRADE_REQUIRED:
                return "stopped — this UltiTools version is no longer accepted; upgrade UltiTools";
            case SUPERSEDED:
                return "stopped — another connection is using this server's credential";
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

        private boolean live() {
            return !invalidated && CloudSession.this == current() && agentConnection == connection;
        }

        private String readBearer() {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return null;
                }
                ServerCredential stored = ServerCredentialStore.load();
                attemptCredentialId = stored == null ? null : stored.getCredentialId();
                return stored == null ? null : stored.bearerValue();
            }
        }

        @Override
        public void onOpened() {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                connection.markEstablished();
                PanelConnectionLog.log(Level.INFO, "UltiCloud: connected.");
            }
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
        public void onStateChanged(AgentConnection.State state) {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
            }
        }

        @Override
        public void onText(String text) {
            synchronized (CloudSession.this) {
                if (!live()) {
                    return;
                }
                // Wave A owns only the transport; Wave B installs the v2 envelope router.
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
     * has not been invalidated -- the same guard as {@link #commit(TokenEntity)}, so a grant that
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

    // ---- Token refresh scheduler ----

    /**
     * Starts a background scheduler on this session that proactively refreshes the access token
     * before it expires (checks every hour, refreshes when &lt;2 hours remaining).
     */
    synchronized void startTokenRefreshScheduler() {
        stopTokenRefreshScheduler();
        refreshExecutor = Executors.newSingleThreadScheduledExecutor();
        refreshTask = refreshExecutor.scheduleWithFixedDelay(() -> {
            try {
                TokenEntity currentToken = this.token;
                if (currentToken == null || currentToken.getAccess_token() == null) {
                    return;
                }
                Long exp = currentToken.getExp();
                if (exp == null) {
                    return;
                }
                long remainingSeconds = exp - (System.currentTimeMillis() / 1000);
                if (remainingSeconds < TOKEN_REFRESH_THRESHOLD_SECONDS) {
                    String refreshTokenValue = currentToken.getRefresh_token();
                    if (refreshTokenValue != null && !refreshTokenValue.isEmpty()) {
                        UltiTools.getInstance().getLogger().log(Level.INFO,
                            "Access token expires in " + remainingSeconds + "s, refreshing proactively...");
                        TokenEntity refreshed = refresh(refreshTokenValue);
                        if (refreshed != null) {
                            UltiTools.getInstance().getLogger().log(Level.INFO,
                                "Proactive token refresh successful");
                        } else {
                            UltiTools.getInstance().getLogger().log(Level.WARNING,
                                "Proactive token refresh failed — WebSocket may disconnect on next reconnect");
                        }
                    }
                }
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Token refresh scheduler error: " + e.getMessage());
            }
        }, TOKEN_REFRESH_CHECK_INTERVAL_MS, TOKEN_REFRESH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop the background token refresh scheduler on this session. */
    synchronized void stopTokenRefreshScheduler() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        if (refreshExecutor != null) {
            refreshExecutor.shutdown();
            refreshExecutor = null;
        }
    }

    // ---- Panel-manager wiring (moved here from PluginInitiationUtils by plan 16-08 Task 2) ----

    /**
     * Wires all WebSocket managers up to this session's connection, but only if this session is
     * still current.
     * <p>
     * This method hangs off {@code onConnectHandler} (via
     * {@code PluginInitiationUtils.onWebSocketOpened} → {@code PluginInitiationUtils.initializeManagers()}
     * → here), and an in-flight handshake can still land after {@code /ulticloud logout}. Without a
     * guard, the listeners {@code disableCloud()} just tore down would be reinstalled verbatim by
     * this late-arriving onOpen — the exact same "no one owns the decision" defect from #181/#223,
     * resurfacing in a different place.
     * <p>
     * <b>Checking {@link #isCurrent()} alone is not enough.</b> That would be only a read taken
     * outside a lock: after it reads true but before this method actually wires anything up,
     * {@link #invalidate()} could cut in on another thread, tear everything down cleanly, and then
     * this method would continue on and wire the listeners right back up. So both this method and
     * {@link #invalidate()} synchronize on {@code this} (this session's own monitor, replacing the
     * former global lifecycle lock — see the two review rounds on PR #264 for the
     * original race), which is why the currency check below is safe to trust once taken: teardown
     * either has not started yet or has already run to completion by the time this returns from the
     * check, never caught in the middle.
     */
    synchronized void initializeManagers() {
        if (!isCurrent()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                FrameworkText.text("云连接已关闭，跳过管理器初始化（这是一次登出之后迟到的握手）"));
            return;
        }
        wireManagers();
    }

    /**
     * The actual wiring performed by {@link #initializeManagers()}. Callers must hold this
     * session's own monitor (i.e. call only from a {@code synchronized(this)} context).
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
    private void wireManagers() {
        try {
            // Wire up the server monitor manager — reference assignment is kept separate from
            // "whether to start monitoring"; see this method's javadoc
            UltiTools.getInstance().getServerMonitorManager().setWebSocketClient(webSocketClient);
            if (Capability.MONITORING.isEnabled()) {
                // Start monitoring (sends status immediately and then periodically)
                UltiTools.getInstance().getServerMonitorManager().startMonitoring();
            } else {
                PluginInitiationUtils.logSkippedCapability(Capability.MONITORING);
            }

            // Wire up the command execution manager
            UltiTools.getInstance().getCommandExecutionManager().setWebSocketClient(webSocketClient);

            // Wire up the file operation manager
            UltiTools.getInstance().getFileOperationManager().setWebSocketClient(webSocketClient);

            // Wire up the server properties manager
            if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                UltiTools.getInstance().getServerPropertiesManager().setWebSocketClient(webSocketClient);
            }

            // Wire up the log stream manager — while logs is disabled, SystemLogHandler is never
            // attached to the root logger. Records this session as the log-stream owner (16-10
            // gap-closure addendum, issue #465) under the same LOG_WIRING_LOCK
            // shutdownLogStreamManager() checks, so a later, stale teardown from a DIFFERENT
            // (already-superseded) session can recognise this wiring is not its own to detach.
            if (UltiTools.getInstance().getLogStreamManager() != null) {
                if (Capability.LOGS.isEnabled()) {
                    synchronized (LOG_WIRING_LOCK) {
                        // Ownership is recorded BEFORE calling initialize(), not after: this
                        // method's own outer try/catch means a later step inside initialize()
                        // throwing (its handler attachment happens early, well before its own
                        // final steps) must not silently skip the ownership record -- the handler
                        // it already attached is still this session's to own and eventually
                        // detach, whether or not initialize() itself ran to completion.
                        logStreamOwner = this;
                        UltiTools.getInstance().getLogStreamManager().initialize(webSocketClient);
                    }
                } else {
                    // #487: the stream will not start, so the early capture must not keep waiting.
                    EarlyLogCapture.release();
                    PluginInitiationUtils.logSkippedCapability(Capability.LOGS);
                }
            }

            // Wire up the player event manager — while player-events is disabled, the Bukkit
            // listener is never registered
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                if (Capability.PLAYER_EVENTS.isEnabled()) {
                    UltiTools.getInstance().getPlayerEventManager().initialize(webSocketClient);
                } else {
                    PluginInitiationUtils.logSkippedCapability(Capability.PLAYER_EVENTS);
                }
            }

            UltiTools.getInstance().getLogger().log(Level.FINE, FrameworkText.text("所有WebSocket管理器已初始化并启动监控"));
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, FrameworkText.format("初始化管理器时出错: %s", e.getMessage()), e);
        }
    }
}
