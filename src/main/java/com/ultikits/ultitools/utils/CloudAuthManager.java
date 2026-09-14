package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Date;
import java.util.function.Consumer;

import org.jetbrains.annotations.ApiStatus;

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

    private CloudAuthManager() {
    }

    /**
     * Attempts a magic-link login on a fresh session, covering the already-logged-in check, the
     * rate-limit check, the magic-link request and starting the poll -- exactly the sequence
     * {@code /ulticloud login} performed before this plan, just no longer expressed through
     * separate public statics on this class. Every branch is reported back through the callback
     * that names it, so {@code CloudLoginCommand.login(CommandSender)} prints the exact same
     * messages it always has; only where the decision is made moved.
     *
     * @param onAlreadyLoggedIn invoked, with nothing else attempted, if a valid token already exists
     * @param onRateLimited     invoked with the remaining cooldown in seconds if the login attempt
     *                          is currently rate-limited
     * @param onRequesting      invoked right before the magic-link HTTP request is made
     * @param onSuccess         invoked with the magic-link URL once the request succeeds
     * @param onError           invoked with an error message if the magic-link request fails
     */
    public static void login(Runnable onAlreadyLoggedIn, Consumer<Long> onRateLimited,
            Runnable onRequesting, Consumer<String> onSuccess, Consumer<String> onError) {
        // Captured once, at the very top, and used consistently below -- see the round-1 review's
        // third finding on this method for why a second, independent read of CloudSession.current()
        // further down is unsafe here.
        CloudSession sessionAtCheck = CloudSession.current();
        if (sessionAtCheck.hasValidToken()) {
            onAlreadyLoggedIn.run();
            return;
        }
        if (!ApiRateLimiter.isLoginAllowed()) {
            onRateLimited.accept(ApiRateLimiter.getRemainingCooldown("login", 60_000));
            return;
        }

        // 16-10 gap-closure addendum, issue #466: everything from the final re-check through
        // replacing the session is one atomic region against CloudSession#commit(TokenEntity),
        // acquiring CloudSession.class BEFORE sessionAtCheck's own monitor -- the SAME order
        // CloudSession#startNew() itself already uses (see CloudSession's own class javadoc for the
        // full documented lock order). The naive fix here -- synchronizing on sessionAtCheck alone,
        // then letting disableCloud()'s own synchronized(CloudSession.class) block execute while
        // still holding it -- inverts that order (session monitor outer, class lock inner) and is a
        // genuine AB-BA deadlock against a concurrent startNew() (class lock outer, session monitor
        // inner): reachable in production via a racing /ulticloud login or /ulticloud logout.
        // Acquiring CloudSession.class first here closes the window WITHOUT that risk: a magic-link
        // poll from an EARLIER login attempt (see the round-1 review's third finding, which the
        // re-check below still exists to catch) either completes its own commit() BEFORE this region
        // starts -- in which case the re-check below sees it and reports "already logged in" -- or it
        // cannot even enter commit()'s own synchronized(this) until this ENTIRE region has finished
        // and released sessionAtCheck's monitor, by which point disableCloud() has already set
        // invalidated, so commit() correctly rejects it instead of silently writing a credential that
        // then gets orphaned by the replacement already in progress.
        CloudSession newSession;
        synchronized (CloudSession.class) {
            synchronized (sessionAtCheck) {
                // Round-1 external review finding, third pass (PR #464), re-verified atomic by this
                // addendum: a magic-link poll from an EARLIER login attempt can still be in flight
                // here -- polling lasts up to 5 minutes, well past the 1-minute login cooldown
                // ApiRateLimiter.isLoginAllowed() just cleared above -- and could commit a valid
                // token onto sessionAtCheck in the gap between the hasValidToken() check above and
                // this line. Re-checking sessionAtCheck itself (never a fresh CloudSession.current()
                // read, which could by now point somewhere else entirely) catches that: a session
                // that became validly authenticated while this method was mid-flight is reported as
                // "already logged in," not torn down and replaced by a redundant second login.
                if (sessionAtCheck.hasValidToken()) {
                    onAlreadyLoggedIn.run();
                    return;
                }
                onRequesting.run();
                // Round-1 external review finding, first pass (PR #464): CloudSession#startNew()
                // alone only tears down the session's OWN resources (schedulers, WebSocket client)
                // -- it does not stop the global server monitor or player-event manager, which live
                // outside any session and are only ever stopped by
                // PluginInitiationUtils#disableCloud(CloudSession)'s own two session-independent
                // teardown steps. Reaching this line means sessionAtCheck's token is missing or
                // expired (both checks above already failed), so its cloud lifecycle -- if one is
                // still running -- is stale by definition. Tearing it down completely before
                // replacing it means a magic-link request that then fails, or never resolves, never
                // leaves those global managers wired to a socket that already closed with no
                // successor connection to ever rewire them. Acts on sessionAtCheck specifically, not
                // a fresh current() read, for the same reason the re-check above does.
                PluginInitiationUtils.disableCloud(sessionAtCheck);
                newSession = CloudSession.startNew();
            }
        }
        // The multi-second magic-link HTTP POST deliberately stays OUTSIDE both locks -- holding
        // either for the duration of a network call would let one login attempt stall every other
        // session-lifecycle operation on the whole plugin.
        String url = newSession.requestMagicLink(onError);
        if (url != null) {
            onSuccess.accept(url);
        }
    }

    /**
     * Tears down cloud features unconditionally, then clears the persisted credential if one
     * existed. Mirrors {@code /ulticloud logout}'s pre-16-09 sequence exactly: teardown must run
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
     * @return {@code true} if a credential existed and was cleared; {@code false} if there was
     *         nothing to clear (teardown still ran regardless)
     * @throws IOException if clearing the persisted credential fails
     */
    public static synchronized boolean logout() throws IOException {
        CloudSession tornDown = PluginInitiationUtils.disableCloud(CloudSession.current());

        // 16-10 gap-closure addendum (round-13 review, PR #464), P1: gate on
        // hasAnythingToClear(), not getToken() == null alone -- see CloudSession#predecessorToken's
        // own javadoc. A session that never itself held a token but replaced one that did
        // (reconnect exhaustion followed by /ulticloud login, before any logout ran) must still
        // reach clearPersisted() below, or the predecessor's still-valid, still-persisted
        // credential survives on disk despite this explicit logout, and a later restart reloads
        // and reconnects with it.
        if (!tornDown.hasAnythingToClear()) {
            return false;
        }

        tornDown.clearPersisted();
        return true;
    }

    /**
     * @return an immutable snapshot of the current session's connection state, for
     *         {@code /ulticloud status} to render -- never the token itself (D-18)
     */
    public static CloudStatus status() {
        CloudSession session = CloudSession.current();
        if (!session.hasValidToken()) {
            return new CloudStatus(false, null, null);
        }
        TokenEntity token = session.getToken();
        String userName = token.getUser_name() != null ? token.getUser_name() : "Unknown";
        return new CloudStatus(true, userName, token.getExpirationDate());
    }

    /**
     * An immutable view of {@link #status()}'s result. Deliberately carries only the fields
     * {@code /ulticloud status} actually prints -- never a {@link TokenEntity} reference -- so this
     * type cannot become a second way to leak the token past the D-18 guard.
     */
    public static final class CloudStatus {

        private final boolean connected;
        private final String userName;
        private final Date expirationDate;

        private CloudStatus(boolean connected, String userName, Date expirationDate) {
            this.connected = connected;
            this.userName = userName;
            this.expirationDate = expirationDate;
        }

        /** @return {@code true} if the current session holds a valid (non-expired) token */
        public boolean isConnected() {
            return connected;
        }

        /** @return the connected user's display name, or {@code null} if not connected */
        public String getUserName() {
            return userName;
        }

        /** @return the current token's expiration date, or {@code null} if not connected or unset */
        public Date getExpirationDate() {
            return expirationDate;
        }
    }
}
