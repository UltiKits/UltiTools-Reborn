package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
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
 * 服务器监控管理器
 * 负责收集服务器状态信息并通过WebSocket发送
 */
@ApiStatus.Internal
public class ServerMonitorManager {
    private UltiPanelWebSocketClient webSocketClient;
    /**
     * 发送线程池。<b>不能是 final</b>：{@link #stopMonitoring()} 会 shutdown 它，而
     * {@code ScheduledExecutorService} 一经 shutdown 就永久失效。logout 之后再 login
     * 是一条完全正常的路径，那时 {@link #startMonitoring()} 必须拿到一个可用的池，
     * 否则 {@code scheduleAtFixedRate} 直接抛 {@code RejectedExecutionException}。
     */
    private ScheduledExecutorService scheduler;
    private boolean isMonitoring = false;
    private int tickCount = 0;

    // TPS计算相关
    private long lastTick = System.currentTimeMillis();
    private final long[] tpsHistory1m = new long[60];   // 1分钟TPS历史
    private final long[] tpsHistory5m = new long[300];  // 5分钟TPS历史
    private final long[] tpsHistory15m = new long[900]; // 15分钟TPS历史
    private int historyIndex = 0;

    // CPU采样（在Bukkit主线程定期采样，batch_update线程读取）
    private volatile double lastCpuUsage = 0.0;

    /** 世界/玩家/插件状态的采样周期，单位 tick。100 tick = 5 秒，与 batch_update 的发送节拍一致。 */
    private static final long SNAPSHOT_INTERVAL_TICKS = 100L;

    /**
     * 主线程采出来的服务器状态快照，异步发送线程只读它。
     * <p>
     * <b>这是 issue #179 的全部要点。</b>在它存在之前，{@code sendBatchUpdate} 跑在普通
     * {@code ScheduledThreadPool} 上，却在那里直接调 {@code Bukkit.getWorlds()}、
     * {@code world.getLoadedChunks()}、{@code Bukkit.getOnlinePlayers()}、
     * {@code player.getLocation()} —— 全是 Paper 明确不支持在异步线程上碰的可变世界状态，
     * 表现为偶发的并发修改异常或读到撕裂的数据。
     * <p>
     * 同一个类里的 TPS/CPU 采样<b>已经</b>正确地 hop 到了 {@code runTaskTimer}，说明契约当时
     * 就被识别到了，只是只应用了一半。本字段把剩下那一半补齐，沿用完全相同的模式。
     * <p>
     * 代价是数据最多陈旧一个采样周期（5 秒）。这是刻意选的：另一条路是让异步线程用
     * {@code callSyncMethod().get()} 同步等主线程，那会让监控的存活依赖主线程的健康度，
     * 而服务器卡顿时恰恰是最需要监控还能说话的时候。
     */
    private volatile ServerStateSnapshot stateSnapshot = ServerStateSnapshot.EMPTY;

    /** 主线程上的两个定时任务，{@link #stopMonitoring()} 需要能取消它们。 */
    private BukkitTask tpsTask;
    private BukkitTask snapshotTask;

    public ServerMonitorManager() {
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * 一次主线程采样的结果。字段全部 final，构造完成后不再改动，通过 volatile 字段发布，
     * 因此读线程看到的要么是上一份完整快照、要么是这一份完整快照，不会看到半份。
     * <p>
     * 其中三个 {@link JsonArray} 在发布之后<b>绝不可再被修改</b>——它们会被直接塞进待发送的
     * 消息里，序列化只读不写，所以共享实例是安全的；一旦有人在发布后 add 一笔，这个前提就没了。
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
     * 设置WebSocket客户端
     * @param client WebSocket客户端
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }
    
    /**
     * 开始监控服务器状态
     */
    public void startMonitoring() {
        if (isMonitoring) {
            return;
        }
        
        isMonitoring = true;
        // 上一轮 stopMonitoring 把池关掉了就换一个新的——见字段上的说明。
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(2);
        }
        UltiTools.getInstance().getLogger().log(Level.INFO, "启动服务器状态监控");
        
        // 等待WebSocket连接建立后，立即发送初始状态
        Bukkit.getScheduler().runTaskLater(UltiTools.getInstance(), () -> {
            if (webSocketClient != null && webSocketClient.isConnected()) {
                sendBatchUpdate();
            }
        }, 20L); // 等待1秒

        // 启用日志传输器的外部排空模式（日志将由batch_update统一发送）
        LogStreamManager lsm = UltiTools.getInstance().getLogStreamManager();
        if (lsm != null && lsm.getLogTransmitter() != null) {
            lsm.getLogTransmitter().setExternalDrainMode(true);
        }

