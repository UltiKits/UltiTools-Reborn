package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <br>
 * 管理UltiCloud身份验证令牌。
 * 支持魔法链接登录（无需密码）和令牌持久化。
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

    private static TokenEntity currentToken;
    private static ScheduledExecutorService pollExecutor;
    private static ScheduledFuture<?> pollTask;
    private static ScheduledExecutorService refreshExecutor;
    private static ScheduledFuture<?> refreshTask;

    /**
     * Try to load a saved token from data.json on startup.
     * If the access token is expired but a refresh token exists, attempts automatic refresh.
     * Returns the token if valid, null otherwise.
     */
    public static TokenEntity loadSavedToken() {
        try {
            Map<String, Object> data = readDataFile();
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
                    saveToken(newToken);
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
        Map<String, Object> data = readDataFile();

        // Store token fields as a map (Gson will serialize properly)
        Map<String, Object> tokenMap = new LinkedHashMap<>();
        tokenMap.put("access_token", token.getAccess_token());
        tokenMap.put("refresh_token", token.getRefresh_token());
        tokenMap.put("token_type", token.getToken_type());
        tokenMap.put("expires_in", token.getExpires_in());
        tokenMap.put("scope", token.getScope());
        tokenMap.put("jti", token.getJti());

        data.put("cloud_token", tokenMap);
        writeDataFile(data);
    }

    /**
     * Clear the saved token (logout).
     */
    public static void clearToken() throws IOException {
        currentToken = null;
        Map<String, Object> data = readDataFile();
        data.remove("cloud_token");
        writeDataFile(data);
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
     * Start polling for magic-link auth completion.
     *
     * @param requestId the magic link request ID
     * @param onComplete called when auth succeeds (with the token), or null if no callback needed
     */
    public static void startPolling(String requestId, Consumer<TokenEntity> onComplete) {
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

            try {
                String apiUrl = HttpRequestUtils.getBaseUrl().trim();
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                SimpleHttpClient.Response response = SimpleHttpClient.get(
                    apiUrl + "/auth/server-login/status?requestId=" + requestId,
                    headers
                );

                if (response.isOk()) {
                    JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
                    String status = responseBody.has("status") ? responseBody.get("status").getAsString() : "pending";

                    if ("completed".equals(status)) {
                        // Extract token from response
                        String tokenJson = responseBody.has("token") ? GSON.toJson(responseBody.getAsJsonObject("token")) : null;
                        if (tokenJson != null) {
                            TokenEntity token = GSON.fromJson(tokenJson, TokenEntity.class);
                            if (token != null && token.getAccess_token() != null) {
                                token.decodeJwtPayload();
                                saveToken(token);

                                UltiTools.getInstance().getLogger().log(Level.INFO,
                                    "UltiCloud login successful! Welcome, " +
                                    (token.getUser_name() != null ? token.getUser_name() : "user") + "!");

                                // Reset rate limiter on successful login
                                ApiRateLimiter.reset("login");

                                if (onComplete != null) {
                                    onComplete.accept(token);
                                }

                                // Try to initialize cloud features
                                try {
                                    PluginInitiationUtils.loginWithToken(token);
                                    // 显式开启：logout 之后重新 login 必须能把状态机拉回来。
                                    PluginInitiationUtils.enableCloud();
                                    PluginInitiationUtils.initWebsocket();
                                    startTokenRefreshScheduler();
                                } catch (Exception e) {
                                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                                        "Cloud features initialization failed: " + e.getMessage());
                                }
                            }
                        }
                        stopPolling();
                    } else if ("expired".equals(status) || "error".equals(status)) {
                        String error = responseBody.has("message") ? responseBody.get("message").getAsString() : "Unknown error";
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Magic link login failed: " + error);
                        stopPolling();
                    }
                    // "pending" — keep polling
                }
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.FINE, "Magic link poll failed: " + e.getMessage());
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readDataFile() throws IOException {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        if (dataFile.exists()) {
            try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
                Map<String, Object> json = GSON.fromJson(reader, Map.class);
                return json != null ? json : new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    private static void writeDataFile(Map<String, Object> data) throws IOException {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        if (!dataFile.getParentFile().exists()) {
            dataFile.getParentFile().mkdirs();
        }
        try (Writer writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        }
    }
}
