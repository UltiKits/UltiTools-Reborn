package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server monitor manager.
 * Responsible for collecting server status information and sending it via WebSocket.
 */
@ApiStatus.Internal
public class ServerMonitorManager {
    private UltiPanelWebSocketClient webSocketClient;
    /**
     * Send thread pool. <b>Must not be final</b>: {@link #stopMonitoring()} shuts it down, and
     * once a {@code ScheduledExecutorService} is shut down it is permanently dead. Logging out
     * and back in is a perfectly normal path, and at that point {@link #startMonitoring()} must
     * obtain a usable pool -- otherwise {@code scheduleAtFixedRate} throws {@code
     * RejectedExecutionException} directly.
     */
    private ScheduledExecutorService scheduler;
    private boolean isMonitoring = false;
    private int tickCount = 0;

    // TPS calculation related
    private long lastTick = System.currentTimeMillis();
    private final long[] tpsHistory1m = new long[60];   // 1-minute TPS history
    private final long[] tpsHistory5m = new long[300];  // 5-minute TPS history
    private final long[] tpsHistory15m = new long[900]; // 15-minute TPS history
    private int historyIndex = 0;

    // CPU sampling (sampled periodically on the Bukkit main thread, read by the batch_update thread)
    private volatile double lastCpuUsage = 0.0;

    /** Sampling period for world/player/plugin status, in ticks. 100 ticks = 5 seconds, matching batch_update's send cadence. */
    private static final long SNAPSHOT_INTERVAL_TICKS = 100L;

    /**
     * A server-state snapshot sampled on the main thread; the async send thread only ever reads
     * it.
     * <p>
     * <b>This is the entire point of issue #179.</b> Before this field existed, {@code
     * sendBatchUpdate} ran on a plain {@code ScheduledThreadPool} but called {@code
     * Bukkit.getWorlds()}, {@code world.getLoadedChunks()}, {@code Bukkit.getOnlinePlayers()},
     * {@code player.getLocation()} directly from there -- all mutable world state Paper
     * explicitly does not support touching from an async thread, surfacing as intermittent
     * concurrent-modification exceptions or torn reads.
     * <p>
     * TPS/CPU sampling in this same class <b>already</b> correctly hopped over to {@code
     * runTaskTimer}, showing the contract was recognized at the time -- just only half applied.
     * This field fills in the other half, following the exact same pattern.
     * <p>
     * The cost is that data can be stale by up to one sampling period (5 seconds). This is a
     * deliberate choice: the alternative would have the async thread synchronously wait on the
     * main thread via {@code callSyncMethod().get()}, which would make monitoring's liveness
     * depend on the main thread's health -- precisely when the server is lagging is exactly when
     * monitoring most needs to still be able to speak.
     */
    private volatile ServerStateSnapshot stateSnapshot = ServerStateSnapshot.EMPTY;

    /** The two main-thread scheduled tasks; {@link #stopMonitoring()} needs to be able to cancel them. */
    private BukkitTask tpsTask;
    private BukkitTask snapshotTask;

