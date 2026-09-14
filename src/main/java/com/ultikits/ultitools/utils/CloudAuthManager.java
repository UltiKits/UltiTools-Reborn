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
 * state, and the panel-manager wiring (D-16). The generation-counter coordination this class used
 * to implement directly (a static {@code currentToken}, a static poll/refresh scheduler pair, and
 * an {@code AtomicLong} generation compared by every asynchronous credential operation before it
 * committed a result) is gone outright, not merely deprecated: session identity replaces it, and
 * D-18's structural guard is what stops a parallel static bypass from being re-added unnoticed. See
 * {@link CloudSession}'s own javadoc for the invariant this replaces, quoted from this class's
 * pre-6.3.0 form.
 * <p>
 * This public static surface stays as a delegating facade for this plan (16-08); plan 16-09 removes
 * the fine-grained methods this class no longer needs to expose (D-17), leaving only the three
 * command-facing entry points {@code login}/{@code logout}/{@code status} public.
 */
public class CloudAuthManager {

    private CloudAuthManager() {
    }

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
