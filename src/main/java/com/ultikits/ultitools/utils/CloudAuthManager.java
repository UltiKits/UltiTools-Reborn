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
        if (CloudSession.current().hasValidToken()) {
            onAlreadyLoggedIn.run();
            return;
        }
        if (!ApiRateLimiter.isLoginAllowed()) {
            onRateLimited.accept(ApiRateLimiter.getRemainingCooldown("login", 60_000));
            return;
        }
        onRequesting.run();
        String url = CloudSession.startNew().requestMagicLink(onError);
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
     * {@link CloudSession#startNew()} itself any more either -- {@link CloudSession#clearPersisted()}
     * does not gate on session identity (it always wipes disk unconditionally,
     * {@link CloudSession#clearPersisted() its own javadoc}), so clearing the torn-down session in
     * place is sufficient; the next real login installs a fresh session via its own
     * {@code CloudSession.startNew()} call, exactly as it always has.
     * <p>
     * <b>Documented semantics for logout racing a concurrent login (CR-01's second half):</b> this
     * method's teardown always acts on whichever session was current at the single instant this
     * method's own {@link CloudSession#current()} call below reads it -- a single, fixed point in
     * time this method controls directly, rather than one buried inside a callee. A {@code login()}
     * that installs its new session <b>before</b> that read wins outright: this {@code logout()}
     * call never sees or touches it. A {@code login()} that installs its new session <b>after</b>
     * that read has already lost the credential race regardless of what this method does --
     * {@link CloudSession#startNew()} itself unconditionally invalidates whatever session it
     * replaces, so the fresh login is torn down by that call alone, independent of this command.
     * There is no window in which this method invalidates a login it did not already lose to
     * {@code startNew()}'s own contract.
     *
     * @return {@code true} if a credential existed and was cleared; {@code false} if there was
     *         nothing to clear (teardown still ran regardless)
     * @throws IOException if clearing the persisted credential fails
     */
    public static synchronized boolean logout() throws IOException {
        CloudSession tornDown = PluginInitiationUtils.disableCloud(CloudSession.current());

        if (tornDown.getToken() == null) {
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
