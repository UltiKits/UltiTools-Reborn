package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;

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
 *
 * @since 6.3.0
 */
final class CloudSession {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 100; // 5 minutes at 3s intervals
    /** How often to check if the access token needs refreshing (1 hour) */
    private static final long TOKEN_REFRESH_CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    /** Refresh the token when it has less than this many seconds remaining (2 hours) */
    private static final long TOKEN_REFRESH_THRESHOLD_SECONDS = 2 * 60 * 60L;
    /** Basic auth header for OAuth2 client credentials (client:112233) */
    private static final String OAUTH2_BASIC_AUTH = "Basic Y2xpZW50OjExMjIzMw==";

    /** The current session. Never {@code null} -- initialised eagerly so every static facade
     * method on {@link CloudAuthManager} always has something to delegate to, even before any
     * login has ever happened. */
    private static volatile CloudSession current = new CloudSession();

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
        current = new CloudSession();
    }

    private final TokenStore tokenStore = new TokenStore();

    private volatile TokenEntity token;
    private volatile boolean invalidated;

    private ScheduledExecutorService pollExecutor;
    private ScheduledFuture<?> pollTask;
    private ScheduledExecutorService refreshExecutor;
    private ScheduledFuture<?> refreshTask;

    /** Package-private -- constructed only by {@link #startNew()} and by tests in this package. */
    CloudSession() {
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
     * Marks this session invalid and tears down everything it owns: the poll and refresh
     * schedulers. Safe to call on a session that never started either (a freshly constructed
     * session with nothing scheduled yet) and safe to call more than once -- both are exercised
     * directly by this class's own tests, independently of the static {@link #current()} holder.
     */
    synchronized void invalidate() {
        invalidated = true;
        stopPolling();
        stopTokenRefreshScheduler();
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
     * Removes the persisted credential from disk and clears this session's in-memory token.
     * Called by {@code CloudAuthManager.clearToken()} on the freshly-installed session
     * {@link #startNew()} returns, so the wipe lands on the session that is actually current going
     * forward.
     *
     * @throws IOException if the underlying write fails
     */
    void clearPersisted() throws IOException {
        tokenStore.clear();
        this.token = null;
    }

    /** @return {@code true} if this session holds a non-expired token with an access token */
    boolean hasValidToken() {
        TokenEntity current = this.token;
        return current != null && current.getAccess_token() != null && !current.isExpired();
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
                    "Saved credential file exists but could not be parsed as valid JSON; "
                        + "treating it as no saved token rather than deleting it. "
                        + "Use /ulticloud login to re-authenticate.");
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

    // ---- Magic-link login flow ----

    /**
     * Requests a magic link for server authentication and, on success, starts polling for its
     * completion on this session's own executor.
     *
     * @param errorCallback called with an error message if the request fails
     * @return the magic link URL, or {@code null} on failure
     */
    String requestMagicLink(Consumer<String> errorCallback) {
        String apiUrl = HttpRequestUtils.getBaseUrl();
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            errorCallback.accept("API URL not configured");
            return null;
        }
        apiUrl = apiUrl.trim();

        String serverUuid;
        try {
            serverUuid = CommonUtils.getUltiToolsUUID();
        } catch (IOException e) {
            errorCallback.accept("Failed to get server UUID: " + e.getMessage());
            return null;
        }

        String requestId = UUID.randomUUID().toString();

        JsonObject body = new JsonObject();
        body.addProperty("requestId", requestId);
        body.addProperty("serverUuid", serverUuid);
        body.addProperty("serverName", org.bukkit.Bukkit.getServer().getName());

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            SimpleHttpClient.Response response = SimpleHttpClient.post(
                apiUrl + "/auth/server-login",
                headers,
                GSON.toJson(body)
            );

            if (response.isOk()) {
                JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
                String url = responseBody.has("url") ? responseBody.get("url").getAsString() : null;
                if (url != null) {
                    startPolling(requestId, null);
                    return url;
                }
                errorCallback.accept("Invalid response from API (missing url)");
                return null;
            } else {
                errorCallback.accept("API returned HTTP " + response.getStatus() + ": " + response.body());
                return null;
            }
        } catch (Exception e) {
            errorCallback.accept("Request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Starts polling for magic-link auth completion on this session's own executor. Stopping this
     * session's polling (via {@link #invalidate()}, or a direct {@link #stopPolling()}) is enough
     * to make every subsequent tick a no-op -- no separate credential-generation comparison is
     * needed, because {@link #pollLoginStatusOnce} below runs as a method on {@code this} instance.
     *
     * @param requestId  the magic link request ID
     * @param onComplete called when auth succeeds (with the token), or {@code null}
     */
    synchronized void startPolling(String requestId, Consumer<TokenEntity> onComplete) {
        stopPolling();

        pollExecutor = Executors.newSingleThreadScheduledExecutor();
        final int[] attempts = {0};

        pollTask = pollExecutor.scheduleWithFixedDelay(() -> {
            attempts[0]++;
            if (attempts[0] > MAX_POLL_ATTEMPTS) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Magic link login timed out (5 minutes)");
                stopPolling();
                return;
            }
            pollLoginStatusOnce(requestId, onComplete);
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Check login status once. Any exception is logged at FINE only -- polling must continue until it times out or reaches a terminal state. */
    private void pollLoginStatusOnce(String requestId, Consumer<TokenEntity> onComplete) {
        try {
            String apiUrl = HttpRequestUtils.getBaseUrl().trim();
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            SimpleHttpClient.Response response = SimpleHttpClient.get(
                apiUrl + "/auth/server-login/status?requestId=" + requestId,
                headers
            );
            if (!response.isOk()) {
                return;
            }

            JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
            String status = responseBody.has("status") ? responseBody.get("status").getAsString() : "pending";

            if ("completed".equals(status)) {
                completeMagicLinkLogin(responseBody, onComplete);
                stopPolling();
            } else if ("expired".equals(status) || "error".equals(status)) {
                String error = responseBody.has("message") ? responseBody.get("message").getAsString() : "Unknown error";
                UltiTools.getInstance().getLogger().log(Level.WARNING, "Magic link login failed: " + error);
                stopPolling();
            }
            // "pending" — keep polling
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE, "Magic link poll failed: " + e.getMessage());
        }
    }

    /**
     * Handles a login that just reached {@code completed}: persists the credential through this
     * session, then reactivates cloud features -- both steps guard against a logout landing in the
     * middle by checking this same {@code this} session, so the two can never disagree about
     * whether the login is still current.
     *
     * @throws IOException if persisting the credential fails
     */
    private void completeMagicLinkLogin(JsonObject responseBody, Consumer<TokenEntity> onComplete) throws IOException {
        String tokenJson = responseBody.has("token") ? GSON.toJson(responseBody.getAsJsonObject("token")) : null;
        if (tokenJson == null) {
            return;
        }
        TokenEntity newToken = GSON.fromJson(tokenJson, TokenEntity.class);
        if (newToken == null || newToken.getAccess_token() == null) {
            return;
        }
        newToken.decodeJwtPayload();

        if (!commit(newToken)) {
            return;
        }

        UltiTools.getInstance().getLogger().log(Level.INFO,
            "UltiCloud login successful! Welcome, "
                + (newToken.getUser_name() != null ? newToken.getUser_name() : "user") + "!");

        ApiRateLimiter.reset("login");

        if (onComplete != null) {
            onComplete.accept(newToken);
        }

        try {
            PluginInitiationUtils.loginWithToken(newToken);
            PluginInitiationUtils.activateCloudIfCurrent(this);
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "Cloud features initialization failed: " + e.getMessage());
        }
    }

    /** Stop polling for magic-link completion on this session. */
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
}
