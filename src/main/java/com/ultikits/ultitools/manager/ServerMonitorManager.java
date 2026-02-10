package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 服务器监控管理器
 * 负责收集服务器状态信息并通过WebSocket发送
 */
public class ServerMonitorManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final ScheduledExecutorService scheduler;
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
    
    public ServerMonitorManager() {
        this.scheduler = Executors.newScheduledThreadPool(2);
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
        scheduler.scheduleAtFixedRate(this::sendBatchUpdate, 5, 5, TimeUnit.SECONDS);

        // 启动TPS计算 + CPU采样任务（每秒）
        Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::updateTpsAndCpu, 0L, 20L);
    }
    
    /**
     * 停止监控
     */
    public void stopMonitoring() {
        if (!isMonitoring) {
            return;
        }
        
        isMonitoring = false;
        scheduler.shutdown();
        UltiTools.getInstance().getLogger().log(Level.INFO, "停止服务器状态监控");
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
            
            UltiTools.getInstance().getLogger().log(Level.FINE, 
                String.format("已发送服务器状态: 玩家 %d/%d, TPS %.1f, 内存 %dMB/%dMB", 
                    Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(),
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
     * 获取当前服务器状态数据
     */
    private JsonObject getCurrentServerStatusData() {
        JsonObject data = new JsonObject();
        
        // 玩家信息
        data.addProperty("playerCount", Bukkit.getOnlinePlayers().size());
        data.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        data.addProperty("onlineMode", Bukkit.getOnlineMode());
        
        // 服务器版本信息
        String version = Bukkit.getVersion();
        String serverVersion = extractVersionNumber(version);
        data.addProperty("serverVersion", serverVersion);
        
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
        
        // 世界列表
        JsonArray worlds = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(world.getName());
        }
        data.add("worlds", worlds);
        
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
     */
    private JsonArray getCurrentPluginArray() {
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

        return plugins;
    }

    /**
     * 获取当前插件列表数据（带wrapper，用于独立plugin_list消息）
     */
    private JsonObject getCurrentPluginList() {
        JsonObject data = new JsonObject();
        JsonArray plugins = getCurrentPluginArray();
        data.add("plugins", plugins);
        data.addProperty("totalCount", plugins.size());
        return data;
    }

    /**
     * 获取当前性能统计数据
     */
    private JsonObject getCurrentMetricsData() {
        JsonObject data = new JsonObject();

        // 玩家活动统计
        JsonObject playerActivity = new JsonObject();
        playerActivity.addProperty("currentOnline", Bukkit.getOnlinePlayers().size());
        playerActivity.addProperty("maxPlayers", Bukkit.getMaxPlayers());
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

        serverPerformance.addProperty("diskUsage", 0.0); // TODO: 实现磁盘使用率检测

        data.add("serverPerformance", serverPerformance);

        // 插件使用情况
        JsonObject pluginUsage = new JsonObject();
        pluginUsage.addProperty("enabledPlugins", Bukkit.getPluginManager().getPlugins().length);
        pluginUsage.addProperty("loadedWorlds", Bukkit.getWorlds().size());
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