    public ServerMonitorManager() {
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * The result of one main-thread sample. Every field is final and never changed after
     * construction, published via a volatile field, so a reading thread sees either the
     * previous complete snapshot or this complete snapshot, never a half-built one.
     * <p>
     * The three {@link JsonArray} fields <b>must never be mutated again after being
     * published</b> -- they are pushed directly into the outgoing message and serialization is
     * read-only, so sharing the instance is safe; the moment anyone calls {@code add} on one
     * after publication, that precondition no longer holds.
     */
    private static final class ServerStateSnapshot {
        static final ServerStateSnapshot EMPTY = new ServerStateSnapshot(
                0, 0, false, "Unknown", 0, 0, new JsonArray(), new JsonArray(), new JsonArray());

        final int playerCount;
        final int maxPlayers;
        final boolean onlineMode;
        final String serverVersion;
        final int worldCount;
        final int pluginCount;
        final JsonArray worlds;
        final JsonArray onlinePlayers;
        final JsonArray plugins;

        ServerStateSnapshot(int playerCount, int maxPlayers, boolean onlineMode, String serverVersion,
                            int worldCount, int pluginCount,
                            JsonArray worlds, JsonArray onlinePlayers, JsonArray plugins) {
            this.playerCount = playerCount;
            this.maxPlayers = maxPlayers;
            this.onlineMode = onlineMode;
            this.serverVersion = serverVersion;
            this.worldCount = worldCount;
            this.pluginCount = pluginCount;
            this.worlds = worlds;
            this.onlinePlayers = onlinePlayers;
            this.plugins = plugins;
        }
    }
    
    /**
     * Set the WebSocket client.
     * @param client the WebSocket client
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    /**
     * Start monitoring server status.
     */
    public void startMonitoring() {
        if (isMonitoring) {
            return;
        }

        isMonitoring = true;
        // If the previous stopMonitoring() shut the pool down, swap in a fresh one -- see the
        // note on the field.
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(2);
        }
        UltiTools.getInstance().getLogger().log(Level.INFO, "启动服务器状态监控");

        // Send the initial status as soon as the WebSocket connection is established
        Bukkit.getScheduler().runTaskLater(UltiTools.getInstance(), () -> {
            if (webSocketClient != null && webSocketClient.isConnected()) {
                sendBatchUpdate();
            }
        }, 20L); // Wait 1 second

        // Enable the log transmitter's external drain mode (logs will be sent uniformly via batch_update)
        LogStreamManager lsm = UltiTools.getInstance().getLogStreamManager();
        if (lsm != null && lsm.getLogTransmitter() != null) {
            lsm.getLogTransmitter().setExternalDrainMode(true);
        }

        // Send a batch_update every 5 seconds (includes status and metrics; includes plugins
        // every 12th tick; includes logs every time).
        // Note: this thread **only sends** -- every piece of Bukkit state comes from the
        // main-thread-sampled snapshot. See issue #179.
        scheduler.scheduleAtFixedRate(this::sendBatchUpdate, 5, 5, TimeUnit.SECONDS);

        // Start the TPS calculation + CPU sampling task (every second)
        tpsTask = Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::updateTpsAndCpu, 0L, 20L);

