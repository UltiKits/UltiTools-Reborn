package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Manages UltiCloud authentication tokens.
 * Supports magic-link login (no password needed) and token persistence.
 */
public class CloudAuthManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 100; // 5 minutes at 3s intervals
    /** How often to check if the access token needs refreshing (1 hour) */
    private static final long TOKEN_REFRESH_CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    /** Refresh the token when it has less than this many seconds remaining (2 hours) */
    private static final long TOKEN_REFRESH_THRESHOLD_SECONDS = 2 * 60 * 60L;
    /** Basic auth header for OAuth2 client credentials (client:112233) */
    private static final String OAUTH2_BASIC_AUTH = "Basic Y2xpZW50OjExMjIzMw==";

    /**
     * Written by the refresh executor thread, read by any caller of {@link #getCurrentToken()} or
     * {@link #hasValidToken()} without holding a lock -- {@code volatile} is required so a value
     * published there is visible to a subsequent read on another thread (D-12's visibility hole).
     */
    private static volatile TokenEntity currentToken;
    private static ScheduledExecutorService pollExecutor;
    private static ScheduledFuture<?> pollTask;
    private static ScheduledExecutorService refreshExecutor;
    private static ScheduledFuture<?> refreshTask;

    /**
     * The credential lifecycle generation.
     * <p>
     * The reason this exists fits in one sentence: <b>cancellation is not invalidation.</b>
     * {@link #stopTokenRefreshScheduler()} and {@link #stopPolling()} both use {@code cancel(false)}
     * plus {@code shutdown()}, and both only promise not to schedule a new execution -- neither
     * constrains a task that has already entered an HTTP request. Meanwhile
     * {@link #refreshToken(String)} calls {@link #saveToken(TokenEntity)} to write to disk
     * <b>before</b> it returns. So the following timing is entirely possible:
     * <pre>
     *   1. A refresh task issues an HTTP request (network round trip, seconds).
     *   2. An admin runs /ulticloud logout -&gt; the scheduler stops -&gt; clearToken() wipes data.json.
     *   3. The HTTP response arrives -&gt; saveToken() writes the new credential back to data.json.
     *   4. The server restarts -&gt; it reads a valid credential -&gt; it logs in automatically.
     * </pre>
     * logout thereby becomes a command with no effect, which is exactly the security property it
     * exists to provide.
     * <p>
     * Rule: every in-flight asynchronous credential operation records the generation it saw when it
     * started, and compares against it via {@link #commitTokenIfCurrent(TokenEntity, long)} before
     * committing its result; the teardown path calls {@link #invalidateCredentialOperations()} to
     * advance the generation, so any late-arriving result is discarded unconditionally.
     */
    private static final java.util.concurrent.atomic.AtomicLong credentialGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Try to load a saved token from data.json on startup.
     * If the access token is expired but a refresh token exists, attempts automatic refresh.
     * Returns the token if valid, null otherwise.
     */
    public static TokenEntity loadSavedToken() {
        try {
            CredentialStore.ReadResult result = CredentialStore.read();
            if (result.isAbsent()) {
                return null;
            }
            if (result.isParseFailure()) {
                // Distinguishable from absence: a torn/corrupt credential file must not be
                // silently treated as "no saved token" -- that would swallow the failure instead
                // of reporting it (D-12, T-08-53).
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Saved credential file exists but could not be parsed as valid JSON; "
                        + "treating it as no saved token rather than deleting it. "
                        + "Use /ulticloud login to re-authenticate.");
                return null;
            }
            Map<String, Object> data = result.data();
            Object savedToken = data.get("cloud_token");
            if (savedToken == null) {
                return null;
            }

            // Gson deserializes nested maps as LinkedTreeMap, so re-serialize and parse
            String tokenJson = GSON.toJson(savedToken);
            TokenEntity token = GSON.fromJson(tokenJson, TokenEntity.class);

            if (token == null || token.getAccess_token() == null || token.getAccess_token().isEmpty()) {
                return null;
            }

            token.decodeJwtPayload();

            if (token.isExpired()) {
                // Access token expired — try refreshing with the refresh token
                if (token.getRefresh_token() != null && !token.getRefresh_token().isEmpty()) {
                    UltiTools.getInstance().getLogger().log(Level.INFO,
                        "Saved cloud token has expired, attempting automatic refresh...");
                    TokenEntity refreshed = refreshToken(token.getRefresh_token());
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

            currentToken = token;
            return token;
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Failed to load saved cloud token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Refresh the access token using the refresh token.
     * Calls POST /oauth/token with grant_type=refresh_token.
     *
     * @param refreshTokenValue the refresh token string
     * @return a new TokenEntity with fresh access and refresh tokens, or null on failure
     */
    public static TokenEntity refreshToken(String refreshTokenValue) {
        // Record the generation when this starts. logout can happen at any point during the
        // HTTP round trip, and the commit below must be able to see it.
        final long generation = credentialGeneration.get();
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
                    // Cannot call saveToken directly: this request may have been sent before logout
                    // and only returned after it. Writing directly would put the credential
                    // clearToken() just wiped straight back into data.json, auto-reconnecting on restart.
                    if (!commitTokenIfCurrent(newToken, generation)) {
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

    /**
     * Save the current token to data.json for persistence across restarts.
     */
    public static void saveToken(TokenEntity token) throws IOException {
        currentToken = token;

        // Store token fields as a map (Gson will serialize properly)
        Map<String, Object> tokenMap = new LinkedHashMap<>();
        tokenMap.put("access_token", token.getAccess_token());
        tokenMap.put("refresh_token", token.getRefresh_token());
        tokenMap.put("token_type", token.getToken_type());
        tokenMap.put("expires_in", token.getExpires_in());
        tokenMap.put("scope", token.getScope());
        tokenMap.put("jti", token.getJti());

        CredentialStore.update(existing -> {
            existing.put("cloud_token", tokenMap);
            return existing;
        });
    }

    /**
     * Clear the saved token (logout).
     */
    public static synchronized void clearToken() throws IOException {
        // Clearing the credential already means "everything before this is invalid". Advancing the
        // generation a second time here is a second gate: the advance done at the start of teardown
        // may still leave a gap against a producer that started mid-teardown, while this one happens
        // after every producer has stopped, so any result still in flight is already stale by now.
        credentialGeneration.incrementAndGet();
        currentToken = null;
        CredentialStore.update(existing -> {
            existing.remove("cloud_token");
            return existing;
        });
    }

    /**
     * Get the current credential generation. Asynchronous credential operations call this
     * <b>when they start</b> to record their own generation.
     *
     * @return the current generation
     * @deprecated This is an internal coordination primitive for {@code CloudAuthManager}'s own
     * asynchronous credential producers, not a supported external API -- measured 0 downstream
     * references across every published module JAR and every local module/plugin source. The
     * cancel-is-not-invalidate guard this method reads from is preserved unchanged; the credential
     * file I/O this class used to imply now lives in {@link CredentialStore}. Scheduled for
     * removal once issue #298's session-based credential lifecycle redesign replaces the whole
     * generation-counter pattern.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static long currentCredentialGeneration() {
        return credentialGeneration.get();
    }

    /**
     * Invalidate every credential operation currently in flight.
     * <p>
     * The teardown path ({@code disableCloud()} / {@code /ulticloud logout}) must call this.
     * Stopping the scheduler alone is not enough -- see the note on {@link #credentialGeneration}.
     *
     * @deprecated This is an internal coordination primitive for {@code CloudAuthManager}'s own
     * teardown path, not a supported external API -- measured 0 downstream references across
     * every published module JAR and every local module/plugin source. The guard's behaviour is
     * preserved unchanged, including its {@code synchronized} coordination with
     * {@link #commitTokenIfCurrent(TokenEntity, long)} and {@link #clearToken()}; the credential
     * file I/O this class used to imply now lives in {@link CredentialStore}. Scheduled for
     * removal once issue #298's session-based credential lifecycle redesign replaces the whole
     * generation-counter pattern.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static synchronized void invalidateCredentialOperations() {
        credentialGeneration.incrementAndGet();
    }

    /**
     * Commit the credential only if the generation has not changed.
     * <p>
     * Synchronized on the class lock together with {@link #invalidateCredentialOperations()} and
     * {@link #clearToken()}, so there is no window between "compare the generation" and "write":
     * teardown either happens entirely before this commit (in which case the commit is rejected)
     * or entirely after it (in which case teardown clears what was just written). Both outcomes
     * are clean.
     *
     * @param token the credential to commit
     * @param generation the generation the caller recorded when it started
     * @return true if committed; false if the generation had changed and the result was discarded
     * @throws IOException if the write fails
     * @deprecated This is an internal coordination primitive for {@code CloudAuthManager}'s own
     * asynchronous credential producers, not a supported external API -- measured 0 downstream
     * references across every published module JAR and every local module/plugin source. The
     * generation-comparison-then-write guard is preserved unchanged, including its
     * {@code synchronized} coordination with {@link #invalidateCredentialOperations()} and
     * {@link #clearToken()}; the write itself now goes through {@link CredentialStore} for an
     * atomic replace. Scheduled for removal once issue #298's session-based credential lifecycle
     * redesign replaces the whole generation-counter pattern.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static synchronized boolean commitTokenIfCurrent(TokenEntity token, long generation)
            throws IOException {
        if (generation != credentialGeneration.get()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Discarding a credential result that arrived after logout (generation changed)");
            return false;
        }
        saveToken(token);
        return true;
    }

    /**
     * Get the current token (in-memory).
     */
    public static TokenEntity getCurrentToken() {
        return currentToken;
    }

    /**
     * Check if we have a valid (non-expired) token.
     */
    public static boolean hasValidToken() {
        return currentToken != null
            && currentToken.getAccess_token() != null
            && !currentToken.isExpired();
    }

    /**
     * Request a magic link for server authentication.
     * Returns the URL the admin should open in their browser, or null on failure.
     *
     * @param errorCallback called with error message if the request fails
     * @return the magic link URL, or null on failure
     */
    public static String requestMagicLink(Consumer<String> errorCallback) {
        // The generation must be captured **here**, not deferred to startPolling(). The POST below
        // is blocking, and logout can happen entirely during that round trip; reading the generation
        // only after the POST returns would read the already-incremented one, making this login
        // "look new" so its eventual token gets accepted and activateCloudIfCurrent() reconnects the
        // server -- even though this logout never saw this login at all.
        final long generation = credentialGeneration.get();
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
                    // Store requestId for polling
                    startPolling(requestId, null, generation);
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
     * Start polling for magic-link auth completion.
     *
     * @param requestId the magic link request ID
     * @param onComplete called when auth succeeds (with the token), or null if no callback needed
     */
    public static void startPolling(String requestId, Consumer<TokenEntity> onComplete) {
        startPolling(requestId, onComplete, credentialGeneration.get());
    }

    /**
     * The polling entry point with an explicit generation.
     * <p>
     * The generation is decided at <b>the moment the whole login started</b> and must not be read
     * fresh here: by the time the caller reaches this point it has usually already made a blocking
     * HTTP request, and a logout that happened during that time must be visible to this login.
     *
     * @param requestId the magic-link request ID
     * @param onComplete called when login completes, or null if no callback is needed
     * @param generation the credential generation captured when this login started
     */
    public static void startPolling(String requestId, Consumer<TokenEntity> onComplete,
                                    final long generation) {
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
            pollLoginStatusOnce(requestId, onComplete, generation);
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Check login status once. Any exception is logged at FINE only -- polling must continue until it times out or reaches a terminal state. */
    private static void pollLoginStatusOnce(String requestId, Consumer<TokenEntity> onComplete,
                                            long generation) {
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
                completeMagicLinkLogin(responseBody, onComplete, generation);
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
     * Handle a login that just reached {@code completed}: persist the credential, then reactivate
     * cloud features.
     * <p>
     * Both steps must guard against logout, and they are two distinct gates:
     * {@link #commitTokenIfCurrent(TokenEntity, long)} guarantees the credential is not written back
     * after logout; {@code activateCloudIfCurrent} guarantees the connection is not rebuilt after
     * logout. The first alone is not enough -- it is the sequence after it that actually reconnects
     * the server.
     *
     * @throws IOException if persisting the credential fails
     */
    private static void completeMagicLinkLogin(JsonObject responseBody,
                                               Consumer<TokenEntity> onComplete,
                                               long generation) throws IOException {
        String tokenJson = responseBody.has("token") ? GSON.toJson(responseBody.getAsJsonObject("token")) : null;
        if (tokenJson == null) {
            return;
        }
        TokenEntity token = GSON.fromJson(tokenJson, TokenEntity.class);
        if (token == null || token.getAccess_token() == null) {
            return;
        }
        token.decodeJwtPayload();

        // logout can happen between "this login started" and "this poll reached completed".
        if (!commitTokenIfCurrent(token, generation)) {
            return;
        }

        UltiTools.getInstance().getLogger().log(Level.INFO,
            "UltiCloud login successful! Welcome, "
                + (token.getUser_name() != null ? token.getUser_name() : "user") + "!");

        // Reset rate limiter on successful login
        ApiRateLimiter.reset("login");

        if (onComplete != null) {
            onComplete.accept(token);
        }

        try {
            // Outside the lock: one HTTP round trip that only registers the server with the panel
            // and does not change any local state.
            PluginInitiationUtils.loginWithToken(token);
            // Inside the lock: re-check the generation before opening the state machine, building
            // the connection, and starting the refresh schedule.
            PluginInitiationUtils.activateCloudIfCurrent(generation);
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "Cloud features initialization failed: " + e.getMessage());
        }
    }

    /**
     * Start a background scheduler that proactively refreshes the access token
     * before it expires (checks every hour, refreshes when &lt;2 hours remaining).
     */
    public static void startTokenRefreshScheduler() {
        stopTokenRefreshScheduler();
        refreshExecutor = Executors.newSingleThreadScheduledExecutor();
        refreshTask = refreshExecutor.scheduleWithFixedDelay(() -> {
            try {
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
                        TokenEntity refreshed = refreshToken(refreshTokenValue);
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

    /**
     * Stop the background token refresh scheduler.
     */
    public static void stopTokenRefreshScheduler() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        if (refreshExecutor != null) {
            refreshExecutor.shutdown();
            refreshExecutor = null;
        }
    }

    /**
     * Stop polling for magic-link completion.
     */
    public static void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (pollExecutor != null) {
            pollExecutor.shutdown();
            pollExecutor = null;
        }
    }

}
