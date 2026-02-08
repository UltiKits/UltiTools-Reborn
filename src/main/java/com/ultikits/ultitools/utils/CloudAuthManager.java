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

    private static TokenEntity currentToken;
    private static ScheduledExecutorService pollExecutor;
    private static ScheduledFuture<?> pollTask;

    /**
     * Try to load a saved token from data.json on startup.
     * Returns the token if valid and not expired, null otherwise.
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
                UltiTools.getInstance().getLogger().log(Level.INFO, "Saved cloud token has expired, will need re-login");
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
                                    if (UltiTools.getInstance().getConfig().getBoolean("web-editor.enable")) {
                                        PluginInitiationUtils.initWebsocket();
                                    }
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
