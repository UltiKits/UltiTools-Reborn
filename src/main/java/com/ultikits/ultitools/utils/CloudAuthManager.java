package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Date;
import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;

/**
 * The three command-facing entry points behind {@code /ulticloud login|logout|status}.
 * <p>
 * As of plan 16-09 (D-17) this class owns no state of its own and exposes no generation-shaped or
 * {@link TokenEntity}-shaped public surface -- everything that used to be a fine-grained public
 * static (the generation triple, {@code saveToken}/{@code clearToken}/{@code refreshToken}/
 * {@code loadSavedToken}, the start/stop pairs for polling and refresh) is gone outright, not
 * deprecated: {@link CloudSession} instance methods now carry that behaviour, reached only through
 * {@link #login(Runnable, Consumer, Runnable, Consumer, Consumer)}, {@link #logout()} and
 * {@link #status()} below. Keeping a delegating shim for any of them would leave the generation
 * convention callable, which is exactly what issue #298's second acceptance criterion requires to
 * stop being true (see plan 16-08's {@link CloudSession} javadoc for the mechanism this replaced).
 * <p>
 * {@code CredentialStaticSurfaceInvariantTest} enforces this structurally: no public static method
 * anywhere in this package may accept or return a {@link TokenEntity} or a generation (D-18), so a
 * static bypass of {@link CloudSession#commit(TokenEntity)} cannot be reintroduced unnoticed.
 * <p>
 * Marked {@link ApiStatus.Internal} -- this class exists to serve {@code CloudLoginCommand}'s three
 * {@code @CmdMapping} methods and is not part of the framework's public API surface for module
 * authors.
 */
@ApiStatus.Internal
public class CloudAuthManager {

    /** The longest {@code suggestedName} UltiCloud accepts, in code points (contract answer 13). */
    static final int MAX_SUGGESTED_NAME_CODE_POINTS = 64;
    /** The message a login reports when an overlapping login already replaced its session. */
    static final String LOGIN_IN_PROGRESS = "Another login attempt is already in progress; please try again";

    private CloudAuthManager() {
    }

    /**
     * Links this server to UltiCloud with the RFC 8628 device authorization grant (protocol v2).
     * <p>
     * Runs the already-linked check (a credential held by the current session or stored in
     * {@code credentials.json}), the rate-limit check, then replaces the current session and returns.
     * The device-code request and the polling run on the new session's own poll executor, so a
     * caller on the server's primary thread is never blocked on the network; their outcome reaches
     * {@code listener} on that executor's thread.
     * <p>
     * The lock structure is the one the 6.3.0 magic-link login used, unchanged: {@code CloudSession.class},
     * then the captured session's monitor, the re-check, the
     * {@code sessionAtCheck != CloudSession.current()} guard, {@code disableCloud(sessionAtCheck)},
     * {@code startNew()} -- the documented lock order of {@link CloudSession}'s class javadoc.
     *
     * @param listener receives every outcome; must not be {@code null}
     * @since 6.3.1
     */
    public static void login(DeviceLoginListener listener) {
        Objects.requireNonNull(listener, "listener");
        CloudSession sessionAtCheck = CloudSession.current();
        ServerCredential linked = linkedCredential(sessionAtCheck);
        if (linked != null) {
            listener.onAlreadyLinked(linked.getServerName());
            return;
        }
        if (!ApiRateLimiter.isLoginAllowed()) {
            listener.onRateLimited(ApiRateLimiter.getRemainingCooldown("login", 60_000));
            return;
        }
        // Issue #466: everything from the re-check through replacing the session is one atomic
        // region against CloudSession#commitCredential, acquiring CloudSession.class BEFORE
        // sessionAtCheck's own monitor -- the same order CloudSession#startNew() uses. Synchronizing
        // on sessionAtCheck alone and letting disableCloud()'s own synchronized(CloudSession.class)
        // block run inside it would invert that order: an AB-BA deadlock against a concurrent
        // startNew(). Holding both here means a grant from an earlier login attempt either commits
        // before this region (and the re-check sees it) or cannot enter commitCredential() until
        // disableCloud() has invalidated the session, so it is discarded instead of orphaned.
        CloudSession newSession;
        synchronized (CloudSession.class) {
            synchronized (sessionAtCheck) {
                // A grant from an earlier login attempt can still commit onto sessionAtCheck between
                // the check above and this line; it is reported as already linked, not replaced.
                linked = linkedCredential(sessionAtCheck);
                if (linked != null) {
                    listener.onAlreadyLinked(linked.getServerName());
                    return;
                }
                // Round-14 review (PR #464): ApiRateLimiter#isAllowed's check-then-set is not atomic,
                // so two overlapping logins can both pass it against the same sessionAtCheck. The
                // second must not call startNew() -- that would invalidate the FIRST login's fresh
                // session, whose code is already on its way to the operator. This read is stable:
                // only startNew() changes current(), and it also holds CloudSession.class.
                if (sessionAtCheck != CloudSession.current()) {
                    listener.onRequestFailed(LOGIN_IN_PROGRESS);
                    return;
                }
                listener.onRequesting();
                // Round-1 review (PR #464): startNew() tears down only the session's own resources;
                // the global server monitor and player-event manager are stopped only by
                // disableCloud(session). Tearing the stale session down completely first means a
                // failed or abandoned device login never leaves them wired to a closed socket.
                PluginInitiationUtils.disableCloud(sessionAtCheck);
                newSession = CloudSession.startNew();
            }
        }
        // The device-code request is a network call: it stays outside both locks and off the
        // caller's thread, on the new session's own executor.
        newSession.beginDeviceLogin(listener);
    }

