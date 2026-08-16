package com.ultikits.ultitools.manager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.CommonUtils;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.ApiStatus;

/**
 * UltiPanel日志传输器
 * 按照API文档规范实现日志传输功能
 * 
 * @author UltiKits
 * @version 1.0.0
 */
@ApiStatus.Internal
public class UltiPanelLogTransmitter {

    private static final int MAX_QUEUE_SIZE = 1000;

    private final UltiPanelWebSocketClient webSocketClient;
    private final String serverId;
    private final AtomicBoolean logTransmissionEnabled = new AtomicBoolean(true);

    // 外部排空模式：当为true时，sendBatch()不再自动发送，由外部调用drainQueue()获取日志
    private final AtomicBoolean externalDrainMode = new AtomicBoolean(false);

    // 批量发送配置
    @Getter @Setter
    private boolean batchEnabled = true;
    @Getter @Setter
    private int batchSize = 10;
    @Getter @Setter
    private int intervalMs = 5000; // 5秒间隔
    
    // 批量发送队列和调度器
    private final ConcurrentLinkedQueue<JsonObject> logQueue;
    private final ScheduledExecutorService batchScheduler;
    
    /**
     * 构造函数
     * 
     * @param webSocketClient WebSocket客户端
     * @param serverId 服务器ID
     */
    public UltiPanelLogTransmitter(UltiPanelWebSocketClient webSocketClient, String serverId) {
        this.webSocketClient = webSocketClient;
        this.serverId = serverId != null ? serverId : getDefaultServerId();
        this.logQueue = new ConcurrentLinkedQueue<>();
        this.batchScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "UltiPanel-LogTransmitter");
            thread.setDaemon(true);
            return thread;
        });
        
        // 启动批量发送任务
        startBatchSender();
    }
    
    /**
     * 发送日志消息到后端
     * 
     * @param level 日志级别 (info, warning, error, debug)
     * @param message 日志消息
     * @param source 日志来源 (如: "server", "plugin:名称")
     * @param throwable 异常对象（可选）
     */
    public void sendLog(String level, String message, String source, Throwable throwable) {
        if (!logTransmissionEnabled.get() || webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }
        
        String logLevel = level;
        String logSource = source;
        if (logLevel == null || logLevel.trim().isEmpty()) {
            logLevel = "info";
        }
        if (logSource == null || logSource.trim().isEmpty()) {
            logSource = "server";
        }
        
        try {
            JsonObject logData = new JsonObject();
            logData.addProperty("level", logLevel);
            logData.addProperty("message", message != null ? message : "");
            logData.addProperty("timestamp", System.currentTimeMillis());
            logData.addProperty("source", logSource);
            logData.addProperty("thread", Thread.currentThread().getName());

            // 添加记录器名称（可选）
            logData.addProperty("logger", determineLoggerName(logSource));
            
            // 如果有异常，添加堆栈跟踪
            if (throwable != null) {
                logData.addProperty("stackTrace", getStackTrace(throwable));
            } else {
                logData.add("stackTrace", null);
            }
            
            if (batchEnabled) {
                // 批量发送模式
                addToBatch(logData);
            } else {
                // 立即发送模式
                sendLogImmediately(logData);
            }
            
        } catch (Exception e) {
            // 避免日志循环，只输出到控制台（不使用日志记录器以避免循环）
            System.err.println("[UltiPanel] 发送日志失败: " + e.getMessage() + " - " + e.getClass().getSimpleName());
        }
    }
    
    /**
     * 便捷方法：发送不同级别的日志
     */
    public void info(String message, String source) {
        sendLog("info", message, source, null);
    }
    
    public void warning(String message, String source) {
        sendLog("warning", message, source, null);
    }
    
    public void error(String message, String source, Throwable throwable) {
        sendLog("error", message, source, throwable);
    }
    
    public void debug(String message, String source) {
        sendLog("debug", message, source, null);
    }
    
    /**
     * 立即发送单条日志
     */
    private void sendLogImmediately(JsonObject logData) {
        JsonObject wsMessage = new JsonObject();
        wsMessage.addProperty("type", "log_stream");
        wsMessage.addProperty("serverId", serverId);
        wsMessage.add("data", logData);
        wsMessage.addProperty("timestamp", System.currentTimeMillis());
        
        webSocketClient.sendMessage(wsMessage);
    }
    
    /**
     * 添加日志到批量队列
     */
    private void addToBatch(JsonObject logData) {
        // Drop oldest entries if queue is full
        while (logQueue.size() >= MAX_QUEUE_SIZE) {
            logQueue.poll(); // Discard oldest
        }

        logQueue.offer(logData);

        // 如果队列满了，立即发送
        if (logQueue.size() >= batchSize) {
            sendBatch();
        }
    }
    
    /**
     * 启动批量发送任务
     */
    private void startBatchSender() {
        batchScheduler.scheduleWithFixedDelay(this::sendBatch, 
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 发送批量日志
     * 当externalDrainMode为true时，仅丢弃超出队列上限的旧条目，不发送
     */
    private void sendBatch() {
        if (logQueue.isEmpty()) {
            return;
        }

        // 外部排空模式下，仅做队列溢出保护（addToBatch已处理），不发送
        if (externalDrainMode.get()) {
            return;
        }

        if (!webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonArray logs = new JsonArray();

            // 从队列中取出日志
            for (int i = 0; i < batchSize && !logQueue.isEmpty(); i++) {
                JsonObject log = logQueue.poll();
                if (log != null) {
                    logs.add(log);
                }
            }

            if (logs.size() > 0) {
                // 发送批量日志消息
                JsonObject batchMessage = new JsonObject();
                batchMessage.addProperty("type", "log_batch");
                batchMessage.addProperty("serverId", serverId);
                batchMessage.add("data", logs);
                batchMessage.addProperty("timestamp", System.currentTimeMillis());

                webSocketClient.sendMessage(batchMessage);

                // 记录批量发送信息
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    String.format("[UltiPanel] 批量发送 %d 条日志", logs.size()));
            }

        } catch (Exception e) {
            System.err.println("[UltiPanel] 发送批量日志失败: " + e.getMessage());
        }
    }

    /**
     * 从队列中排空日志条目，返回JsonArray
     * 供外部调用者（如ServerMonitorManager的batch_update）使用
     *
     * @param maxItems 最多取出的条目数
     * @return 日志条目的JsonArray
     */
    public JsonArray drainQueue(int maxItems) {
        JsonArray logs = new JsonArray();
        for (int i = 0; i < maxItems && !logQueue.isEmpty(); i++) {
            JsonObject log = logQueue.poll();
            if (log != null) {
                logs.add(log);
            }
        }
        return logs;
    }

    /**
     * 设置外部排空模式
     * 当启用时，sendBatch()不再自动发送日志，改由外部通过drainQueue()获取
     *
     * @param enabled 是否启用外部排空模式
     */
    public void setExternalDrainMode(boolean enabled) {
        this.externalDrainMode.set(enabled);
    }

    /**
     * 检查是否处于外部排空模式
     */
    public boolean isExternalDrainMode() {
        return externalDrainMode.get();
    }
    
    /**
     * 获取异常堆栈跟踪字符串
     */
    private String getStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            return sw.toString();
        } catch (Exception e) {
            return "Failed to get stack trace: " + e.getMessage();
        }
    }
    
    /**
     * 确定记录器名称
     */
    private String determineLoggerName(String source) {
        if (source == null) {
            return "unknown";
        }
        
        if (source.startsWith("plugin:")) {
            return source.substring(7); // 移除 "plugin:" 前缀
        } else if (source.equals("server")) {
            return "MinecraftServer";
        } else {
            return source;
        }
    }
    
    /**
     * 获取默认服务器ID
     */
    private String getDefaultServerId() {
        try {
            return CommonUtils.getUltiToolsUUID();
        } catch (Exception e) {
            return "unknown-server";
        }
    }
    
    /**
     * 启用/禁用日志传输
     */
    public void setLogTransmissionEnabled(boolean enabled) {
        this.logTransmissionEnabled.set(enabled);
        
        if (enabled) {
            UltiTools.getInstance().getLogger().info("[UltiPanel] 日志传输已启用");
        } else {
            UltiTools.getInstance().getLogger().info("[UltiPanel] 日志传输已禁用");
        }
    }
    
    /**
     * 检查日志传输是否启用
     */
    public boolean isLogTransmissionEnabled() {
        return logTransmissionEnabled.get();
    }
    
    /**
     * 获取当前队列大小
     */
    public int getQueueSize() {
        return logQueue.size();
    }
    
    /**
     * 立即发送队列中的所有日志
     * 临时禁用外部排空模式以确保日志被发送
     */
    public void flushLogs() {
        boolean wasExternalDrain = externalDrainMode.getAndSet(false);
        try {
            // 必须以「队列真的变短了」作为继续的条件，不能只看队列非空。
            //
            // sendBatch() 在 WebSocket 未连接时会直接 return 且**不消费任何队列元素**
            // （见 sendBatch 里的 isConnected 判断）。原先写成 while (!logQueue.isEmpty())
            // 就是死循环——而「面板连不上、队列积压、socket 已断」恰恰是最容易命中的场景：
            // 管理员之所以要 logout 或关服，通常正是因为面板连不上。
            //
            // 这个死循环在 disableCloud() 出现之前就存在（onDisable 也会走到这条路径），
            // 但那时只在关服时触发；现在 logout 在命令线程上也会走到，会直接卡住服务器。
            // 见 issue #181 / #223 的 PR 评审。
            int previousSize = -1;
            while (!logQueue.isEmpty()) {
                int currentSize = logQueue.size();
                if (currentSize == previousSize) {
                    // 上一轮一个都没送出去，再转多少次也不会有进展
                    break;
                }
                previousSize = currentSize;
                sendBatch();
            }
        } finally {
            if (wasExternalDrain) {
                externalDrainMode.set(true);
            }
        }
    }
    
    /**
     * 关闭日志传输器
     */
    public void shutdown() {
        try {
            // 发送剩余的日志
            flushLogs();
            
            // 关闭调度器
            batchScheduler.shutdown();
            
            // 等待调度器关闭
            if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                batchScheduler.shutdownNow();
            }
            
            logTransmissionEnabled.set(false);
            UltiTools.getInstance().getLogger().info("[UltiPanel] 日志传输器已关闭");
            
        } catch (InterruptedException e) {
            batchScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[UltiPanel] 关闭日志传输器时发生错误: " + e.getMessage());
        }
    }
}
