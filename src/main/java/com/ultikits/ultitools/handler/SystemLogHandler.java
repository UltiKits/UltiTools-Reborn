package com.ultikits.ultitools.handler;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * 系统日志处理器
 * 捕获所有系统日志并发送到UltiPanel后端
 * 
 * @author UltiKits
 * @version 1.0.0
 */
public class SystemLogHandler extends Handler {
    
    private final UltiPanelLogTransmitter logTransmitter;
    
    // 日志级别过滤配置
    @Getter @Setter
    private Set<String> enabledLevels;
    
    // 排除的记录器配置
    @Getter @Setter
    private Set<String> excludedLoggers;
    
    // 最小日志级别
    @Getter @Setter
    private Level minimumLevel = Level.INFO;
    
    /**
     * 构造函数
     * 
     * @param logTransmitter 日志传输器
     */
    public SystemLogHandler(UltiPanelLogTransmitter logTransmitter) {
        this.logTransmitter = logTransmitter;
        
        // 初始化默认配置
        initializeDefaultConfiguration();
    }
    
    /**
     * 初始化默认配置
     */
    private void initializeDefaultConfiguration() {
        // 默认启用的日志级别
        enabledLevels = new HashSet<>();
        enabledLevels.add("info");
        enabledLevels.add("warning");
        enabledLevels.add("error");
        // enabledLevels.add("debug"); // 调试日志默认禁用
        
        // 默认排除的记录器（避免传输过多日志）
        excludedLoggers = new HashSet<>();
        excludedLoggers.add("com.mojang.authlib");
        excludedLoggers.add("net.minecraft.network");
        excludedLoggers.add("org.apache.http");
        excludedLoggers.add("com.zaxxer.hikari");
        excludedLoggers.add("org.eclipse.jetty");
        excludedLoggers.add("ErrorReportCollector");

        // 设置最小级别
        setLevel(minimumLevel);
    }
    
    /**
     * 从配置文件加载配置
     */
    public void loadConfiguration() {
        try {
            // 加载日志级别配置
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.levels")) {
                enabledLevels.clear();
                for (String level : UltiTools.getInstance().getConfig().getStringList("ultipanel.logging.levels")) {
                    enabledLevels.add(level.toLowerCase());
                }
            }
            
            // 加载排除的记录器配置
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.excluded-loggers")) {
                excludedLoggers.clear();
                excludedLoggers.addAll(UltiTools.getInstance().getConfig().getStringList("ultipanel.logging.excluded-loggers"));
                // Always preserve internal loggers to prevent circular logging
                excludedLoggers.add("ErrorReportCollector");
            }
            