        // 每5秒发送一次batch_update（包含status、metrics，每12个tick包含plugins，每次包含logs）
        // 注意：这条线程**只负责发送**，一切 Bukkit 状态都来自主线程采好的快照。见 issue #179。
        scheduler.scheduleAtFixedRate(this::sendBatchUpdate, 5, 5, TimeUnit.SECONDS);

        // 启动TPS计算 + CPU采样任务（每秒）
        tpsTask = Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::updateTpsAndCpu, 0L, 20L);

        // 世界/玩家/插件状态的采样任务（每5秒，主线程）。
        // 刻意不并进上面那个 1Hz 的任务：world.getLoadedChunks() 会分配一个装下所有已加载区块
        // 的数组，大服上并不便宜，按 1Hz 采就是凭空把这份开销放大 5 倍。100 tick 与今天实际的
        // 采样频率一致，只是换到了正确的线程上。
        snapshotTask = Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::refreshStateSnapshot,
                0L, SNAPSHOT_INTERVAL_TICKS);
    }

    /**
     * 停止监控
     * <p>
     * 除了关掉发送线程，还要取消两个主线程定时任务。原先这里只 {@code scheduler.shutdown()}，
     * 那在采样搬到主线程<b>之前</b>是够的——遍历世界那份开销本来就长在发送线程上，关掉发送
     * 就一起没了。搬家之后不取消的话，「停止监控」会变成「不再发送、但照样每 5 秒遍历一遍
     * 所有世界和区块」，而且是在主线程上。见 PR #265 的评审。
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
                // 把异常本身交给 logger 而不是拼 getMessage()：既保住栈，也避免在日志调用点
                // 无条件做字符串拼接（PMD 的 PreserveStackTrace / GuardLogStatement 都盯这一点）。
                UltiTools.getInstance().getLogger().log(Level.FINE, "取消监控任务时出错", e);
            }
        }
    }
    
    /**
     * 发送服务器状态信息
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
            
            // 发送消息
            webSocketClient.sendMessage(message);
            
            // 日志里的玩家数同样取自快照——这句也跑在异步线程上，直接读 Bukkit 是同一个毛病。
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
     * 提取版本号
     */
    private String extractVersionNumber(String fullVersion) {
        try {
            // 尝试从版本字符串中提取数字版本号
            // 例如: "git-Bukkit-abc123 (MC: 1.20.1)" -> "1.20.1"
            if (fullVersion.contains("MC: ")) {
                int start = fullVersion.indexOf("MC: ") + 4;
                int end = fullVersion.indexOf(")", start);
                if (end > start) {
                    return fullVersion.substring(start, end);
                }
            }
            
            // 如果无法提取，返回Bukkit版本
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
     * 获取CPU使用率（返回缓存值，由定期采样更新）
     */
    private double getCPUUsage() {
        return lastCpuUsage;
    }

    /**
     * 采样CPU使用率并更新缓存值
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

            // 备选方案：使用系统负载
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
     * 发送带请求ID的服务器状态信息（响应后端请求）
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
            message.addProperty("requestId", requestId); // 包含请求ID
            
            JsonObject data = getCurrentServerStatusData();
            message.add("data", data);
            
            // 发送消息
            webSocketClient.sendMessage(message);
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("已响应服务器状态请求，请求ID: %s", requestId));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "响应服务器状态请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 在主线程上采一份服务器状态快照。
     * <p>
     * <b>只能在 Bukkit 主线程调用。</b>非主线程调用会被拒绝并记 SEVERE —— 这是防御性的第二道
     * 闸：调度已经保证了线程，但这个类的历史正是「契约被识别了，只应用了一半」，把契约写进
     * 代码里比写进注释里可靠。
     *
     * @return 新的快照；若不在主线程则返回 {@code null}
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

        // 计数一律取自上面刚构造好的数组，而不是再问一次 Bukkit：既少两次遍历，
        // 也让「playerCount 与 onlinePlayers 长度一致」由构造保证，而不是靠「同一 tick 内不会变」这条推理。
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
     * 线程契约的唯一检查点：不在主线程就记 SEVERE 并返回 false。
     * <p>
     * 这个类的历史正是「契约被识别了，只应用了一半」，所以把契约写成运行时可观测的信号，
     * 比写进注释可靠——真机上只要日志里出现这句，就说明调度被改错了。
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
     * 主线程定时任务的入口：采样并发布快照。
     * <p>
     * 包级可见，供测试直接驱动。
     */
    void refreshStateSnapshot() {
        // 线程契约的检查放在连接判断**之前**：断连时也要抓得住「跑错线程」这件事，
        // 否则这道防御闸在最需要它的场景（连不上、于是走到各种异常路径）反而是哑的。
        if (!isOnPrimaryThreadOrComplain()) {
            return;
        }

        // 没有连接就不采样。这一条不是省电，是保持修复前的行为：原先 sendBatchUpdate 在
        // !isConnected 时是**先返回、再遍历**的，所以永久断连的服务器一次遍历都不做。
        // 采样搬到主线程之后若不带上这个判断，断连反而变成了每 5 秒白遍历一遍所有世界和区块。
        // 见 PR #265 的评审。
        if (webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        try {
            ServerStateSnapshot snapshot = sampleServerState();
            if (snapshot != null) {
                stateSnapshot = snapshot;
            }
        } catch (Exception e) {
            // 与 cancelTask 保持一致：异常交给 logger，不在调用点拼 getMessage()。
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[ServerMonitor] 采样服务器状态失败", e);
        }
    }

    /** 供测试断言「到底采过样没有」——比从外部观察发出去的 JSON 更直接。 */
    boolean hasSampledState() {
        return stateSnapshot != ServerStateSnapshot.EMPTY;
    }

    /**
     * 取当前快照供发送线程使用。
     * <p>
     * 若还一次都没采过、而调用方恰好就在主线程上，就地补采一次——否则连接建立后的第一帧
     * 会是一片零，以及 {@code sendServerStatusWithRequestId} 这类按需请求在监控启动前会返回空数据。
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
     * 获取当前服务器状态数据
     * <p>
     * 所有 Bukkit 派生的字段都取自主线程采好的快照（见 {@link #stateSnapshot}）；
     * 本方法本身可以在任意线程调用。Runtime 内存、JVM 运行时长、TPS、CPU 不属于 Bukkit 状态，
     * 就地读取即可——TPS 与 CPU 早已由主线程的 1Hz 任务维护。
     */
    private JsonObject getCurrentServerStatusData() {
        ServerStateSnapshot snapshot = currentSnapshot();
        JsonObject data = new JsonObject();

        // 玩家信息
        data.addProperty("playerCount", snapshot.playerCount);
        data.addProperty("maxPlayers", snapshot.maxPlayers);
        data.addProperty("onlineMode", snapshot.onlineMode);

        // 服务器版本信息
        data.addProperty("serverVersion", snapshot.serverVersion);

        // TPS信息
        JsonArray tpsArray = new JsonArray();
        double[] tps = calculateTPS();
        for (double tpsValue : tps) {
            tpsArray.add(Math.round(tpsValue * 10.0) / 10.0);
        }
        data.add("tps", tpsArray);
        
        // 内存信息
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
        
        // CPU使用率
        double cpuUsage = getCPUUsage();
        data.addProperty("cpu", Math.round(cpuUsage * 10.0) / 10.0);
        
        // 运行时间
        data.addProperty("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        
        // 世界列表 (enriched objects) —— 主线程采样，此处只引用
        data.add("worlds", snapshot.worlds);

        // Online player details —— 同上
        data.add("onlinePlayers", snapshot.onlinePlayers);

        return data;
    }
    
    /**
     * 发送batch_update消息，合并status、metrics、plugins、logs到单条WebSocket帧
     * 每5秒调用一次，plugins每12个tick（60秒）包含一次
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

            // 始终包含status
            data.add("status", getCurrentServerStatusData());

            // 始终包含metrics
            data.add("metrics", getCurrentMetricsData());

            // 每12个tick（60秒）包含plugins（Worker expects raw array, not wrapper object）
            if (tickCount % 12 == 0) {
                data.add("plugins", getCurrentPluginArray());
            }

            // 从日志传输器排空日志
            LogStreamManager lsm = UltiTools.getInstance().getLogStreamManager();
            if (lsm != null && lsm.getLogTransmitter() != null) {
                JsonArray logs = lsm.getLogTransmitter().drainQueue(50);
                if (logs.size() > 0) {
                    data.add("logs", logs);
                }
            }

            // 从错误报告收集器排空错误
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
     * 获取当前插件列表数组
     * <p>
     * 取自主线程采好的快照。{@code Bukkit.getPluginManager().getPlugins()} 同样不该在异步线程上遍历。
     */
    private JsonArray getCurrentPluginArray() {
        return currentSnapshot().plugins;
    }

    /**
     * 获取当前性能统计数据
     * <p>
     * Bukkit 派生的字段取自快照；Runtime 内存与 TPS 就地计算。
     */
    private JsonObject getCurrentMetricsData() {
        ServerStateSnapshot snapshot = currentSnapshot();
        JsonObject data = new JsonObject();

        // 玩家活动统计
        JsonObject playerActivity = new JsonObject();
        playerActivity.addProperty("currentOnline", snapshot.playerCount);
        playerActivity.addProperty("maxPlayers", snapshot.maxPlayers);
        data.add("playerActivity", playerActivity);

        // 服务器性能
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

        // 插件使用情况
        JsonObject pluginUsage = new JsonObject();
        pluginUsage.addProperty("enabledPlugins", snapshot.pluginCount);
        pluginUsage.addProperty("loadedWorlds", snapshot.worldCount);
        data.add("pluginUsage", pluginUsage);

        return data;
    }

    /**
     * 发送性能统计数据（独立消息，用于on-demand请求）
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
     * 发送性能统计数据（带请求ID，用于响应后端请求）
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
     * 更新TPS计算 + CPU采样 - 每秒执行一次（20 ticks）
     */
    private void updateTpsAndCpu() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastTick;

        // CPU采样（每次调用都采样，让JVM有足够数据返回非-1值）
        sampleCpuUsage();
        
        // Task runs every 20 ticks. At 20 TPS, timeDiff ≈ 1000ms.
        // TPS = 20 ticks * (1000ms / actual_elapsed_ms)
        double currentTPS = 20000.0 / Math.max(timeDiff, 50.0);
        currentTPS = Math.min(currentTPS, 20.0); // 限制最大TPS为20
        
        // 存储到历史数组
        long tpsAsLong = Math.round(currentTPS * 100); // 存储为百分制整数
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
     * 计算TPS - 返回 [1分钟, 5分钟, 15分钟] 平均值
     */
    private double[] calculateTPS() {
        double[] tps = new double[3];
        
        // 1分钟TPS平均值
        tps[0] = calculateAverageTPS(tpsHistory1m, Math.min(historyIndex, tpsHistory1m.length));
        
        // 5分钟TPS平均值
        tps[1] = calculateAverageTPS(tpsHistory5m, Math.min(historyIndex, tpsHistory5m.length));
        
        // 15分钟TPS平均值
        tps[2] = calculateAverageTPS(tpsHistory15m, Math.min(historyIndex, tpsHistory15m.length));
        
        // 如果没有足够的历史数据，使用实时计算的TPS
        if (historyIndex < 60) {
            double realtimeTPS = calculateRealtimeTPS();
            if (historyIndex < 1) tps[0] = realtimeTPS;  // 至少需要1秒数据
            if (historyIndex < 60) tps[1] = realtimeTPS; // 至少需要60秒数据
            if (historyIndex < 300) tps[2] = realtimeTPS; // 至少需要300秒数据
        }
        
        return tps;
    }
    
    /**
     * 计算历史TPS的平均值
     */
    private double calculateAverageTPS(long[] history, int count) {
        if (count == 0) return 20.0;
        
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += history[i];
        }
        
        return (sum / (double) count) / 100.0; // 转回小数形式
    }
    
    /**
     * 计算实时TPS（基于最近的历史记录）
     */
    private double calculateRealtimeTPS() {
        // 如果有历史数据，使用最近的TPS记录
        if (historyIndex > 0) {
            int recentIndex = (historyIndex - 1) % tpsHistory1m.length;
            return tpsHistory1m[recentIndex] / 100.0; // 转回小数形式
        }
        
        // 如果没有历史数据，返回默认值
        return 20.0;
    }
    
    /**
     * 发送玩家事件
     */
    public void sendPlayerEvent(String eventType, Player player, JsonObject additionalData) {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "player_event");
            
            JsonObject data = new JsonObject();
            data.addProperty("eventType", eventType);
            
            // 玩家信息
            JsonObject playerInfo = new JsonObject();
            playerInfo.addProperty("uuid", player.getUniqueId().toString());
            playerInfo.addProperty("name", player.getName());
            playerInfo.addProperty("ip", player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
            data.add("player", playerInfo);
            
            // 位置信息
            if (player.getLocation() != null) {
                JsonObject location = new JsonObject();
                location.addProperty("world", player.getWorld().getName());
                location.addProperty("x", Math.round(player.getLocation().getX() * 100.0) / 100.0);
                location.addProperty("y", Math.round(player.getLocation().getY() * 100.0) / 100.0);
                location.addProperty("z", Math.round(player.getLocation().getZ() * 100.0) / 100.0);
                data.add("location", location);
            }
            
            // 添加额外数据
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