        // World/player/plugin status sampling task (every 5 seconds, main thread).
        // Deliberately not folded into the 1Hz task above: world.getLoadedChunks() allocates an
        // array holding every loaded chunk, which is not cheap on a large server -- sampling it
        // at 1Hz would inflate that cost by a factor of 5 for no reason. 100 ticks matches
        // today's actual sampling frequency, just moved to the correct thread.
        snapshotTask = Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::refreshStateSnapshot,
                0L, SNAPSHOT_INTERVAL_TICKS);
    }

    /**
     * Stop monitoring.
     * <p>
     * Besides shutting down the send thread, the two main-thread scheduled tasks must also be
     * cancelled. This used to be just {@code scheduler.shutdown()}, which was enough
     * <b>before</b> sampling moved to the main thread -- the cost of iterating every world lived
     * on the send thread, so shutting down sending removed it too. After the move, not
     * cancelling turns "stop monitoring" into "stop sending, but still iterate every world and
     * chunk every 5 seconds" -- and now on the main thread. See the PR #265 review.
     */
    public void stopMonitoring() {
        if (!isMonitoring) {
            return;
        }

        isMonitoring = false;
        cancelTask(snapshotTask);
        snapshotTask = null;
        cancelTask(tpsTask);
        tpsTask = null;
        scheduler.shutdown();
        UltiTools.getInstance().getLogger().log(Level.INFO, "停止服务器状态监控");
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception e) {
                // Hand the exception itself to the logger instead of concatenating getMessage():
                // this preserves the stack trace and avoids unconditional string concatenation at
                // the log call site (both PMD's PreserveStackTrace and GuardLogStatement watch for this).
                UltiTools.getInstance().getLogger().log(Level.FINE, "取消监控任务时出错", e);
            }
        }
    }

    /**
     * Send server status information.
     */
    public void sendServerStatus() {
        try {
            if (webSocketClient == null || !webSocketClient.isConnected()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket未连接，无法发送服务器状态");
                return;
            }

            JsonObject message = new JsonObject();
            message.addProperty("type", "server_status");
            message.addProperty("serverId", webSocketClient.getServerId());
            message.addProperty("timestamp", System.currentTimeMillis());

            JsonObject data = getCurrentServerStatusData();
            message.add("data", data);

            // Send the message
            webSocketClient.sendMessage(message);

            // The player count in the log line also comes from the snapshot -- this line also
            // runs on the async thread, so reading Bukkit directly here would be the same defect.
            ServerStateSnapshot snapshot = currentSnapshot();
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("已发送服务器状态: 玩家 %d/%d, TPS %.1f, 内存 %dMB/%dMB",
                    snapshot.playerCount, snapshot.maxPlayers,
                    calculateTPS()[0],
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024,
                    Runtime.getRuntime().maxMemory() / 1024 / 1024));

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送服务器状态失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Extract the version number.
     */
    private String extractVersionNumber(String fullVersion) {
        try {
            // Try to extract the numeric version from the version string
            // e.g.: "git-Bukkit-abc123 (MC: 1.20.1)" -> "1.20.1"
            if (fullVersion.contains("MC: ")) {
                int start = fullVersion.indexOf("MC: ") + 4;
                int end = fullVersion.indexOf(")", start);
                if (end > start) {
                    return fullVersion.substring(start, end);
                }
            }

            // If it cannot be extracted, return the Bukkit version
            String bukkitVersion = Bukkit.getBukkitVersion();
            if (bukkitVersion.contains("-")) {
                return bukkitVersion.split("-")[0];
            }

            return bukkitVersion;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Get the CPU usage (returns the cached value, updated by periodic sampling).
     */
    private double getCPUUsage() {
        return lastCpuUsage;
    }

    /**
     * Sample CPU usage and update the cached value.
     */
    private void sampleCpuUsage() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunOsBean.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    lastCpuUsage = cpuLoad * 100;
                    return;
                }
            }

            // Fallback: use the system load average
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad >= 0) {
                int processors = osBean.getAvailableProcessors();
                lastCpuUsage = Math.min((systemLoad / processors) * 100, 100);
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE, "无法获取CPU使用率: " + e.getMessage());
        }
    }

    /**
     * Send server status information with a request ID (in response to a backend request).
     */
    public void sendServerStatusWithRequestId(String requestId) {
        try {
            if (webSocketClient == null || !webSocketClient.isConnected()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING, "WebSocket未连接，无法发送服务器状态");
                return;
            }

            JsonObject message = new JsonObject();
            message.addProperty("type", "server_status");
            message.addProperty("serverId", webSocketClient.getServerId());
            message.addProperty("timestamp", System.currentTimeMillis());
            message.addProperty("requestId", requestId); // Includes the request ID

            JsonObject data = getCurrentServerStatusData();
            message.add("data", data);

            // Send the message
            webSocketClient.sendMessage(message);

            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("已响应服务器状态请求，请求ID: %s", requestId));

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "响应服务器状态请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Samples a server-state snapshot on the main thread.
     * <p>
     * <b>May only be called on the Bukkit main thread.</b> A call from a non-main thread is
     * refused and logged as SEVERE -- this is a second, defensive gate: scheduling already
     * guarantees the thread, but this class's own history is exactly "the contract was
     * recognized, only half applied", and writing the contract into the code is more reliable
     * than writing it into a comment.
     *
     * @return the new snapshot; {@code null} if not on the main thread
     */
    private ServerStateSnapshot sampleServerState() {
        if (!isOnPrimaryThreadOrComplain()) {
            return null;
        }

        JsonArray worlds = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            JsonObject worldObj = new JsonObject();
            worldObj.addProperty("name", world.getName());
            worldObj.addProperty("environment", world.getEnvironment().name());
            worldObj.addProperty("difficulty", world.getDifficulty().name());
            worldObj.addProperty("playerCount", world.getPlayers().size());
            worldObj.addProperty("loadedChunks", world.getLoadedChunks().length);
            worldObj.addProperty("pvpEnabled", world.getPVP());

            JsonObject spawnLoc = new JsonObject();
            spawnLoc.addProperty("x", world.getSpawnLocation().getBlockX());
            spawnLoc.addProperty("y", world.getSpawnLocation().getBlockY());
            spawnLoc.addProperty("z", world.getSpawnLocation().getBlockZ());
            worldObj.add("spawnLocation", spawnLoc);

            worlds.add(worldObj);
        }

        JsonArray onlinePlayers = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("uuid", player.getUniqueId().toString());
            playerObj.addProperty("name", player.getName());
            playerObj.addProperty("world", player.getWorld().getName());
            Location loc = player.getLocation();
            playerObj.addProperty("x", Math.round(loc.getX() * 10.0) / 10.0);
            playerObj.addProperty("y", Math.round(loc.getY() * 10.0) / 10.0);
            playerObj.addProperty("z", Math.round(loc.getZ() * 10.0) / 10.0);
            playerObj.addProperty("health", player.getHealth());
            playerObj.addProperty("maxHealth", player.getMaxHealth());
            playerObj.addProperty("foodLevel", player.getFoodLevel());
            playerObj.addProperty("gameMode", player.getGameMode().name());
            playerObj.addProperty("op", player.isOp());
            onlinePlayers.add(playerObj);
        }

        JsonArray plugins = new JsonArray();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            JsonObject pluginInfo = new JsonObject();
            pluginInfo.addProperty("name", plugin.getName());
            pluginInfo.addProperty("version", plugin.getDescription().getVersion());
            pluginInfo.addProperty("enabled", plugin.isEnabled());

            if (plugin.getDescription().getAuthors() != null && !plugin.getDescription().getAuthors().isEmpty()) {
                pluginInfo.addProperty("author", String.join(", ", plugin.getDescription().getAuthors()));
            } else {
                pluginInfo.addProperty("author", "Unknown");
            }

            pluginInfo.addProperty("description", plugin.getDescription().getDescription());
            plugins.add(pluginInfo);
        }

        // Counts are always taken from the arrays just constructed above, rather than asking
        // Bukkit again: this avoids two extra iterations, and it lets "playerCount matches
        // onlinePlayers' length" be guaranteed by construction, instead of relying on the
        // reasoning "it won't change within the same tick".
        return new ServerStateSnapshot(
                onlinePlayers.size(),
                Bukkit.getMaxPlayers(),
                Bukkit.getOnlineMode(),
                extractVersionNumber(Bukkit.getVersion()),
                worlds.size(),
                plugins.size(),
                worlds,
                onlinePlayers,
                plugins);
    }

    /**
     * The single check point for the thread contract: logs SEVERE and returns false when not on
     * the main thread.
     * <p>
     * This class's own history is exactly "the contract was recognized, only half applied", so
     * the contract is written as a runtime-observable signal rather than trusted to a comment --
     * on a real server, this line appearing in the log is proof that scheduling was broken.
     */
    private boolean isOnPrimaryThreadOrComplain() {
        if (Bukkit.isPrimaryThread()) {
            return true;
        }
        UltiTools.getInstance().getLogger().log(Level.SEVERE,
            "[ServerMonitor] 试图在非主线程上采样 Bukkit 状态，已拒绝。这是一个编程错误，请检查调度。");
        return false;
    }

    /**
     * Entry point for the main-thread scheduled task: samples and publishes the snapshot.
     * <p>
     * Package-visible so tests can drive it directly.
     */
    void refreshStateSnapshot() {
        // The thread-contract check runs **before** the connection check: even when
        // disconnected, "running on the wrong thread" must still be caught -- otherwise this
        // defensive gate would be mute exactly in the scenario that needs it most (unable to
        // connect, falling through various exception paths).
        if (!isOnPrimaryThreadOrComplain()) {
            return;
        }

        // Skip sampling when there is no connection. This isn't about saving power -- it
        // preserves pre-fix behavior: sendBatchUpdate used to **return first, iterate second**
        // when !isConnected, so a permanently disconnected server never iterated at all. If this
        // check weren't carried over after moving sampling to the main thread, a disconnected
        // server would instead iterate every world and chunk for nothing, every 5 seconds. See
        // the PR #265 review.
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        try {
            ServerStateSnapshot snapshot = sampleServerState();
            if (snapshot != null) {
                stateSnapshot = snapshot;
            }
        } catch (Exception e) {
            // Same convention as cancelTask: hand the exception itself to the logger instead of
            // concatenating getMessage() at the call site.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[ServerMonitor] 采样服务器状态失败", e);
        }
    }

    /** Lets tests assert whether sampling has actually happened yet -- more direct than observing the emitted JSON externally. */
    boolean hasSampledState() {
        return stateSnapshot != ServerStateSnapshot.EMPTY;
    }

    /**
     * Gets the current snapshot for the send thread to use.
     * <p>
     * If sampling has never happened even once and the caller happens to be on the main thread,
     * sample once right here -- otherwise the first frame after the connection is established
     * would be all zeroes, and on-demand requests like {@code sendServerStatusWithRequestId}
     * would return empty data before monitoring has started.
     */
    private ServerStateSnapshot currentSnapshot() {
        ServerStateSnapshot snapshot = stateSnapshot;
        if (snapshot == ServerStateSnapshot.EMPTY && Bukkit.isPrimaryThread()) {
            ServerStateSnapshot sampled = sampleServerState();
            if (sampled != null) {
                stateSnapshot = sampled;
                return sampled;
            }
        }
        return snapshot;
    }

    /**
     * Gets the current server status data.
     * <p>
     * Every Bukkit-derived field comes from the main-thread-sampled snapshot (see {@link
     * #stateSnapshot}); this method itself may be called from any thread. Runtime memory, JVM
     * uptime, TPS and CPU are not Bukkit state and are read in place -- TPS and CPU are already
     * maintained by the main thread's 1Hz task.
     */
    private JsonObject getCurrentServerStatusData() {
        ServerStateSnapshot snapshot = currentSnapshot();
        JsonObject data = new JsonObject();

        // Player information
        data.addProperty("playerCount", snapshot.playerCount);
        data.addProperty("maxPlayers", snapshot.maxPlayers);
        data.addProperty("onlineMode", snapshot.onlineMode);

        // Server version information
        data.addProperty("serverVersion", snapshot.serverVersion);

        // TPS information
        JsonArray tpsArray = new JsonArray();
        double[] tps = calculateTPS();
        for (double tpsValue : tps) {
            tpsArray.add(Math.round(tpsValue * 10.0) / 10.0);
        }
        data.add("tps", tpsArray);

        // Memory information
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024; // MB
        long totalMemory = runtime.totalMemory() / 1024 / 1024; // MB
        long freeMemory = runtime.freeMemory() / 1024 / 1024; // MB
        long usedMemory = totalMemory - freeMemory;

        JsonObject memory = new JsonObject();
        memory.addProperty("used", usedMemory);
        memory.addProperty("max", maxMemory);
        memory.addProperty("free", maxMemory - usedMemory);
        data.add("memory", memory);

        // CPU usage
        double cpuUsage = getCPUUsage();
        data.addProperty("cpu", Math.round(cpuUsage * 10.0) / 10.0);

        // Uptime
        data.addProperty("uptime", ManagementFactory.getRuntimeMXBean().getUptime());

        // World list (enriched objects) -- sampled on the main thread, only referenced here
        data.add("worlds", snapshot.worlds);

        // Online player details -- same as above
        data.add("onlinePlayers", snapshot.onlinePlayers);

        return data;
    }
    
    /**
     * Sends the batch_update message, merging status, metrics, plugins and logs into a single
     * WebSocket frame. Called every 5 seconds; plugins is included once every 12th tick (60 seconds).
     */
    private void sendBatchUpdate() {
        try {
            if (webSocketClient == null || !webSocketClient.isConnected()) {
                return;
            }

            JsonObject message = new JsonObject();
            message.addProperty("type", "batch_update");
            message.addProperty("serverId", webSocketClient.getServerId());
            message.addProperty("timestamp", System.currentTimeMillis());

            JsonObject data = new JsonObject();

            // status is always included
            data.add("status", getCurrentServerStatusData());

            // metrics is always included
            data.add("metrics", getCurrentMetricsData());

            // plugins is included every 12th tick (60 seconds) (Worker expects raw array, not wrapper object)
            if (tickCount % 12 == 0) {
                data.add("plugins", getCurrentPluginArray());
            }

            // Drain logs from the log transmitter -- whether to drain is decided by the LOGS
            // capability switch (D-12). The gate must sit before drainQueue(...), not after the
            // result is obtained only to be thrown away: drain-then-discard is still the shape of
            // "already collected into memory, just not sent out", which D-12 explicitly rejects
            // as a half-measure disable.
            if (Capability.LOGS.isEnabled()) {
                LogStreamManager lsm = UltiTools.getInstance().getLogStreamManager();
                if (lsm != null && lsm.getLogTransmitter() != null) {
                    JsonArray logs = lsm.getLogTransmitter().drainQueue(50);
                    if (logs.size() > 0) {
                        data.add("logs", logs);
                    }
                }
            }

            // Drain errors from the error report collector -- deliberately unaffected by any
            // Capability (D-07): error-reporting keeps its own pre-existing
            // ultipanel.logging.error-reporting.enabled key and its pre-existing default; moving
            // it under the capability switch would silently change a key an operator may already
            // have set by hand. ErrorReportCollector already gates on that key itself at the
            // collection layer, so draining an empty queue just yields an empty array.
            ErrorReportCollector erc = UltiTools.getInstance().getErrorReportCollector();
            if (erc != null) {
                JsonArray errors = erc.drainErrors(10);
                if (errors.size() > 0) {
                    data.add("errors", errors);
                }
            }

            message.add("data", data);
            webSocketClient.sendMessage(message);

            tickCount++;
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "Failed to send batch update: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the current plugin list array.
     * <p>
     * Taken from the main-thread-sampled snapshot. {@code Bukkit.getPluginManager().getPlugins()}
     * likewise should not be iterated from an async thread.
     */
    private JsonArray getCurrentPluginArray() {
        return currentSnapshot().plugins;
    }

    /**
     * Gets the current performance metrics data.
     * <p>
     * Bukkit-derived fields come from the snapshot; Runtime memory and TPS are computed in place.
     */
    private JsonObject getCurrentMetricsData() {
        ServerStateSnapshot snapshot = currentSnapshot();
        JsonObject data = new JsonObject();

        // Player activity statistics
        JsonObject playerActivity = new JsonObject();
        playerActivity.addProperty("currentOnline", snapshot.playerCount);
        playerActivity.addProperty("maxPlayers", snapshot.maxPlayers);
        data.add("playerActivity", playerActivity);

        // Server performance
        JsonObject serverPerformance = new JsonObject();
        double[] tps = calculateTPS();
        double avgTPS = 0;
        for (double t : tps) {
            avgTPS += t;
        }
        avgTPS /= tps.length;
        serverPerformance.addProperty("averageTPS", Math.round(avgTPS * 100.0) / 100.0);

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = ((double) usedMemory / maxMemory) * 100;
        serverPerformance.addProperty("memoryUsage", Math.round(memoryUsage * 100.0) / 100.0);

        serverPerformance.addProperty("diskUsage", 0.0);

        data.add("serverPerformance", serverPerformance);

        // Plugin usage
        JsonObject pluginUsage = new JsonObject();
        pluginUsage.addProperty("enabledPlugins", snapshot.pluginCount);
        pluginUsage.addProperty("loadedWorlds", snapshot.worldCount);
        data.add("pluginUsage", pluginUsage);

        return data;
    }

    /**
     * Sends performance metrics data (standalone message, for on-demand requests).
     */
    public void sendMetricsData() {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "metrics_data");

            JsonObject data = getCurrentMetricsData();

            message.add("data", data);
            message.addProperty("serverId", webSocketClient.getServerId());

            webSocketClient.sendMessage(message);

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送性能数据失败: " + e.getMessage());
        }
    }

    /**
     * Sends performance metrics data with a request ID (in response to a backend request).
     */
    public void sendMetricsDataWithRequestId(String requestId) {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "metrics_data");
            message.addProperty("requestId", requestId);

            JsonObject data = getCurrentMetricsData();

            message.add("data", data);
            message.addProperty("serverId", webSocketClient.getServerId());

            webSocketClient.sendMessage(message);

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送性能数据失败: " + e.getMessage());
        }
    }

    /**
     * Updates the TPS calculation + CPU sampling -- runs once per second (20 ticks).
     */
    private void updateTpsAndCpu() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastTick;

        // CPU sampling (sampled on every call, so the JVM has enough data to return a non-(-1) value)
        sampleCpuUsage();

        // Task runs every 20 ticks. At 20 TPS, timeDiff ≈ 1000ms.
        // TPS = 20 ticks * (1000ms / actual_elapsed_ms)
        double currentTPS = 20000.0 / Math.max(timeDiff, 50.0);
        currentTPS = Math.min(currentTPS, 20.0); // Cap the max TPS at 20

        // Store into the history arrays
        long tpsAsLong = Math.round(currentTPS * 100); // Stored as an integer scaled by 100
        tpsHistory1m[historyIndex % tpsHistory1m.length] = tpsAsLong;
        tpsHistory5m[historyIndex % tpsHistory5m.length] = tpsAsLong;
        tpsHistory15m[historyIndex % tpsHistory15m.length] = tpsAsLong;

        historyIndex++;
        lastTick = currentTime;

        UltiTools.getInstance().getLogger().log(Level.FINEST, 
            String.format("TPS更新: 当前TPS=%.2f, 时间间隔=%dms", currentTPS, timeDiff));
    }
    
    /**
     * Get current 1-minute average TPS.
     *
     * @return the 1-minute average TPS
     * @since 6.2.3
     */
    public double getCurrentTPS() {
        double[] tps = calculateTPS();
        return Math.round(tps[0] * 100.0) / 100.0;
    }

    /**
     * Calculates TPS -- returns the [1-minute, 5-minute, 15-minute] averages.
     */
    private double[] calculateTPS() {
        double[] tps = new double[3];

        // 1-minute TPS average
        tps[0] = calculateAverageTPS(tpsHistory1m, Math.min(historyIndex, tpsHistory1m.length));

        // 5-minute TPS average
        tps[1] = calculateAverageTPS(tpsHistory5m, Math.min(historyIndex, tpsHistory5m.length));

        // 15-minute TPS average
        tps[2] = calculateAverageTPS(tpsHistory15m, Math.min(historyIndex, tpsHistory15m.length));

        // If there isn't enough history yet, fall back to the realtime-computed TPS
        if (historyIndex < 60) {
            double realtimeTPS = calculateRealtimeTPS();
            if (historyIndex < 1) tps[0] = realtimeTPS;  // Needs at least 1 second of data
            if (historyIndex < 60) tps[1] = realtimeTPS; // Needs at least 60 seconds of data
            if (historyIndex < 300) tps[2] = realtimeTPS; // Needs at least 300 seconds of data
        }
        
        return tps;
    }
    
    /**
     * Calculates the average of a history-of-TPS array.
     */
    private double calculateAverageTPS(long[] history, int count) {
        if (count == 0) return 20.0;

        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += history[i];
        }

        return (sum / (double) count) / 100.0; // Convert back to decimal form
    }

    /**
     * Calculates the realtime TPS (based on the most recent history entry).
     */
    private double calculateRealtimeTPS() {
        // If history data exists, use the most recent TPS record
        if (historyIndex > 0) {
            int recentIndex = (historyIndex - 1) % tpsHistory1m.length;
            return tpsHistory1m[recentIndex] / 100.0; // Convert back to decimal form
        }

        // If there is no history data, return the default value
        return 20.0;
    }

    /**
     * Sends a player event.
     */
    public void sendPlayerEvent(String eventType, Player player, JsonObject additionalData) {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "player_event");

            JsonObject data = new JsonObject();
            data.addProperty("eventType", eventType);

            // Player information
            JsonObject playerInfo = new JsonObject();
            playerInfo.addProperty("uuid", player.getUniqueId().toString());
            playerInfo.addProperty("name", player.getName());
            playerInfo.addProperty("ip", player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
            data.add("player", playerInfo);

            // Location information
            if (player.getLocation() != null) {
                JsonObject location = new JsonObject();
                location.addProperty("world", player.getWorld().getName());
                location.addProperty("x", Math.round(player.getLocation().getX() * 100.0) / 100.0);
                location.addProperty("y", Math.round(player.getLocation().getY() * 100.0) / 100.0);
                location.addProperty("z", Math.round(player.getLocation().getZ() * 100.0) / 100.0);
                data.add("location", location);
            }

            // Add additional data
            if (additionalData != null) {
                for (String key : additionalData.keySet()) {
                    data.add(key, additionalData.get(key));
                }
            }
            
            data.addProperty("timestamp", System.currentTimeMillis());
            
            message.add("data", data);
            message.addProperty("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送玩家事件失败: " + e.getMessage());
        }
    }
    
    public boolean isMonitoring() {
        return isMonitoring;
    }
}