            UltiTools.getInstance().getLogger().info("[UltiPanel] 系统日志处理器配置已加载");
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().warning("[UltiPanel] 加载日志配置失败，使用默认配置: " + e.getMessage());
        }
    }
    
    @Override
    public void publish(LogRecord record) {
        // 检查是否应该处理此日志记录
        if (!shouldProcessRecord(record)) {
            return;
        }
        
        try {
            // 映射日志级别
            String level = mapLogLevel(record.getLevel());
            
            // 检查级别是否启用
            if (!enabledLevels.contains(level)) {
                return;
            }
            
            // 格式化消息
            String message = formatLogMessage(record);
            
            // 确定日志源
            String source = determineLogSource(record);
            
            // 发送日志
            logTransmitter.sendLog(level, message, source, record.getThrown());

            // Report error-level logs with exceptions to ErrorReportCollector
            if ("error".equals(level) && record.getThrown() != null) {
                try {
                    UltiTools instance = UltiTools.getInstance();
                    if (instance != null) {
                        ErrorReportCollector erc = instance.getErrorReportCollector();
                        if (erc != null) {
                            String moduleName = extractModuleFromSource(source);
                            TriggerContext ctx = inferTriggerFromStackTrace(record.getThrown());
                            erc.reportError(record.getThrown(), moduleName, ctx);
                        }
                    }
                } catch (Exception ignored) {
                    // Never re-enter logging from error reporting
                }
            }

        } catch (Exception e) {
            // 避免日志循环，使用System.err
            System.err.println("[UltiPanel] SystemLogHandler处理日志记录失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查是否应该处理此日志记录
     */
    private boolean shouldProcessRecord(LogRecord record) {
        // 检查日志级别
        if (!isLoggable(record)) {
            return false;
        }
        
        // 检查记录器是否被排除
        String loggerName = record.getLoggerName();
        if (loggerName != null) {
            for (String excluded : excludedLoggers) {
                if (loggerName.startsWith(excluded)) {
                    return false;
                }
            }
        }
        
        // 避免处理UltiPanel自身的日志传输相关日志，防止循环
        if (loggerName != null && (
            loggerName.contains("UltiPanelLogTransmitter") ||
            loggerName.contains("SystemLogHandler") ||
            loggerName.contains("WebSocketClient")
        )) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 映射Java日志级别到UltiPanel级别
     */
    private String mapLogLevel(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "error";
        } else if (level.intValue() >= Level.WARNING.intValue()) {
            return "warning";
        } else if (level.intValue() >= Level.INFO.intValue()) {
            return "info";
        } else {
            return "debug";
        }
    }
    
    /**
     * 格式化日志消息
     */
    private String formatLogMessage(LogRecord record) {
        String message = record.getMessage();
        
        if (message == null) {
            message = "";
        }
        
        // 如果有参数，则格式化消息
        if (record.getParameters() != null && record.getParameters().length > 0) {
            try {
                message = String.format(message, record.getParameters());
            } catch (Exception e) {
                // 格式化失败时，尝试使用原始消息加参数信息
                StringBuilder sb = new StringBuilder(message);
                sb.append(" [参数: ");
                for (Object param : record.getParameters()) {
                    sb.append(param).append(", ");
                }
                if (sb.length() > 2) {
                    sb.setLength(sb.length() - 2); // 移除最后的 ", "
                }
                sb.append("]");
                message = sb.toString();
            }
        }
        
        return message;
    }
    
    /**
     * 确定日志源标识
     */
    private String determineLogSource(LogRecord record) {
        String loggerName = record.getLoggerName();
        
        if (loggerName == null) {
            return "server";
        }
        
        // 判断是否为UltiTools相关日志
        if (loggerName.startsWith("com.ultikits.ultitools")) {
            return "plugin:UltiTools";
        }
        
        // 判断是否为插件日志
        if (loggerName.contains("plugin") || loggerName.startsWith("org.bukkit.plugin")) {
            String pluginName = extractPluginName(loggerName);
            return "plugin:" + pluginName;
        }
        
        // 判断是否为服务器核心日志
        if (loggerName.startsWith("net.minecraft") || 
            loggerName.startsWith("org.bukkit") ||
            loggerName.startsWith("org.spigotmc") ||
            loggerName.startsWith("org.apache.logging") ||
            loggerName.equals("Minecraft")) {
            return "server";
        }
        
        // 判断是否为数据库相关日志
        if (loggerName.startsWith("com.zaxxer.hikari") ||
            loggerName.startsWith("org.hibernate") ||
            loggerName.contains("database") ||
            loggerName.contains("mysql") ||
            loggerName.contains("sqlite")) {
            return "database";
        }
        
        // 判断是否为网络相关日志
        if (loggerName.contains("network") ||
            loggerName.contains("netty") ||
            loggerName.contains("http")) {
            return "network";
        }
        
        // 其他情况
        return "system";
    }
    
    /**
     * 从日志记录器名称中提取插件名称
     */
    private String extractPluginName(String loggerName) {
        if (loggerName == null) {
            return "unknown";
        }
        
        // 尝试从logger名称中提取插件名
        String[] parts = loggerName.split("\\.");
        
        for (String part : parts) {
            // 跳过常见的包名前缀
            if (!part.equals("org") && !part.equals("bukkit") && !part.equals("plugin") 
                && !part.equals("com") && !part.equals("github") && !part.equals("net")
                && !part.equals("java") && !part.equals("javax")) {
                
                // 如果找到非标准包名，很可能是插件名
                return capitalizeFirst(part);
            }
        }
        
        return "unknown";
    }
    
    /**
     * 首字母大写
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
    
    /**
     * Extract module name from the log source string (e.g., "plugin:UltiChat" -> "UltiChat").
     */
    private String extractModuleFromSource(String source) {
        if (source != null && source.startsWith("plugin:")) {
            return source.substring("plugin:".length());
        }
        return source != null ? source : "unknown";
    }

    /**
     * Infer trigger context from exception stack trace.
     */
    private TriggerContext inferTriggerFromStackTrace(Throwable throwable) {
        StackTraceElement[] frames = throwable.getStackTrace();
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            String methodName = frame.getMethodName();

            // Command execution
            if (className.contains("BaseCommandExecutor") && "executeCommand".equals(methodName)) {
                return TriggerContext.uncaught("command execution");
            }

            // Bukkit event handler
            if (className.contains("EventExecutor") || className.contains("TimedEventExecutor")) {
                // Try to find the event class name from surrounding frames
                for (StackTraceElement f : frames) {
                    if (f.getClassName().endsWith("Event") || f.getClassName().contains(".event.")) {
                        String eventName = f.getClassName();
                        int lastDot = eventName.lastIndexOf('.');
                        if (lastDot >= 0) {
                            eventName = eventName.substring(lastDot + 1);
                        }
                        return TriggerContext.event(eventName);
                    }
                }
                return TriggerContext.event("unknown");
            }

            // Scheduled task
            if (className.contains("BukkitRunnable") && "run".equals(methodName)) {
                return TriggerContext.scheduled("BukkitRunnable");
            }
            if (className.contains("CraftScheduler") || className.contains("ScheduledTask")) {
                return TriggerContext.scheduled("ScheduledTask");
            }
        }

        return TriggerContext.uncaught("unknown");
    }

    /**
     * 添加启用的日志级别
     */
    public void addEnabledLevel(String level) {
        if (level != null) {
            enabledLevels.add(level.toLowerCase());
        }
    }
    
    /**
     * 移除启用的日志级别
     */
    public void removeEnabledLevel(String level) {
        if (level != null) {
            enabledLevels.remove(level.toLowerCase());
        }
    }
    
    /**
     * 添加排除的记录器
     */
    public void addExcludedLogger(String loggerName) {
        if (loggerName != null) {
            excludedLoggers.add(loggerName);
        }
    }
    
    /**
     * 移除排除的记录器
     */
    public void removeExcludedLogger(String loggerName) {
        if (loggerName != null) {
            excludedLoggers.remove(loggerName);
        }
    }
    
    @Override
    public void flush() {
        // 刷新日志传输器
        if (logTransmitter != null) {
            logTransmitter.flushLogs();
        }
    }
    
    @Override
    public void close() throws SecurityException {
        // 关闭时刷新剩余日志
        flush();
    }
    
    /**
     * 获取配置信息字符串
     */
    public String getConfigurationInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("SystemLogHandler配置信息:\n");
        sb.append("- 最小日志级别: ").append(minimumLevel).append("\n");
        sb.append("- 启用的级别: ").append(enabledLevels).append("\n");
        sb.append("- 排除的记录器数量: ").append(excludedLoggers.size()).append("\n");
        sb.append("- 日志传输器状态: ").append(logTransmitter != null ? "已连接" : "未连接");
        return sb.toString();
    }
}
