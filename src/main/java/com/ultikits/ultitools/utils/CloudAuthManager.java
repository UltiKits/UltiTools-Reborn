package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.function.Consumer;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * Manages UltiCloud authentication tokens.
 * Supports magic-link login (no password needed) and token persistence.
 * <p>
 * As of 6.3.0 this class owns no state of its own. Every method below is a thin delegator onto
 * {@link CloudSession#current()} -- the single object that actually owns the token, the poll and
 * refresh schedulers, and (as of plan 16-08's Task 2) the WebSocket client, the reconnect backoff
 * state, and the panel-manager wiring (D-16). The generation-counter <b>mechanism</b> this class
 * used to implement directly -- a static {@code currentToken}, a static poll/refresh scheduler
 * pair, and an {@code AtomicLong} generation that every asynchronous credential operation compared
 * against before committing its result -- is gone: session identity replaces it, and no code path
 * anywhere in this package re-reads or re-advances a generation counter to decide whether a commit
 * is current. See {@link CloudSession}'s own javadoc for the invariant this replaces, quoted from
 * this class's pre-6.3.0 form.
 * <p>
 * The four generation-shaped methods below ({@link #currentCredentialGeneration()},
 * {@link #invalidateCredentialOperations()}, {@link #commitTokenIfCurrent(TokenEntity, long)}, and
 * the three-argument {@link #startPolling(String, Consumer, long)}) are <b>compatibility shims
 * only</b> -- kept present, still callable, but no longer backed by the mechanism their signature
 * implies (each is measured, per D-17, to have zero external callers across every published module
 * JAR and every local module/plugin source). Plan 16-09 removes them outright (D-17), with a
 * japicmp exclude recorded per member at that point; this plan does not remove any public member of
 * this class, so no exclude is needed yet.
 * <p>
 * This public static surface stays as a delegating facade for this plan (16-08); plan 16-09 narrows
 * it (D-17), leaving only the three command-facing entry points {@code login}/{@code logout}/
 * {@code status} public.
 */
public class CloudAuthManager {

    /**
     * Try to load a saved token from data.json on startup.
     * If the access token is expired but a refresh token exists, attempts automatic refresh.
     * Returns the token if valid, null otherwise.
     */
    public static TokenEntity loadSavedToken() {
        return CloudSession.current().loadFromDisk();
    }

    /**
     * Refresh the access token using the refresh token.
     * Calls POST /oauth/token with grant_type=refresh_token.
     *
     * @param refreshTokenValue the refresh token string
     * @return a new TokenEntity with fresh access and refresh tokens, or null on failure
     */
    public static TokenEntity refreshToken(String refreshTokenValue) {
        return CloudSession.current().refresh(refreshTokenValue);
    }

    /**
     * Save the current token to data.json for persistence across restarts.
     */
    public static void saveToken(TokenEntity token) throws IOException {
        CloudSession.current().commit(token);
    }

    /**
     * Clear the saved token (logout). Replaces the current session with a fresh one (D-16) --
     * everything the old session had in flight lapses because {@link CloudSession#invalidate()}
     * ran on it, then wipes the persisted credential.
     */
    public static synchronized void clearToken() throws IOException {
        CloudSession session = CloudSession.startNew();
        session.clearPersisted();
    }

    /**
     * Get the current token (in-memory).
     */
    public static TokenEntity getCurrentToken() {
        return CloudSession.current().getToken();
    }

    /**
     * Check if we have a valid (non-expired) token.
     */
    public static boolean hasValidToken() {
        return CloudSession.current().hasValidToken();
    }

    /**
     * Request a magic link for server authentication.
     * Returns the URL the admin should open in their browser, or null on failure.
     * <p>
     * Constructs a new {@link CloudSession} (this <b>is</b> D-16's "login creates a session"),
     * replacing whatever session was current and invalidating it -- so a login attempt started
     * while a previous one was still polling does not leave two pollers racing each other.
     *
     * @param errorCallback called with error message if the request fails
     * @return the magic link URL, or null on failure
     */
    public static String requestMagicLink(Consumer<String> errorCallback) {
        return CloudSession.startNew().requestMagicLink(errorCallback);
    }

    /**
     * Start polling for magic-link auth completion, on the current session.
     *
     * @param requestId the magic link request ID
     * @param onComplete called when auth succeeds (with the token), or null if no callback needed
     */
    public static void startPolling(String requestId, Consumer<TokenEntity> onComplete) {
        CloudSession.current().startPolling(requestId, onComplete);
    }

    /**
     * The three-argument overload of {@link #startPolling(String, Consumer)} that used to take an
     * explicit credential generation.
     *
     * @param requestId  the magic link request ID
     * @param onComplete called when auth succeeds (with the token), or null if no callback needed
     * @param generation ignored -- session identity, not a generation, now decides currency
     * @deprecated Compatibility shim only (D-17) -- measured 0 external callers across every
     * published module JAR and every local module/plugin source. The {@code generation} parameter
     * is accepted and ignored; polling always runs on {@link CloudSession#current()}. Scheduled
     * for removal by plan 16-09.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static void startPolling(String requestId, Consumer<TokenEntity> onComplete, long generation) {
        CloudSession.current().startPolling(requestId, onComplete);
    }

    /**
     * The credential lifecycle generation this class used to expose directly.
     *
     * @return {@code 0L} unconditionally -- there is no generation counter to read any more
     * @deprecated Compatibility shim only (D-17) -- measured 0 external callers across every
     * published module JAR and every local module/plugin source. {@link CloudSession} identity
     * replaced this counter outright; nothing in this package reads or advances a generation to
     * decide currency any more. Scheduled for removal by plan 16-09.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static long currentCredentialGeneration() {
        return 0L;
    }

    /**
     * Invalidates the current session, mirroring what this method used to do to a shared
     * generation counter.
     *
     * @deprecated Compatibility shim only (D-17) -- measured 0 external callers across every
     * published module JAR and every local module/plugin source. Delegates to
     * {@link CloudSession#current()}'s own {@link CloudSession#invalidate()} rather than advancing
     * a counter that no longer exists. Scheduled for removal by plan 16-09.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static void invalidateCredentialOperations() {
        CloudSession.current().invalidate();
    }

    /**
     * Commits {@code token} through the current session, ignoring {@code generation}.
     *
     * @param token the credential to commit
     * @param generation ignored -- session identity, not a generation, now decides currency
     * @return {@code true} if committed; {@code false} if the current session has been invalidated
     * @throws IOException if the write fails
     * @deprecated Compatibility shim only (D-17) -- measured 0 external callers across every
     * published module JAR and every local module/plugin source. {@link CloudSession#commit(TokenEntity)}
     * on {@link CloudSession#current()} is the real guard; the {@code generation} parameter is
     * accepted only so this signature still compiles against any (nonexistent) caller. Scheduled
     * for removal by plan 16-09.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static synchronized boolean commitTokenIfCurrent(TokenEntity token, long generation) throws IOException {
        return CloudSession.current().commit(token);
    }

    /**
     * Start a background scheduler that proactively refreshes the access token
     * before it expires (checks every hour, refreshes when &lt;2 hours remaining), on the current
     * session.
     */
    public static void startTokenRefreshScheduler() {
        CloudSession.current().startTokenRefreshScheduler();
    }

    /**
     * Stop the background token refresh scheduler on the current session.
     */
    public static void stopTokenRefreshScheduler() {
        CloudSession.current().stopTokenRefreshScheduler();
    }

    /**
     * Stop polling for magic-link completion on the current session.
     */
    public static void stopPolling() {
        CloudSession.current().stopPolling();
    }

}
