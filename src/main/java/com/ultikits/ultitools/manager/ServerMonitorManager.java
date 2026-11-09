package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.FrameworkText;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.logging.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server monitor manager.
 * Responsible for collecting server status information and sending it via WebSocket.
 */
@ApiStatus.Internal
public class ServerMonitorManager {

    // TPS calculation related
    private long lastTick = System.currentTimeMillis();
    private final long[] tpsHistory1m = new long[60];   // 1-minute TPS history
    private final long[] tpsHistory5m = new long[300];  // 5-minute TPS history
    private final long[] tpsHistory15m = new long[900]; // 15-minute TPS history
    private int historyIndex = 0;

    // CPU sampling (sampled periodically on the Bukkit main thread, read by the batch_update thread)
    private volatile double lastCpuUsage = 0.0;

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

    public ServerMonitorManager() {
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
    static final class ServerStateSnapshot {
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
            UltiTools.getInstance().getLogger().log(Level.FINE, FrameworkText.format("无法获取CPU使用率: %s", e.getMessage()));
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
            FrameworkText.text("[ServerMonitor] 试图在非主线程上采样 Bukkit 状态，已拒绝。这是一个编程错误，请检查调度。"));
        return false;
    }

    /**
     * Samples and publishes one state snapshot, so the v2 telemetry path and its tests can publish
     * a fresh sample.
     * <p>
     * <b>May only be called on the Bukkit main thread</b>; {@link #sampleServerState()} refuses
     * any other thread and logs it as SEVERE.
     *
     * @return {@code true} when a fresh snapshot was published, {@code false} off the main thread
     *         or when sampling failed
     */
    boolean sampleAndPublishState() {
        if (!isOnPrimaryThreadOrComplain()) {
            return false;
        }
        try {
            ServerStateSnapshot snapshot = sampleServerState();
            if (snapshot != null) {
                stateSnapshot = snapshot;
                return true;
            }
        } catch (Exception e) {
            // Same convention as cancelTask: hand the exception itself to the logger instead of
            // concatenating getMessage() at the call site.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                FrameworkText.text("[ServerMonitor] 采样服务器状态失败"), e);
        }
        return false;
    }

    /**
     * Runs one TPS and CPU sample on the main thread.
     * <p>
     * The v2 telemetry session (plan 22-22) schedules this once per second in place of the v1
     * {@code tpsTask}: {@link #getCurrentTPS()} and {@link #sampledCpuPercent()} read what it
     * records.
     * <p>
     * <b>May only be called on the Bukkit main thread</b>, like every other sample this class
     * takes; any other thread is refused and logged as SEVERE.
     *
     * @return {@code true} when the sample ran, {@code false} off the main thread
     */
    boolean sampleTpsAndCpu() {
        if (!isOnPrimaryThreadOrComplain()) {
            return false;
        }
        updateTpsAndCpu();
        return true;
    }

    /**
     * Starts the TPS clock now without recording a sample (plan 22-22 fix): the clock otherwise starts when this
     * object is created, and the v2 sampler first runs 20 ticks after the first connection, which may come long after
     * the plugin enabled; that gap would be recorded as the first TPS sample. CPU is sampled once so the next sample
     * has a value. <b>Main thread only</b>, like {@link #sampleTpsAndCpu()}.
     *
     * @return {@code true} when the clock was started, {@code false} off the main thread
     */
    boolean primeTpsClock() {
        if (!isOnPrimaryThreadOrComplain()) {
            return false;
        }
        sampleCpuUsage();
        lastTick = System.currentTimeMillis();
        return true;
    }

    /**
     * Reads the published main-thread sample for the v2 telemetry encoders
     * ({@link AgentTelemetry}). Package-private: internal cross-class reading, not a public API,
     * and the v1 send paths keep using {@link #currentSnapshot()}.
     * <p>
     * Samples once here when nothing has been published yet and the caller is on the main thread,
     * exactly like {@link #currentSnapshot()}; otherwise it returns the last publication, which
     * may be up to one sampling period old.
     *
     * @return the latest sampled state, or the empty snapshot before the first sample
     */
    ServerStateSnapshot currentStateSnapshot() {
        return currentSnapshot();
    }

    /**
     * @return the process CPU load percentage measured by the 1 Hz main-thread task, unclamped
     *         (the contract's encoder applies its own bounds)
     */
    double sampledCpuPercent() {
        return lastCpuUsage;
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
            FrameworkText.format("TPS更新: 当前TPS=%.2f, 时间间隔=%dms", currentTPS, timeDiff));
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
}
