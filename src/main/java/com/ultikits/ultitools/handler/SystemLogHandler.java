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
 * System log handler.
 * Captures all system log records and forwards them to the UltiPanel backend.
 *
 * @author UltiKits
 * @version 1.0.0
 */
public class SystemLogHandler extends Handler {
    
    private final UltiPanelLogTransmitter logTransmitter;
    
    // Log-level filter configuration
    @Getter @Setter
    private Set<String> enabledLevels;

    // Excluded-logger configuration
    @Getter @Setter
    private Set<String> excludedLoggers;

    // Minimum log level
    @Getter @Setter
    private Level minimumLevel = Level.INFO;

    /**
     * Constructor.
     *
     * @param logTransmitter the log transmitter
     */
    public SystemLogHandler(UltiPanelLogTransmitter logTransmitter) {
        this.logTransmitter = logTransmitter;

        // Initialize the default configuration
        initializeDefaultConfiguration();
    }

    /**
     * Initializes the default configuration.
     */
    private void initializeDefaultConfiguration() {
        // Log levels enabled by default
        enabledLevels = new HashSet<>();
        enabledLevels.add("info");
        enabledLevels.add("warning");
        enabledLevels.add("error");
        // enabledLevels.add("debug"); // debug logging is disabled by default

        // Loggers excluded by default (avoids transmitting excessive log volume)
        excludedLoggers = new HashSet<>();
        excludedLoggers.add("com.mojang.authlib");
        excludedLoggers.add("net.minecraft.network");
        excludedLoggers.add("org.apache.http");
        excludedLoggers.add("com.zaxxer.hikari");
        excludedLoggers.add("org.eclipse.jetty");
        excludedLoggers.add("ErrorReportCollector");

        // Apply the minimum level
        setLevel(minimumLevel);
    }
    
    /**
     * Loads configuration from the config file.
     */
    public void loadConfiguration() {
        try {
            // Load the log-level configuration
            if (UltiTools.getInstance().getConfig().contains("ultipanel.logging.levels")) {
                enabledLevels.clear();
                for (String level : UltiTools.getInstance().getConfig().getStringList("ultipanel.logging.levels")) {
                    enabledLevels.add(level.toLowerCase());
                }
            }

            // Load the excluded-logger configuration
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
        // Check whether this log record should be processed
        if (!shouldProcessRecord(record)) {
            return;
        }

        try {
            // Map the log level
            String level = mapLogLevel(record.getLevel());

            // Check whether the level is enabled
            if (!enabledLevels.contains(level)) {
                return;
            }

            // Format the message
            String message = formatLogMessage(record);

            // Determine the log source
            String source = determineLogSource(record);

            // Send the log
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
            // Avoid a logging loop by writing to System.err directly
            System.err.println("[UltiPanel] SystemLogHandler处理日志记录失败: " + e.getMessage());
        }
    }
    
    /**
     * Checks whether this log record should be processed.
     */
    private boolean shouldProcessRecord(LogRecord record) {
        // Check the log level
        if (!isLoggable(record)) {
            return false;
        }

        // Check whether the logger is excluded
        String loggerName = record.getLoggerName();
        if (loggerName != null) {
            for (String excluded : excludedLoggers) {
                if (loggerName.startsWith(excluded)) {
                    return false;
                }
            }
        }

        // Avoid processing UltiPanel's own log-transmission logs, to prevent a loop
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
     * Maps a Java log level to the UltiPanel level vocabulary.
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
     * Formats a log message.
     */
    private String formatLogMessage(LogRecord record) {
        String message = record.getMessage();

        if (message == null) {
            message = "";
        }

        // If parameters are present, format the message with them
        if (record.getParameters() != null && record.getParameters().length > 0) {
            try {
                message = String.format(message, record.getParameters());
            } catch (Exception e) {
                // Formatting failed - fall back to the raw message plus the parameter values
                StringBuilder sb = new StringBuilder(message);
                sb.append(" [参数: ");
                for (Object param : record.getParameters()) {
                    sb.append(param).append(", ");
                }
                if (sb.length() > 2) {
                    sb.setLength(sb.length() - 2); // Remove the trailing ", "
                }
                sb.append("]");
                message = sb.toString();
            }
        }
        
        return message;
    }
    
    /**
     * Determines the log-source identifier.
     */
    private String determineLogSource(LogRecord record) {
        String loggerName = record.getLoggerName();

        if (loggerName == null) {
            return "server";
        }

        // UltiTools-related log
        if (loggerName.startsWith("com.ultikits.ultitools")) {
            return "plugin:UltiTools";
        }

        // Plugin log
        if (loggerName.contains("plugin") || loggerName.startsWith("org.bukkit.plugin")) {
            String pluginName = extractPluginName(loggerName);
            return "plugin:" + pluginName;
        }

        // Server-core log
        if (loggerName.startsWith("net.minecraft") || 
            loggerName.startsWith("org.bukkit") ||
            loggerName.startsWith("org.spigotmc") ||
            loggerName.startsWith("org.apache.logging") ||
            loggerName.equals("Minecraft")) {
            return "server";
        }

        // Database-related log
        if (loggerName.startsWith("com.zaxxer.hikari") ||
            loggerName.startsWith("org.hibernate") ||
            loggerName.contains("database") ||
            loggerName.contains("mysql") ||
            loggerName.contains("sqlite")) {
            return "database";
        }

        // Network-related log
        if (loggerName.contains("network") ||
            loggerName.contains("netty") ||
            loggerName.contains("http")) {
            return "network";
        }

        // Everything else
        return "system";
    }

    /**
     * Extracts a plugin name from a logger name.
     */
    private String extractPluginName(String loggerName) {
        if (loggerName == null) {
            return "unknown";
        }

        // Try to extract the plugin name from the logger name
        String[] parts = loggerName.split("\\.");

        for (String part : parts) {
            // Skip common package-name prefixes
            if (!part.equals("org") && !part.equals("bukkit") && !part.equals("plugin") 
                && !part.equals("com") && !part.equals("github") && !part.equals("net")
                && !part.equals("java") && !part.equals("javax")) {

                // A non-standard package name found here is likely the plugin name
                return capitalizeFirst(part);
            }
        }

        return "unknown";
    }

    /**
     * Capitalizes the first letter.
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
     * Adds an enabled log level.
     */
    public void addEnabledLevel(String level) {
        if (level != null) {
            enabledLevels.add(level.toLowerCase());
        }
    }

    /**
     * Removes an enabled log level.
     */
    public void removeEnabledLevel(String level) {
        if (level != null) {
            enabledLevels.remove(level.toLowerCase());
        }
    }

    /**
     * Adds an excluded logger.
     */
    public void addExcludedLogger(String loggerName) {
        if (loggerName != null) {
            excludedLoggers.add(loggerName);
        }
    }

    /**
     * Removes an excluded logger.
     */
    public void removeExcludedLogger(String loggerName) {
        if (loggerName != null) {
            excludedLoggers.remove(loggerName);
        }
    }

    @Override
    public void flush() {
        // Flush the log transmitter
        if (logTransmitter != null) {
            logTransmitter.flushLogs();
        }
    }

    @Override
    public void close() throws SecurityException {
        // Flush any remaining logs on close
        flush();
    }

    /**
     * Gets a configuration-info string.
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