    /**
     * @return the credential {@code session} committed, else the one stored in
     *         {@code credentials.json}, else {@code null}
     */
    static ServerCredential linkedCredential(CloudSession session) {
        ServerCredential held = session.getCredential();
        return held != null ? held : ServerCredentialStore.load();
    }

    /**
     * @return the running framework version, sent verbatim (a {@code -SNAPSHOT} suffix included):
     *         the plugin description's version, else {@code env.yml}'s, else {@code unknown}
     */
    // UltiTools.getEnv() throws an unchecked ConfigurationException when env.yml is missing; the
    // version is then simply unknown.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    static String runningFrameworkVersion() {
        UltiTools plugin = UltiTools.getInstance();
        PluginDescriptionFile description = plugin == null ? null : plugin.getDescription();
        if (description != null && description.getVersion() != null && !description.getVersion().trim().isEmpty()) {
            return description.getVersion();
        }
        try {
            String version = UltiTools.getEnv().getString("version");
            if (version != null && !version.trim().isEmpty()) {
                return version;
            }
        } catch (RuntimeException e) {
            // Fall through to "unknown".
        }
        return "unknown";
    }

    /**
     * @return the server's MOTD as plain text, at most {@value #MAX_SUGGESTED_NAME_CODE_POINTS} code
     *         points, or {@code null} when there is no server or the MOTD is blank (the field is then
     *         omitted; the owner names the server on the confirmation page either way)
     */
    @SuppressWarnings("deprecation") // Server#getMotd(): the legacy-text form is what is wanted here
    static String suggestedServerName() {
        Server server = Bukkit.getServer();
        return server == null ? null : normaliseSuggestedName(server.getMotd());
    }

    /**
     * @param motd a legacy-formatted MOTD, or {@code null}
     * @return the MOTD without colour codes, with every run of whitespace or control characters
     *         collapsed to one space, trimmed and cut to {@value #MAX_SUGGESTED_NAME_CODE_POINTS}
     *         code points without splitting a surrogate pair; {@code null} when nothing remains
     */
    static String normaliseSuggestedName(String motd) {
        if (motd == null) {
            return null;
        }
        String plain = ChatColor.stripColor(motd);
        StringBuilder builder = new StringBuilder(plain.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < plain.length()) {
            int codePoint = plain.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.appendCodePoint(codePoint);
        }
        String name = builder.toString();
        if (name.isEmpty()) {
            return null;
        }
        if (name.codePointCount(0, name.length()) > MAX_SUGGESTED_NAME_CODE_POINTS) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_SUGGESTED_NAME_CODE_POINTS)).trim();
        }
        return name;
    }

    /**
     * @return this server's stored install id ({@code uuid} in {@code credentials.json}) normalised by
     *         {@link LegacyServerId#canonical(String)}, or {@code null}; reads only, never writes
     */
    static String storedLegacyServerUuid() {
        CredentialStore.ReadResult result = CredentialStore.read();
        if (!result.isParsed()) {
            return null;
        }
        Object uuid = result.data().get("uuid");
        return uuid instanceof String ? LegacyServerId.canonical((String) uuid) : null;
    }

    /**
     * The 6.3.0 callback form of {@link #login(DeviceLoginListener)}, kept with its signature for
     * compatibility. It now runs the device authorization: {@code onSuccess} receives the
     * verification URI with the code filled in ({@code verificationUriComplete}); the later outcome
     * (linked, denied, expired) is reported only in the server log.
     *
     * @param onAlreadyLoggedIn invoked, with nothing else attempted, if this server is already linked
     * @param onRateLimited     invoked with the remaining cooldown in seconds if the login attempt
     *                          is currently rate-limited
     * @param onRequesting      invoked right before the device-code request is made
     * @param onSuccess         invoked with the complete verification URI once a code was issued
     * @param onError           invoked with an error message if the request fails
     * @deprecated Use {@link #login(DeviceLoginListener)} instead; removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    public static void login(Runnable onAlreadyLoggedIn, Consumer<Long> onRateLimited,
            Runnable onRequesting, Consumer<String> onSuccess, Consumer<String> onError) {
        login(new DeviceLoginListener() {
            @Override
            public void onAlreadyLinked(String serverName) {
                onAlreadyLoggedIn.run();
            }

            @Override
            public void onRateLimited(long seconds) {
                onRateLimited.accept(seconds);
            }

            @Override
            public void onRequesting() {
                onRequesting.run();
            }

            @Override
            public void onCode(String verificationUri, String userCode, String verificationUriComplete,
                    long expiresInSeconds) {
                onSuccess.accept(verificationUriComplete);
            }

            @Override
            public void onRequestFailed(String reason) {
                onError.accept(reason);
            }
        });
    }

    /**
     * Tears down cloud features unconditionally, then asynchronously revokes and deletes the
     * protocol v2 credential. The revoke and delete finish after this method returns; use
     * {@link #logoutReporting(Consumer)} to observe their outcome. The token-era cleanup remains
     * synchronous while that internal code is retained. In both forms teardown must run
     * even when there is nothing to clear (an expired-but-still-connected session is the case that
     * most needs logout to take effect), and the credential is read only <b>after</b> teardown so an
     * in-flight login that commits mid-teardown is still caught and cleared -- see
     * {@code CloudLoginCommand.logout(CommandSender)}'s own comments for why that order matters.
     * <p>
     * <b>CR-01 (16-REVIEW-cloud.md):</b> this method captures {@link CloudSession#current()} exactly
     * once -- in the statement below, immediately before teardown begins -- and passes that captured
     * reference to {@link PluginInitiationUtils#disableCloud(CloudSession)}, which acts on and
     * returns that exact instance. The disk-clear decision then acts on the returned reference,
     * never on a second, independent call to {@link CloudSession#current()} taken after teardown
     * returns. Reading {@code current()} a second time, afterward, used to be exactly the bug: a
     * concurrent {@code login()} (unsynchronized, and reachable from a different thread via
     * {@code @RunAsync}) can install a brand-new session with {@link CloudSession#startNew()}
     * in the gap while teardown is running, and that second read would then see the NEW session's
     * (always {@code null}) token instead of the one actually being logged out of -- concluding
     * "nothing to clear" and leaving the real credential on disk. Acting on the one reference this
     * method already holds makes that race structurally impossible: there is no second read left to
     * disagree with the first. This method deliberately does <b>not</b> call
     * {@link CloudSession#startNew()} itself any more either; clearing the torn-down session in
     * place is sufficient, and the next real login installs a fresh session via its own
     * {@code CloudSession.startNew()} call, exactly as it always has.
     * <p>
     * <b>Round-1 external review finding, corrected in the same plan:</b> the paragraph above
     * establishes WHICH session's disk-clear decision this method acts on, but not what that
     * clear is safe to remove. {@link CloudSession#clearPersisted()} does NOT wipe disk
     * unconditionally -- it delegates to {@link TokenStore#clearIfMatches(TokenEntity)}, a
     * compare-and-delete keyed on the torn-down session's own last-known token. This matters
     * because a concurrent {@code login()} can still WRITE a fresh credential to the same shared
     * document while this session's teardown is in flight, even though its own session-invalidation
     * race is already closed by the paragraph above -- see {@link CloudSession#clearPersisted()}'s
     * own javadoc for the full account of why an unconditional clear would remove that fresh write.
     * <p>
     * <b>Documented semantics for logout racing a concurrent login (CR-01's second half):</b> this
     * method's teardown always acts on whichever session was current at the single instant this
     * method's own {@link CloudSession#current()} call below reads it -- a single, fixed point in
     * time this method controls directly, rather than one buried inside a callee. A {@code login()}
     * that installs its new session <b>before</b> that read wins outright: this {@code logout()}
     * call never sees or touches it. A {@code login()} that installs its new session <b>after</b>
     * that read has already lost the SESSION-invalidation race regardless of what this method does
     * -- {@link CloudSession#startNew()} itself unconditionally invalidates whatever session it
     * replaces, so the fresh login's session object is torn down by that call alone, independent of
     * this command. What {@link CloudSession#clearPersisted()}'s compare-and-delete adds on top is
     * the disk-level half of that same guarantee: even if the fresh login's own commit reaches the
     * shared document before this method's clear does, the clear cannot remove a value the
     * torn-down session never itself wrote.
     *
     * @return {@code true} if a credential existed and cleanup was started, or a pending authentication
     *         attempt was cancelled (real-machine UAT fix, plan 16-08 -- see
     *         {@link CloudSession#hasAnythingToClear()}); {@code false} if there was nothing to
     *         clear and nothing pending (teardown still ran regardless)
     * @throws IOException if clearing the persisted credential fails
     */
    public static synchronized boolean logout() throws IOException {
        return beginLogout(outcome -> { });
    }

    /**
     * Stops the captured session synchronously, then revokes and deletes its credential on a Bukkit
     * asynchronous task. The callback runs on that task, or inline when no credential is held.
     * A local storage failure is propagated or reported by the scheduler, never reported as deletion.
     *
     * @param onDone receives the completed outcome
     * @since 6.3.1
     */
    public static synchronized void logoutReporting(Consumer<LogoutOutcome> onDone) {
        Objects.requireNonNull(onDone, "onDone");
        try {
            beginLogout(onDone);
        } catch (IOException failure) {
            throw new com.ultikits.ultitools.exceptions.DataAccessException("Could not clear the saved cloud credential");
        }
    }

    private static boolean beginLogout(Consumer<LogoutOutcome> onDone) throws IOException {
        CloudSession tornDown;
        ServerCredential retiring;
        boolean hadAnything;
        boolean cancelled;
        synchronized (CloudSession.class) {
            tornDown = PluginInitiationUtils.disableCloud(CloudSession.current());
            retiring = linkedCredential(tornDown);
            hadAnything = tornDown.hasAnythingToClear() || retiring != null;
            cancelled = tornDown.consumePendingCancellation();
            if (retiring == null && tornDown.hasAnythingToClear()) {
                tornDown.clearPersisted();
            }
        }
        if (retiring == null) {
            onDone.accept(cancelled ? LogoutOutcome.CANCELLED_PENDING : LogoutOutcome.NOT_LINKED);
        } else {
            final CloudSession owner = tornDown;
            final ServerCredential credential = retiring;
            Bukkit.getScheduler().runTaskAsynchronously(UltiTools.getInstance(), () -> {
                CloudHttp.Response response = CloudHttp.postJson("/v1/agent/credential/revoke", null,
                        credential.bearerValue());
                owner.completeCredentialLogout(credential);
                onDone.accept(response.outcome() == CloudHttp.Outcome.OK
                        && (response.status() == 200 || response.status() == 401)
                        ? LogoutOutcome.REVOKED : LogoutOutcome.DELETED_LOCALLY_REVOKE_FAILED);
            });
        }
        return hadAnything;
    }

    /** Completed unlink outcomes; none contains a credential. @since 6.3.1 */
    public enum LogoutOutcome {
        NOT_LINKED, REVOKED, DELETED_LOCALLY_REVOKE_FAILED, CANCELLED_PENDING
    }

    /**
     * @return an immutable snapshot of the current session's connection state, for
     *         {@code /ulticloud status} to render -- never the token itself (D-18)
     */
    public static CloudStatus status() {
        CloudSession session = CloudSession.current();
        synchronized (session) {
            ServerCredential linked = linkedCredential(session);
            String serverName = linked == null ? null : linked.getServerName();
            String serverId = linked == null ? null : linked.getServerId();
            String connection = session.connectionDescription();
            return new CloudStatus("connected".equals(connection), linked != null, serverName, serverId, connection);
        }
    }

    /**
     * An immutable view of {@link #status()}'s result. Deliberately carries only the fields
     * {@code /ulticloud status} actually prints -- never a {@link TokenEntity} reference -- so this
     * type cannot become a second way to leak the token past the D-18 guard.
     */
    public static final class CloudStatus {

        private final boolean connected;
        private final boolean linked;
        private final String serverName;
        private final String serverId;
        private final String connectionDescription;

        private CloudStatus(boolean connected, boolean linked, String serverName, String serverId,
                String connectionDescription) {
            this.connected = connected;
            this.linked = linked;
            this.serverName = serverName;
            this.serverId = serverId;
            this.connectionDescription = connectionDescription;
        }

        /** @return a description of the current connection for the operator
         * @since 6.3.1
         */
        public String getConnectionDescription() {
            return connectionDescription;
        }

        /** @return {@code true} only while the current agent connection is open */
        public boolean isConnected() {
            return connected;
        }

        /**
         * @return {@code null}; protocol v2 holds no user token
         * @deprecated Use {@link #getServerName()} or {@link #getServerId()} instead; removed in 6.4.0.
         * @removeIn 6.4.0
         */
        @Deprecated(since = "6.3.1", forRemoval = true)
        public String getUserName() {
            return null;
        }

        /**
         * @return {@code null}; the server credential never expires
         * @deprecated Use {@link #getServerName()} or {@link #getServerId()} for identity instead; removed in 6.4.0.
         * @removeIn 6.4.0
         */
        @Deprecated(since = "6.3.1", forRemoval = true)
        public Date getExpirationDate() {
            return null;
        }

        /**
         * @return {@code true} if this server holds a protocol v2 server credential
         * @since 6.3.1
         */
        public boolean isLinked() {
            return linked;
        }

        /**
         * @return the linked server's name as chosen on the confirmation page, or {@code null} if not
         *         linked or no name was returned
         * @since 6.3.1
         */
        public String getServerName() {
            return serverName;
        }

        /**
         * @return the linked server's public id, or {@code null} if not linked
         * @since 6.3.1
         */
        public String getServerId() {
            return serverId;
        }
    }

    /**
     * The outcomes of {@link #login(DeviceLoginListener)}. {@link #onAlreadyLinked},
     * {@link #onRateLimited}, {@link #onRequesting} and an overlapping-login
     * {@link #onRequestFailed} are called on the caller's thread before {@code login} returns; every
     * other callback arrives later on the login session's poll executor thread.
     *
     * @since 6.3.1
     */
    public interface DeviceLoginListener {

        /** @param serverName the name this server is linked under, or {@code null} */
        void onAlreadyLinked(String serverName);

        /** @param seconds the remaining cooldown before another login attempt is allowed */
        void onRateLimited(long seconds);

        /** The device-code request is about to be made. */
        void onRequesting();

        /**
         * A device code was issued; show it to the operator.
         *
         * @param verificationUri         the page where the owner enters the code
         * @param userCode                the {@code XXXX-XXXX} code
         * @param verificationUriComplete the page with the code filled in
         * @param expiresInSeconds        how long the code stays valid
         */
        void onCode(String verificationUri, String userCode, String verificationUriComplete, long expiresInSeconds);

        /** @param reason why no device code could be obtained */
        void onRequestFailed(String reason);

        /**
         * The owner confirmed and the credential was saved.
         *
         * @param serverName the name chosen on the confirmation page, or {@code null}
         * @param serverId   the server's public id
         */
        default void onLinked(String serverName, String serverId) {
            // The session logs the linked line itself.
        }

        /** The owner refused on the confirmation page. */
        default void onDenied() {
            // The session logs the denied line itself.
        }

        /** The code expired before it was confirmed. */
        default void onExpired() {
            // The session logs the expired line itself.
        }

        /**
         * UltiCloud rejected the request.
         *
         * @param code    the backend error code
         * @param message the backend message, sanitised
         */
        default void onRejected(String code, String message) {
            // The session logs the rejected line itself.
        }
    }
}
