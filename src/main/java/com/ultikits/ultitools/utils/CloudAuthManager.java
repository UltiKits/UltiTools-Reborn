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
     * 凭证的生命周期代际。
     * <p>
     * 存在的理由只有一句：<b>取消不等于失效。</b>{@link #stopTokenRefreshScheduler()} 与
     * {@link #stopPolling()} 用的是 {@code cancel(false)} 加 {@code shutdown()}，两者都只
     * 承诺不再调度新的执行，对一个已经进入 HTTP 请求的任务毫无约束——而
     * {@link #refreshToken(String)} 在返回<b>之前</b>就 {@link #saveToken(TokenEntity)} 写盘。
     * 于是这样的时序完全成立：
     * <pre>
     *   1. 刷新任务发出 HTTP 请求（网络往返，秒级）
     *   2. 管理员 /ulticloud logout → 停调度器 → clearToken() 清掉 data.json
     *   3. HTTP 返回 → saveToken() 把新凭证写回 data.json
     *   4. 重启服务器 → 读到有效凭证 → 自动登录
     * </pre>
     * logout 于是成了一条没有效果的命令，而它恰恰是安全语义的。
     * <p>
     * 规则：一切在途的异步凭证操作出发时记下当时的代际，提交结果之前用
     * {@link #commitTokenIfCurrent(TokenEntity, long)} 比对；拆线路径调
     * {@link #invalidateCredentialOperations()} 推进代际，迟到的结果一律作废。
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
        // 出发时记下代际。整个 HTTP 往返期间 logout 都可能发生，而下面的提交必须能看见。
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
                    // 不能直接 saveToken：这次请求可能是在 logout 之前发出、logout 之后才回来的。
                    // 直接写盘会把刚被 clearToken() 清掉的凭证原样填回 data.json，重启后自动重连。
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
    public static synchronized void clearToken() throws IOException {
        currentToken = null;
        Map<String, Object> data = readDataFile();
        data.remove("cloud_token");
        writeDataFile(data);
    }

    /**
     * 取当前的凭证代际。异步凭证操作在<b>出发时</b>调它记下自己那一代。
     *
     * @return 当前代际
     */
    public static long currentCredentialGeneration() {
        return credentialGeneration.get();
    }

    /**
     * 让一切在途的凭证操作作废。
     * <p>
     * 拆线路径（{@code disableCloud()} / {@code /ulticloud logout}）必须调它。只停调度器
     * 是不够的——见 {@link #credentialGeneration} 上的说明。
     */
    public static synchronized void invalidateCredentialOperations() {
        credentialGeneration.incrementAndGet();
    }

    /**
     * 仅当代际未变时才提交凭证。
     * <p>
     * 与 {@link #invalidateCredentialOperations()} 和 {@link #clearToken()} 同步在类锁上，
     * 因此「比对代际」与「写入」之间不存在窗口：拆线要么整个发生在提交之前（这次提交被
     * 拒），要么整个发生在提交之后（拆线把刚写的清掉）。两种都是干净的。
     *
     * @param token 待提交的凭证
     * @param generation 调用方出发时记下的代际
     * @return 已提交返回 true；代际已变、结果被丢弃则返回 false
     * @throws IOException 写入失败
     */
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
        // 本次 magic-link 登录所属的代际。logout 推进代际之后，这一轮的结果一律作废——
        // stopPolling() 的 cancel(false) 拦不住一个已经进了 HTTP 请求的轮询周期。
        final long generation = credentialGeneration.get();

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
                                // logout 可能发生在「本次轮询发出」与「本次轮询拿到 completed」之间。
                                // 直接往下走的话，下面的 enableCloud() + initWebsocket() 会把服务器
                                // 悄悄登回去——而 logout 刚刚才报告过云功能已停止。
                                if (!commitTokenIfCurrent(token, generation)) {
                                    stopPolling();
                                    return;
                                }

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
                                    // 锁外：一次 HTTP 往返，只向面板注册服务器，不改本地状态。
                                    PluginInitiationUtils.loginWithToken(token);
                                    // 锁内：复查代际之后再开状态机、建连、起刷新调度。
                                    // 提交凭证那一步对 logout 原子还不够——真正把服务器连回去的
                                    // 是这一串，logout 挤在两者之间的话照样会被撤销。
                                    if (!PluginInitiationUtils.activateCloudIfCurrent(generation)) {
                                        stopPolling();
                                        return;
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
