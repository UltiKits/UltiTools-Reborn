package com.ultikits.ultitools.manager;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
    
    // TPS计算相关
    private long lastTick = System.currentTimeMillis();
    private final long[] tpsHistory1m = new long[60];   // 1分钟TPS历史
    private final long[] tpsHistory5m = new long[300];  // 5分钟TPS历史
    private final long[] tpsHistory15m = new long[900]; // 15分钟TPS历史
    private int historyIndex = 0;
    
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
                sendServerStatus();
            }
        }, 20L); // 等待1秒
        
        // 按照文档建议，每30秒发送一次服务器状态
        scheduler.scheduleAtFixedRate(this::sendServerStatus, 30, 30, TimeUnit.SECONDS);
        
        // 每60秒发送一次插件列表
        scheduler.scheduleAtFixedRate(this::sendPluginList, 45, 60, TimeUnit.SECONDS);
        
        // 每2分钟发送一次性能数据
        scheduler.scheduleAtFixedRate(this::sendMetricsData, 90, 120, TimeUnit.SECONDS);
        
        // 启动TPS计算任务
        Bukkit.getScheduler().runTaskTimer(UltiTools.getInstance(), this::updateTPS, 0L, 20L);
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
            
            JSONObject message = new JSONObject();
            message.put("type", "server_status");
            message.put("serverId", webSocketClient.getServerId());
            message.put("timestamp", System.currentTimeMillis());
            
            JSONObject data = getCurrentServerStatusData();
            message.put("data", data);
            
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
     * 获取CPU使用率
     */
    private double getCPUUsage() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunOsBean.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    return cpuLoad * 100;
                }
            }
            
            // 备选方案：使用系统负载
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad >= 0) {
                // 将系统负载转换为百分比（近似值）
                int processors = osBean.getAvailableProcessors();
                return Math.min((systemLoad / processors) * 100, 100);
            }
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE, "无法获取CPU使用率: " + e.getMessage());
        }
        
        return 0.0; // 默认值
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
            
            JSONObject message = new JSONObject();
            message.put("type", "server_status");
            message.put("serverId", webSocketClient.getServerId());
            message.put("timestamp", System.currentTimeMillis());
            message.put("requestId", requestId); // 包含请求ID
            
            JSONObject data = getCurrentServerStatusData();
            message.put("data", data);
            
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
    private JSONObject getCurrentServerStatusData() {
        JSONObject data = new JSONObject();
        
        // 玩家信息
        data.put("playerCount", Bukkit.getOnlinePlayers().size());
        data.put("maxPlayers", Bukkit.getMaxPlayers());
        data.put("onlineMode", Bukkit.getOnlineMode());
        
        // 服务器版本信息
        String version = Bukkit.getVersion();
        String serverVersion = extractVersionNumber(version);
        data.put("serverVersion", serverVersion);
        
        // TPS信息
        JSONArray tpsArray = new JSONArray();
        double[] tps = calculateTPS();
        for (double tpsValue : tps) {
            tpsArray.add(Math.round(tpsValue * 10.0) / 10.0);
        }
        data.put("tps", tpsArray);
        
        // 内存信息
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024; // MB
        long totalMemory = runtime.totalMemory() / 1024 / 1024; // MB
        long freeMemory = runtime.freeMemory() / 1024 / 1024; // MB
        long usedMemory = totalMemory - freeMemory;
        
        JSONObject memory = new JSONObject();
        memory.put("used", usedMemory);
        memory.put("max", maxMemory);
        memory.put("free", maxMemory - usedMemory);
        data.put("memory", memory);
        
        // CPU使用率
        double cpuUsage = getCPUUsage();
        data.put("cpu", Math.round(cpuUsage * 10.0) / 10.0);
        
        // 运行时间
        data.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        
        // 世界列表
        JSONArray worlds = new JSONArray();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(world.getName());
        }
        data.put("worlds", worlds);
        
        return data;
    }
    
    /**
     * 发送插件列表
     */
    private void sendPluginList() {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "plugin_list");
            
            JSONObject data = new JSONObject();
            JSONArray plugins = new JSONArray();
            
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                JSONObject pluginInfo = new JSONObject();
                pluginInfo.put("name", plugin.getName());
                pluginInfo.put("version", plugin.getDescription().getVersion());
                pluginInfo.put("enabled", plugin.isEnabled());
                
                if (!plugin.getDescription().getAuthors().isEmpty()) {
                    pluginInfo.put("author", String.join(", ", plugin.getDescription().getAuthors()));
                } else {
                    pluginInfo.put("author", "Unknown");
                }
                
                pluginInfo.put("description", plugin.getDescription().getDescription());
                plugins.add(pluginInfo);
            }
            
            data.put("plugins", plugins);
            data.put("totalCount", plugins.size());
            
            message.put("data", data);
            message.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送插件列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送性能统计数据
     */
    public void sendMetricsData() {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "metrics_data");
            
            JSONObject data = new JSONObject();
            
            // 玩家活动统计
            JSONObject playerActivity = new JSONObject();
            playerActivity.put("currentOnline", Bukkit.getOnlinePlayers().size());
            playerActivity.put("maxPlayers", Bukkit.getMaxPlayers());
            // TODO: 这里可以添加更多统计信息，如每日登录数等
            data.put("playerActivity", playerActivity);
            
            // 服务器性能
            JSONObject serverPerformance = new JSONObject();
            double[] tps = calculateTPS();
            double avgTPS = 0;
            for (double t : tps) {
                avgTPS += t;
            }
            avgTPS /= tps.length;
            serverPerformance.put("averageTPS", Math.round(avgTPS * 100.0) / 100.0);
            
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double memoryUsage = ((double) usedMemory / maxMemory) * 100;
            serverPerformance.put("memoryUsage", Math.round(memoryUsage * 100.0) / 100.0);
            
            // 磁盘使用率（简化实现）
            serverPerformance.put("diskUsage", 0.0); // TODO: 实现磁盘使用率检测
            
            data.put("serverPerformance", serverPerformance);
            
            // 插件使用情况
            JSONObject pluginUsage = new JSONObject();
            pluginUsage.put("enabledPlugins", Bukkit.getPluginManager().getPlugins().length);
            pluginUsage.put("loadedWorlds", Bukkit.getWorlds().size());
            data.put("pluginUsage", pluginUsage);
            
            message.put("data", data);
            message.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送性能数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送性能统计数据（带请求ID）
     */
    public void sendMetricsDataWithRequestId(String requestId) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "metrics_data");
            message.put("requestId", requestId);
            
            JSONObject data = new JSONObject();
            
            // 玩家活动统计
            JSONObject playerActivity = new JSONObject();
            playerActivity.put("currentOnline", Bukkit.getOnlinePlayers().size());
            playerActivity.put("maxPlayers", Bukkit.getMaxPlayers());
            // TODO: 这里可以添加更多统计信息，如每日登录数等
            data.put("playerActivity", playerActivity);
            
            // 服务器性能
            JSONObject serverPerformance = new JSONObject();
            double[] tps = calculateTPS();
            double avgTPS = 0;
            for (double t : tps) {
                avgTPS += t;
            }
            avgTPS /= tps.length;
            serverPerformance.put("averageTPS", Math.round(avgTPS * 100.0) / 100.0);
            
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double memoryUsage = ((double) usedMemory / maxMemory) * 100;
            serverPerformance.put("memoryUsage", Math.round(memoryUsage * 100.0) / 100.0);
            
            // 磁盘使用率（简化实现）
            serverPerformance.put("diskUsage", 0.0); // TODO: 实现磁盘使用率检测
            
            data.put("serverPerformance", serverPerformance);
            
            // 插件使用情况
            JSONObject pluginUsage = new JSONObject();
            pluginUsage.put("enabledPlugins", Bukkit.getPluginManager().getPlugins().length);
            pluginUsage.put("loadedWorlds", Bukkit.getWorlds().size());
            data.put("pluginUsage", pluginUsage);
            
            message.put("data", data);
            message.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送性能数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新TPS计算 - 每秒执行一次
     */
    private void updateTPS() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastTick;
        
        // 计算这一秒的TPS（理论上应该接近1000ms）
        double currentTPS = 1000.0 / Math.max(timeDiff, 50.0); // 防止除零，最小50ms
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
    public void sendPlayerEvent(String eventType, Player player, JSONObject additionalData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "player_event");
            
            JSONObject data = new JSONObject();
            data.put("eventType", eventType);
            
            // 玩家信息
            JSONObject playerInfo = new JSONObject();
            playerInfo.put("uuid", player.getUniqueId().toString());
            playerInfo.put("name", player.getName());
            playerInfo.put("ip", player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
            data.put("player", playerInfo);
            
            // 位置信息
            if (player.getLocation() != null) {
                JSONObject location = new JSONObject();
                location.put("world", player.getWorld().getName());
                location.put("x", Math.round(player.getLocation().getX() * 100.0) / 100.0);
                location.put("y", Math.round(player.getLocation().getY() * 100.0) / 100.0);
                location.put("z", Math.round(player.getLocation().getZ() * 100.0) / 100.0);
                data.put("location", location);
            }
            
            // 添加额外数据
            if (additionalData != null) {
                for (String key : additionalData.keySet()) {
                    data.put(key, additionalData.get(key));
                }
            }
            
            data.put("timestamp", System.currentTimeMillis());
            
            message.put("data", data);
            message.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送玩家事件失败: " + e.getMessage());
        }
    }
    
    public boolean isMonitoring() {
        return isMonitoring;
    }
}
