package com.ultikits.ultitools.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Log stream manager.
 * Responsible for monitoring server logs in real time and transmitting them to UltiPanel via WebSocket.
 *
 * @author UltiKits
 * @version 2.0.0
 */
@ApiStatus.Internal
public class LogStreamManager implements Listener {

    /**
     * The only level names {@link SystemLogHandler} ever recognises -- exactly the vocabulary
     * its own level mapping can produce ("info"/"warning"/"error"/"debug"), so a level a client
     * asks to enable can always actually be reached by a published record's mapped level.
     */
    private static final Set<String> VALID_LOG_LEVELS =
            new HashSet<>(Arrays.asList("info", "warning", "error", "debug"));

    private static LogStreamManager instance;
    private UltiPanelWebSocketClient webSocketClient;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> subscribedClients = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    
    @Getter
    private UltiPanelLogTransmitter logTransmitter;
    private SystemLogHandler systemLogHandler;
    
    private LogStreamManager() {
        // Register the event listener
        try {
            Bukkit.getPluginManager().registerEvents(this, UltiTools.getInstance());
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("注册事件监听器失败: " + e.getMessage());
        }
    }
    
    public static LogStreamManager getInstance() {
        if (instance == null) {
            instance = new LogStreamManager();
        }
        return instance;
    }
    
    /**
     * Initializes the log stream manager.
     */
    public void initialize(UltiPanelWebSocketClient client) {
        // Idempotent: a repeat call must first tear down whatever the previous round left behind.
        //
        // This method is called by onConnectHandler, and onConnectHandler runs on **every**
        // onOpen -- including reconnect() within the reconnect window, and the call after
        // reinitWebSocket builds a fresh client. It used to unconditionally `new` a
        // UltiPanelLogTransmitter (spinning up a transmission thread) and attach a new
        // SystemLogHandler to the JVM root logger, while removeHandler only ever ran inside
        // shutdown(), and shutdown()'s only caller is onDisable. So every connection blip leaked
        // one handler plus one thread, and every log line got sent N times over. See issue #181.
        detachAllSystemLogHandlers();
        if (logTransmitter != null) {
            try {
                logTransmitter.shutdown();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().warning(
                    "[UltiPanel] Error shutting down previous log transmitter: " + e.getMessage());
            }
        }

        this.webSocketClient = client;

        // Initialize the log transmitter
        String serverId = getServerId();
        this.logTransmitter = new UltiPanelLogTransmitter(client, serverId);

        // Gate-2 finding (round 4): externalDrainMode must be re-applied to EVERY freshly-created
        // transmitter, not only once from ServerMonitorManager#startMonitoring(). wireManagers()
        // calls startMonitoring() BEFORE this method on first boot, so the very first transmitter
        // did not exist yet when startMonitoring() tried to enable drain mode on it -- the
        // (defensive, still-present) call there saw a null transmitter and never retried. Every
        // reconnect after the first calls THIS method again to build a fresh transmitter, while
        // startMonitoring() itself early-returns on isMonitoring() already being true, so it would
        // never reach a later transmitter either. Querying monitoring state here, at the one place
        // a transmitter is actually constructed, covers both the first-boot ordering and every
        // reconnect -- without needing to reorder wireManagers() or special-case reconnects.
        ServerMonitorManager serverMonitorManager = UltiTools.getInstance().getServerMonitorManager();
        if (serverMonitorManager != null && serverMonitorManager.isMonitoring()) {
            this.logTransmitter.setExternalDrainMode(true);
            // Gate-2 finding (round 6): under external drain mode, addToBatch()'s own
            // size-threshold trigger is otherwise silently swallowed by sendBatch()'s own
            // early return, so a configured batchSize never shortens delivery latency while
            // monitoring is active. Wire an immediate-drain request back to the monitor.
            this.logTransmitter.setExternalSizeThresholdCallback(serverMonitorManager::drainLogsNow);
        }

        // Load the batch-send settings from the config file
        loadBatchConfiguration();

        // Create and configure the system log handler
        this.systemLogHandler = new SystemLogHandler(logTransmitter);
        this.systemLogHandler.loadConfiguration();

        // Add the system log handler to the root Logger
        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(systemLogHandler);

        // Auto-start the log stream (begin monitoring and sending logs immediately)
        startLogStream("auto", "info");

        UltiTools.getInstance().getLogger().info("[UltiPanel] LogStreamManager initialized and log streaming started");

        // Send the initialization-complete logs
        sendInitializationLogs();
    }

