package com.ultikits.ultitools.examples;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * 日志传输使用示例
 * 
 * 这个示例展示了如何使用UltiPanel日志传输功能
 * 
 * @author UltiKits
 * @version 1.0.0
 */
public class LogTransmissionExample implements Listener {
    
    private LogStreamManager logStreamManager;
    private UltiPanelLogTransmitter logTransmitter;
    
    /**
     * 初始化日志传输示例
     */
    public void initialize() {
        // 获取日志流管理器实例
        logStreamManager = UltiTools.getInstance().getLogStreamManager();
        
        if (logStreamManager != null) {
            // 获取日志传输器
            logTransmitter = logStreamManager.getLogTransmitter();
            
            // 注册事件监听器
            Bukkit.getPluginManager().registerEvents(this, UltiTools.getInstance());
            
            // 发送初始化日志
            sendInitializationLogs();
            
            UltiTools.getInstance().getLogger().info("[示例] 日志传输示例初始化完成");
        } else {
            UltiTools.getInstance().getLogger().warning("[示例] LogStreamManager未初始化，无法使用日志传输功能");
        }
    }
    
    /**
     * 发送初始化相关的日志
     */
    private void sendInitializationLogs() {
        if (logTransmitter == null) {
            return;
        }
        
        // 发送不同级别的日志示例
        logTransmitter.info("日志传输示例模块已启动", "plugin:LogExample");
        logTransmitter.warning("这是一条警告日志示例", "plugin:LogExample");
        logTransmitter.debug("这是一条调试日志示例", "plugin:LogExample");
        
        // 发送带异常的错误日志示例
        try {
            // 故意制造一个异常用于示例
            throw new RuntimeException("这是一个示例异常，用于展示错误日志传输");
        } catch (Exception e) {
            logTransmitter.error("日志传输异常示例", "plugin:LogExample", e);
        }
        
        // 使用日志流管理器发送自定义日志
        if (logStreamManager != null) {
            logStreamManager.sendCustomLog("info", "使用LogStreamManager发送的自定义日志", "plugin:LogExample");
            logStreamManager.sendPluginActionLog("INIT", "日志传输示例模块初始化完成");
        }
    }
    
    /**
     * 监听玩家命令执行事件，发送相关日志
     */
    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String playerName = event.getPlayer().getName();
        String command = event.getMessage();
        
        // 过滤一些常见命令，避免日志过多
        if (command.startsWith("/help") || command.startsWith("/tps") || command.startsWith("/list")) {
            return;
        }
        
        // 发送命令执行日志
        if (logTransmitter != null) {
            String message = String.format("玩家 %s 执行命令: %s", playerName, command);
            logTransmitter.info(message, "server");
        }
        
        // 使用日志流管理器发送玩家事件日志
        if (logStreamManager != null) {
            logStreamManager.sendPlayerEventLog("COMMAND", playerName, "执行命令: " + command);
        }
    }
    
    /**
     * 演示批量日志发送
     */
    public void demonstrateBatchLogging() {
        if (logTransmitter == null) {
            return;
        }
        
        UltiTools.getInstance().getLogger().info("[示例] 开始批量日志发送演示");
        
        // 发送多条日志，将会被批量处理
        for (int i = 1; i <= 15; i++) {
            String message = String.format("批量日志示例 #%d - 时间戳: %d", i, System.currentTimeMillis());
            logTransmitter.info(message, "plugin:BatchExample");
            
            // 稍微延迟避免过快
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        UltiTools.getInstance().getLogger().info("[示例] 批量日志发送演示完成");
    }
    
    /**
     * 演示不同类型的日志源
     */
    public void demonstrateLogSources() {
        if (logTransmitter == null) {
            return;
        }
        
        // 服务器核心日志
        logTransmitter.info("这是一条服务器核心日志", "server");
        
        // 插件日志
        logTransmitter.info("这是一条插件日志", "plugin:MyPlugin");
        
        // 数据库日志
        logTransmitter.warning("数据库连接超时", "database");
        
        // 网络日志
        logTransmitter.error("网络连接失败", "network", new RuntimeException("连接被拒绝"));
        
        // 自定义模块日志
        logTransmitter.info("备份任务开始", "module:backup");
        
        UltiTools.getInstance().getLogger().info("[示例] 不同日志源演示完成");
    }
    
    /**
     * 演示配置变更
     */
    public void demonstrateConfigurationChange() {
        if (logTransmitter == null) {
            return;
        }
        
        // 获取当前配置
        boolean currentBatchMode = logTransmitter.isBatchEnabled();
        int currentBatchSize = logTransmitter.getBatchSize();
        int currentInterval = logTransmitter.getIntervalMs();
        
        logTransmitter.info(String.format("当前配置 - 批量模式: %s, 批量大小: %d, 间隔: %dms", 
            currentBatchMode, currentBatchSize, currentInterval), "plugin:ConfigExample");
        
        // 临时修改配置进行演示
        logTransmitter.setBatchEnabled(false);
        logTransmitter.info("已切换到立即发送模式", "plugin:ConfigExample");
        
        // 恢复原配置
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logTransmitter.setBatchEnabled(currentBatchMode);
        logTransmitter.info("已恢复原始配置", "plugin:ConfigExample");
    }
    
    /**
     * 获取日志传输状态信息
     */
    public String getLogTransmissionStatus() {
        if (logStreamManager == null || logTransmitter == null) {
            return "日志传输系统未初始化";
        }
        
        StringBuilder status = new StringBuilder();
        status.append("=== UltiPanel 日志传输状态 ===\n");
        status.append("流状态: ").append(logStreamManager.isStreaming() ? "活跃" : "非活跃").append("\n");
        status.append("订阅客户端数量: ").append(logStreamManager.getSubscriberCount()).append("\n");
        status.append("传输器状态: ").append(logTransmitter.isLogTransmissionEnabled() ? "启用" : "禁用").append("\n");
        status.append("批量模式: ").append(logTransmitter.isBatchEnabled() ? "启用" : "禁用").append("\n");
        status.append("队列大小: ").append(logTransmitter.getQueueSize()).append("\n");
        status.append("批量大小: ").append(logTransmitter.getBatchSize()).append("\n");
        status.append("发送间隔: ").append(logTransmitter.getIntervalMs()).append("ms\n");
        
        return status.toString();
    }
}