    /**
     * Loads the batch-send configuration from the config file.
     */
    private void loadBatchConfiguration() {
        if (logTransmitter == null) {
            return;
        }

        try {
            // Load the batch-send configuration
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.enabled")) {
                boolean batchEnabled = UltiTools.getInstance().getConfig().getBoolean("ultipanel.logging.batch.enabled", true);
                logTransmitter.setBatchEnabled(batchEnabled);
            }
            
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.size")) {
                int batchSize = UltiTools.getInstance().getConfig().getInt("ultipanel.logging.batch.size", 10);
                logTransmitter.setBatchSize(Math.max(1, batchSize));
            }
            
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.batch.interval")) {
                int interval = UltiTools.getInstance().getConfig().getInt("ultipanel.logging.batch.interval", 5000);
                logTransmitter.setIntervalMs(Math.max(UltiPanelLogTransmitter.MIN_INTERVAL_MS, interval));
            }
            
            UltiTools.getInstance().getLogger().info(String.format(
                "[UltiPanel] 日志传输配置 - 批量发送: %s, 批量大小: %d, 发送间隔: %dms",
                logTransmitter.isBatchEnabled(), logTransmitter.getBatchSize(), logTransmitter.getIntervalMs()));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("[UltiPanel] 加载批量发送配置失败，使用默认配置: " + e.getMessage());
        }
    }
    
    /**
     * Sends the initialization-complete logs.
     */
    private void sendInitializationLogs() {
        // Send the server-startup information
        logTransmitter.info("UltiTools 日志传输系统已启动", "plugin:UltiTools");

        // Send the current online-player-count information
        int onlineCount = Bukkit.getOnlinePlayers().size();
        logTransmitter.info(String.format("当前在线玩家数量: %d", onlineCount), "server");

        // Send the system configuration information
        if (systemLogHandler != null) {
            String configInfo = systemLogHandler.getConfigurationInfo();
            UltiTools.getInstance().getLogger().info(configInfo);
        }
    }

    /**
     * Handles a log stream message.
     */
    public void handleLogStreamMessage(JsonObject data) {
        if (data == null) {
            UltiTools.getInstance().getLogger().warning("LogStreamManager: 收到空的日志流消息");
            return;
        }
        
        String action = data.has("action") && !data.get("action").isJsonNull() 
            ? data.get("action").getAsString() : null;
        String clientId = data.has("clientId") && !data.get("clientId").isJsonNull() 
            ? data.get("clientId").getAsString() : null;
        String level = data.has("level") && !data.get("level").isJsonNull() 
            ? data.get("level").getAsString() : null; // Optional log-level filter
        
        if (clientId == null) {
            clientId = "default";
        }
        
        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 处理日志流操作 - 动作: %s, 客户端: %s, 级别: %s", 
                action, clientId, level));
        
        switch (action != null ? action : "") {
            case "start":
                startLogStream(clientId, level);
                break;
            case "stop":
                stopLogStream(clientId);
                break;
            case "pause":
            case "resume":
                // D-19 (maintainer decision, 2026-09-14): pause/resume are rejected, not
                // implemented. See sendPauseResumeRejection()'s javadoc for the full reasoning.
                sendPauseResumeRejection(clientId, action);
                break;
            case "status":
                sendStreamStatus(clientId);
                break;
            case "config":
                handleConfigUpdate(data, clientId);
                break;
            default:
                UltiTools.getInstance().getLogger().warning(
                    String.format("LogStreamManager: 未知的日志流操作: %s", action));
                sendErrorResponse(clientId, "Unknown log stream action: " + action);
                break;
        }
    }
    
    /**
     * Handles a configuration update.
     * <p>
     * #433: a request naming an unrecognised level rejects the WHOLE request (previously-applied
     * levels are left untouched) rather than partially applying the recognised entries -- a
     * client that made a typo should not silently end up with a different filter than it asked
     * for. An empty {@code levels} array is applied literally: it is a request for zero levels,
     * so zero levels are enabled and nothing is delivered until the client sets levels again --
     * this framework's own front end never actually sends this field today (measured against
     * both {@code UltiPanelFrontend} and {@code ultipanel-api-worker} while planning this fix),
     * so there is no real product behaviour to match, and applying the request literally is the
     * one reading that never has to guess what the caller "really" meant.
     * <p>
     * Gate-2 finding: BOTH the {@code levels} section and the {@code batchConfig} section are
     * parsed and validated FIRST, before either is applied. Before this fix, {@code levels} was
     * applied immediately upon parsing; a request combining valid {@code levels} with an invalid
     * {@code batchConfig} field left the levels change already in effect by the time the
     * {@code batchConfig} validation failed and returned an error for the WHOLE request -- the
     * same "declared rejection, partial effect" defect WR-02 already closed within
     * {@code batchConfig} alone, reoccurring one level up, across the two top-level sections.
     */
    private void handleConfigUpdate(JsonObject data, String clientId) {
        try {
            // ---------- Parse and validate every present section; apply nothing yet ----------
            // Extracted into parseAndValidateLevels/parseAndValidateBatchConfig (Codacy Gate-2
            // finding: this method's own NPath complexity peaked at 117301 against a threshold
            // of 200 once both sections' parsing/validation lived inline here).
            LevelsSection levels = new LevelsSection();
            if (!parseAndValidateLevels(data, clientId, levels)) {
                return; // rejection response already sent
            }
            BatchConfigSection batchConfig = new BatchConfigSection();
            if (!parseAndValidateBatchConfig(data, clientId, batchConfig)) {
                return; // rejection response already sent
            }

            // ---------- Both sections validated -- now apply ----------
            List<String> changes = new ArrayList<>();
            applyLevels(levels, changes);
            applyBatchConfig(batchConfig, changes);

            if (changes.isEmpty()) {
                // #433: a config action naming neither levels nor batchConfig changed nothing --
                // say so, rather than answering the same "success" a real change would get.
                sendStreamResponse(clientId, "config_unchanged", "No configuration changes were requested");
                return;
            }

            sendStreamResponse(clientId, "config_updated", "Configuration updated: " + String.join("; ", changes));

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("[UltiPanel] 更新配置失败: " + e.getMessage());
            sendErrorResponse(clientId, "Failed to update configuration: " + e.getMessage());
        }
    }

    /**
     * Applies an already-validated {@code levels} section (if present) and appends a description
     * to {@code changes} -- extracted from {@link #handleConfigUpdate(JsonObject, String)}
     * alongside {@link #applyBatchConfig(BatchConfigSection, List)} (Codacy Gate-2 NPath
     * complexity finding).
     */
    private void applyLevels(LevelsSection levels, List<String> changes) {
        if (!levels.present) {
            return;
        }
        systemLogHandler.setEnabledLevels(levels.levels);
        changes.add(levels.levels.isEmpty()
                ? "levels updated to an empty set (no log records will be delivered "
                        + "until levels are set again)"
                : "levels updated to " + levels.levels);
    }

    /**
     * Applies an already-validated {@code batchConfig} section (if present) and appends a
     * description to {@code changes} -- extracted from {@link #handleConfigUpdate(JsonObject,
     * String)} alongside {@link #applyLevels(LevelsSection, List)} (Codacy Gate-2 NPath
     * complexity finding).
     */
    private void applyBatchConfig(BatchConfigSection batchConfig, List<String> changes) {
        if (!batchConfig.present) {
            return;
        }
        boolean batchChanged = false;
        if (batchConfig.enabled != null) {
            logTransmitter.setBatchEnabled(batchConfig.enabled);
            batchChanged = true;
        }
        if (batchConfig.size != null) {
            logTransmitter.setBatchSize(batchConfig.size);
            batchChanged = true;
        }
        if (batchConfig.interval != null) {
            logTransmitter.setIntervalMs(batchConfig.interval);
            batchChanged = true;
        }
        if (batchChanged) {
            changes.add("batch settings updated");
            UltiTools.getInstance().getLogger().info("[UltiPanel] 批量发送配置已更新");
        }
    }

    /** Parsed, validated {@code levels} section of a config-update request; {@link #present} is false if absent. */
    private static final class LevelsSection {
        boolean present;
        Set<String> levels;
    }

    /** Parsed, validated {@code batchConfig} section; each field is null when not present in the request. */
    private static final class BatchConfigSection {
        boolean present;
        Boolean enabled;
        Integer size;
        Integer interval;
    }

    /**
     * Parses and validates the {@code levels} array from a config-update request into
     * {@code out}, WITHOUT applying it (extracted from {@link #handleConfigUpdate(JsonObject,
     * String)}, Codacy Gate-2 NPath complexity finding). {@code out.present} stays {@code false}
     * if {@code levels} is absent from the request or {@link #systemLogHandler} is not yet wired
     * -- that is not a rejection.
     * <p>
     * Rejects (sends the error response itself, matching this class's own established
     * "validator sends its own rejection" convention) the WHOLE request if {@code levels} is
     * present but not a JSON array (Gate-2 round 3), or if any entry is not one of
     * {@link #VALID_LOG_LEVELS} (#433) -- previously-applied levels survive either way.
     *
     * @return {@code true} if absent or valid; {@code false} if rejected -- the caller must
     *         return immediately without applying anything from either section
     */
    private boolean parseAndValidateLevels(JsonObject data, String clientId, LevelsSection out) {
        if (!data.has("levels") || systemLogHandler == null) {
            return true;
        }
        JsonElement levelsElement = data.get("levels");
        if (levelsElement == null || !levelsElement.isJsonArray()) {
            // Gate-2 finding (round 3): a `levels` field present but NOT a JSON array (a string,
            // object, or null) used to silently leave levelsPresent false -- the malformed
            // section was dropped rather than rejected, so an accompanying valid batchConfig
            // would still apply and report config_updated, silently ignoring the requested
            // (malformed) level change. Reject the whole request instead, matching #433's own
            // "reject the whole request" precedent for an unrecognised level value.
            sendErrorResponse(clientId, "Failed to update configuration: 'levels' must be "
                    + "a JSON array of level names");
            return false;
        }

        out.present = true;
        JsonArray levelsArray = levelsElement.getAsJsonArray();
        out.levels = new LinkedHashSet<>();
        for (JsonElement levelElement : levelsArray) {
            String rawLevel = (levelElement == null || levelElement.isJsonNull())
                    ? null : levelElement.getAsString();
            String normalizedLevel = rawLevel == null
                    ? null : rawLevel.toLowerCase(Locale.ROOT);
            if (normalizedLevel == null || !VALID_LOG_LEVELS.contains(normalizedLevel)) {
                // Reject the whole request -- nothing (from either section) is applied.
                sendErrorResponse(clientId, "Unrecognized log level: " + rawLevel);
                return false;
            }
            out.levels.add(normalizedLevel);
        }
        return true;
    }

    /**
     * Parses and validates the {@code batchConfig} object from a config-update request into
     * {@code out}, WITHOUT applying it (extracted from {@link #handleConfigUpdate(JsonObject,
     * String)}, Codacy Gate-2 NPath complexity finding). {@code out.present} stays {@code false}
     * if {@code batchConfig} is absent from the request or {@link #logTransmitter} is not yet
     * wired -- that is not a rejection.
     * <p>
     * WR-01/WR-02: every present field is validated before any is applied -- a rejected
     * {@code size} or {@code interval} must not leave the OTHER fields in this same
     * {@code batchConfig} object already applied.
     *
     * @return {@code true} if absent or valid; {@code false} if rejected -- the caller must
     *         return immediately without applying anything from either section
     */
    private boolean parseAndValidateBatchConfig(JsonObject data, String clientId, BatchConfigSection out) {
        if (!data.has("batchConfig") || logTransmitter == null) {
            return true;
        }
        out.present = true;
        JsonObject batchConfig = data.getAsJsonObject("batchConfig");

        return parseAndValidateBatchEnabled(batchConfig, clientId, out)
                && parseAndValidateBatchSize(batchConfig, clientId, out)
                && parseAndValidateBatchInterval(batchConfig, clientId, out);
    }

    /**
     * Parses and validates {@code batchConfig.enabled} into {@code out} -- extracted from
     * {@link #parseAndValidateBatchConfig(JsonObject, String, BatchConfigSection)} alongside its
     * two siblings (Codacy Gate-2 NPath complexity finding, round 7: the combined method peaked
     * at 540 against a threshold of 200 once the round-6 strict-type checks were added inline).
     */
    private boolean parseAndValidateBatchEnabled(JsonObject batchConfig, String clientId, BatchConfigSection out) {
        if (!batchConfig.has("enabled") || batchConfig.get("enabled").isJsonNull()) {
            return true;
        }
        // Gate-2 finding (round 6): Gson's getAsBoolean() on a JSON STRING silently applies
        // Boolean.parseBoolean, which returns false for any non-"true" string (e.g.
        // "disabled" -> false) instead of rejecting a malformed value -- a typo would
        // silently disable batching rather than being refused.
        JsonElement enabledElement = batchConfig.get("enabled");
        if (!enabledElement.isJsonPrimitive() || !enabledElement.getAsJsonPrimitive().isBoolean()) {
            sendErrorResponse(clientId, "Failed to update configuration: "
                    + "'batchConfig.enabled' must be a boolean");
            return false;
        }
        out.enabled = enabledElement.getAsBoolean();
        return true;
    }

    /**
     * Parses and validates {@code batchConfig.size} into {@code out} -- see
     * {@link #parseAndValidateBatchEnabled(JsonObject, String, BatchConfigSection)}'s javadoc for
     * why this is a separate method.
     */
    private boolean parseAndValidateBatchSize(JsonObject batchConfig, String clientId, BatchConfigSection out) {
        if (!batchConfig.has("size") || batchConfig.get("size").isJsonNull()) {
            return true;
        }
        Integer parsedSize = parseStrictInt(batchConfig.get("size"), "batchConfig.size", clientId);
        if (parsedSize == null) {
            return false;
        }
        out.size = parsedSize;
        if (out.size < 1) {
            // Gate-2 finding: a size below 1 makes sendBatch()'s own
            // `for (int i = 0; i < batchSize; ...)` loop consume nothing, so a
            // negative/zero size silently stalls delivery rather than being rejected.
            sendErrorResponse(clientId, "Failed to update configuration: Batch size "
                    + "must be at least 1, got: " + out.size);
            return false;
        }
        return true;
    }

    /**
     * Parses and validates {@code batchConfig.interval} into {@code out} -- see
     * {@link #parseAndValidateBatchEnabled(JsonObject, String, BatchConfigSection)}'s javadoc for
     * why this is a separate method.
     */
    private boolean parseAndValidateBatchInterval(JsonObject batchConfig, String clientId, BatchConfigSection out) {
        if (!batchConfig.has("interval") || batchConfig.get("interval").isJsonNull()) {
            return true;
        }
        Integer parsedInterval = parseStrictInt(batchConfig.get("interval"), "batchConfig.interval", clientId);
        if (parsedInterval == null) {
            return false;
        }
        out.interval = parsedInterval;
        if (out.interval < UltiPanelLogTransmitter.MIN_INTERVAL_MS) {
            sendErrorResponse(clientId, "Failed to update configuration: Batch interval "
                    + "must be at least " + UltiPanelLogTransmitter.MIN_INTERVAL_MS
                    + "ms, got: " + out.interval);
            return false;
        }
        return true;
    }

    /**
     * Parses {@code element} as a strict Java {@code int} -- rejects a non-numeric value, a
     * non-integral number (e.g. {@code 1.9}), and a value outside {@code int} range (Gate-2
     * finding, round 6: Gson's {@code getAsInt()} silently truncates a fractional value and can
     * wrap an out-of-range one, rather than validating that the payload is genuinely an integer).
     * Sends the rejection response itself, matching this class's other validators.
     *
     * @return the parsed value, or {@code null} if invalid (rejection response already sent)
     */
    private Integer parseStrictInt(JsonElement element, String fieldName, String clientId) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            sendErrorResponse(clientId, "Failed to update configuration: '" + fieldName
                    + "' must be an integer");
            return null;
        }
        java.math.BigDecimal value = element.getAsBigDecimal();
        if (value.stripTrailingZeros().scale() > 0
                || value.compareTo(java.math.BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || value.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            sendErrorResponse(clientId, "Failed to update configuration: '" + fieldName
                    + "' must be an integer");
            return null;
        }
        return value.intValueExact();
    }
    
    /**
     * Starts the log stream.
     */
    public void startLogStream(String clientId, String level) {
        subscribedClients.put(clientId, true);
        streaming.set(true);

        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 启动日志流，级别: %s", clientId, level));

        // Send the acknowledgment message
        sendStreamResponse(clientId, "started", "Log stream started successfully");
    }

    /**
     * Starts the log stream (legacy-version compatibility overload).
     */
    public void startLogStream(String clientId) {
        startLogStream(clientId, "info");
    }

    /**
     * Stops the log stream.
     */
    public void stopLogStream(String clientId) {
        subscribedClients.remove(clientId);
        if (subscribedClients.isEmpty()) {
            streaming.set(false);
        }

        UltiTools.getInstance().getLogger().info(
            String.format("LogStreamManager: 为客户端 %s 停止日志流", clientId));

        // Send the acknowledgment message
        sendStreamResponse(clientId, "stopped", "Log stream stopped successfully");
    }

    /**
     * Rejects a {@code pause}/{@code resume} request (D-19, maintainer decision 2026-09-14).
     * <p>
     * This framework has no per-viewer identity to honour a per-viewer pause with: the delivery
     * messages ({@code log_stream}/{@code log_batch}) carry no per-client address at all --
     * {@link UltiPanelLogTransmitter#sendLog} broadcasts once over the single WebSocket
     * connection this server holds to the panel relay, which fans a delivered record out to
     * however many browser viewers are subscribed on that connection, invisibly to this
     * framework. #434's original fix (a global "does any subscriber want delivery" check) could
     * never actually suppress anything in production, because {@link #initialize} permanently
     * subscribes a synthetic {@code "auto"} client that is never paused -- see
     * {@code 16-REVIEW-panel.md} CR-01. Rather than build real per-viewer session identity
     * (cross-repository, not authorised here), the framework declares the action unsupported and
     * says so: pausing the live view is the panel view's own action (stop rendering new lines,
     * optionally buffer them client-side), and the server keeps streaming to every subscribed
     * client regardless. {@code stop}/{@code start} remain the way to unsubscribe/resubscribe.
     * The removed {@code pauseLogStream(String)}/{@code resumeLogStream(String)} public methods
     * are recorded in {@code COMPATIBILITY.md} under the same-release exception.
     */
    private void sendPauseResumeRejection(String clientId, String action) {
        sendErrorResponse(clientId, "The '" + action + "' action is not supported: pausing the "
                + "live log view is the panel view's own action (stop rendering, optionally "
                + "buffer client-side) -- the server has no per-viewer visibility into the panel "
                + "relay's fan-out and keeps streaming to every subscribed client regardless. "
                + "Use 'stop'/'start' to unsubscribe/resubscribe instead.");
    }


    /**
     * Sends a stream response message.
     */
    private void sendStreamResponse(String clientId, String status, String message) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "log_stream_response");
            response.addProperty("serverId", getServerId());
            response.addProperty("timestamp", System.currentTimeMillis());
            
            JsonObject data = new JsonObject();
            data.addProperty("status", status);
            data.addProperty("message", message);
            data.addProperty("clientId", clientId);
            data.addProperty("subscriberCount", subscribedClients.size());
            data.addProperty("streaming", streaming.get());
            
            response.add("data", data);
            webSocketClient.sendMessage(response);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning(
                String.format("LogStreamManager: 发送流响应失败: %s", e.getMessage()));
        }
    }
    
    /**
     * Sends an error response.
     */
    private void sendErrorResponse(String clientId, String error) {
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "log_stream_response");
            response.addProperty("serverId", getServerId());
            response.addProperty("timestamp", System.currentTimeMillis());
            
            JsonObject data = new JsonObject();
            data.addProperty("message", error);
            data.addProperty("clientId", clientId);
            data.addProperty("context", "log_stream");
            
            response.add("data", data);
            webSocketClient.sendMessage(response);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning(
                String.format("LogStreamManager: 发送错误响应失败: %s", e.getMessage()));
        }
    }
    
    /**
     * Sends the stream status.
     */
    private void sendStreamStatus(String clientId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "log_stream_response");
        message.addProperty("timestamp", System.currentTimeMillis());
        message.addProperty("serverId", getServerId());
        
        JsonObject data = new JsonObject();
        data.addProperty("action", "status");
        data.addProperty("streaming", streaming.get());
        data.addProperty("subscriberCount", subscribedClients.size());
        data.addProperty("clientId", clientId);
        data.addProperty("logTransmitterEnabled", logTransmitter != null && logTransmitter.isLogTransmissionEnabled());
        data.addProperty("queueSize", logTransmitter != null ? logTransmitter.getQueueSize() : 0);
        message.add("data", data);
        
        if (webSocketClient != null) {
            webSocketClient.sendMessage(message);
        }
    }
    
    /**
     * Gets the current stream status.
     */
    public boolean isStreaming() {
        return streaming.get();
    }

    /**
     * Gets the number of subscribed clients.
     */
    public int getSubscriberCount() {
        return subscribedClients.size();
    }

    /**
     * Sends a custom log message directly.
     * Used for logging plugin-specific events.
     */
    public void sendCustomLog(String level, String message, String source) {
        if (logTransmitter != null) {
            logTransmitter.sendLog(level, message, source, null);
        }
    }

    /**
     * Sends a player-event log.
     */
    public void sendPlayerEventLog(String eventType, String playerName, String message) {
        sendCustomLog("info", 
            String.format("[玩家事件] %s: %s - %s", eventType, playerName, message),
            "plugin:UltiTools");
    }

    /**
     * Sends a plugin-action log.
     */
    public void sendPluginActionLog(String action, String details) {
        sendCustomLog("info",
            String.format("[插件操作] %s: %s", action, details),
            "plugin:UltiTools");
    }

    // ========== Bukkit event handlers ==========
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        sendPlayerEventLog("JOIN", playerName, "玩家加入服务器");
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        sendPlayerEventLog("QUIT", playerName, "玩家离开服务器");
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        sendCustomLog("info", "服务器加载完成", "server");
    }
    
    /**
     * Gets the server ID.
     */
    private String getServerId() {
        try {
            return com.ultikits.ultitools.utils.CommonUtils.getUltiToolsUUID();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Shuts down the log stream manager.
     */
    public void shutdown() {
        subscribedClients.clear();
        streaming.set(false);

        // Shut down the log transmitter
        if (logTransmitter != null) {
            logTransmitter.shutdown();
        }

        // Remove the handler from Bukkit's Logger
        detachAllSystemLogHandlers();

        UltiTools.getInstance().getLogger().info("[UltiPanel] LogStreamManager shutdown");
    }

    /**
     * Detaches every one of this framework's {@link SystemLogHandler} instances from the JVM
     * root logger.
     * <p>
     * Deliberately scans by type and removes every instance, rather than only removing {@code
     * this.systemLogHandler}: before this method existed, every successful reconnect attached
     * one more to the root logger, the field could only ever hold onto the last one, and the
     * previously-leaked ones had no reference pointing at them any more and so could never be
     * detached. Only a type-based scan can clean up the whole historical backlog at once, and
     * that is also the only formulation under which the acceptance criterion "handler count is
     * always 1" can hold. See issue #181.
     */
    private void detachAllSystemLogHandlers() {
        try {
            Logger rootLogger = Logger.getLogger("");
            for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
                if (handler instanceof SystemLogHandler) {
                    rootLogger.removeHandler(handler);
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("Error removing log handler: " + e.getMessage());
        }
        this.systemLogHandler = null;
    }
}
