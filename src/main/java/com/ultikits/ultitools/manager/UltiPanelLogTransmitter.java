package com.ultikits.ultitools.manager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * UltiPanel log transmitter.
 * Implements the log-transmission functionality per the API documentation specification.
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

    // External drain mode: when true, sendBatch() no longer sends automatically, and logs are
    // obtained externally by calling drainQueue()
    private final AtomicBoolean externalDrainMode = new AtomicBoolean(false);

    // Batch-send configuration
    @Getter
    private boolean batchEnabled = true; // setter below (#432) -- starts/stops the scheduled sender
    @Getter @Setter
    private int batchSize = 10;
    @Getter
    private int intervalMs = 5000; // 5-second interval; setter below (#432) reschedules the sender

    // Batch-send queue and scheduler
    private final ConcurrentLinkedQueue<JsonObject> logQueue;
    private final ScheduledExecutorService batchScheduler;

    /**
     * The currently-scheduled batch-send task, or {@code null} while batching is disabled.
     * <p>
     * Tracked so {@link #setIntervalMs(int)} and {@link #setBatchEnabled(boolean)} can cancel and
     * resubmit it -- before #432, the interval was baked into the one
     * {@code scheduleWithFixedDelay} call the constructor made, and the setter only mutated the
     * field without ever touching the already-running task.
     */
    private volatile ScheduledFuture<?> batchSenderTask;

    /**
     * Constructor.
     *
     * @param webSocketClient the WebSocket client
     * @param serverId the server ID
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

        // Start the batch-send task
        startBatchSender();
    }

    /**
     * Sends a log message to the backend.
     *
     * @param level the log level (info, warning, error, debug)
     * @param message the log message
     * @param source the log source (e.g.: "server", "plugin:name")
     * @param throwable the exception object (optional)
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

            // Add the logger name (optional)
            logData.addProperty("logger", determineLoggerName(logSource));

            // If there is an exception, add the stack trace
            if (throwable != null) {
                logData.addProperty("stackTrace", getStackTrace(throwable));
            } else {
                logData.add("stackTrace", null);
            }

            if (batchEnabled) {
                // Batch-send mode
                addToBatch(logData);
            } else {
                // Immediate-send mode
                sendLogImmediately(logData);
            }

        } catch (Exception e) {
            // Avoid a logging loop -- print to the console only (do not use the logger, to avoid the loop)
            System.err.println("[UltiPanel] 发送日志失败: " + e.getMessage() + " - " + e.getClass().getSimpleName());
        }
    }

    /**
     * Convenience methods: send a log at a specific level.
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
     * Sends a single log entry immediately.
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
     * Adds a log entry to the batch queue.
     */
    private void addToBatch(JsonObject logData) {
        // Drop oldest entries if queue is full
        while (logQueue.size() >= MAX_QUEUE_SIZE) {
            logQueue.poll(); // Discard oldest
        }

        logQueue.offer(logData);

        // If the queue is full, send immediately
        if (logQueue.size() >= batchSize) {
            sendBatch();
        }
    }

    /**
     * (Re)starts the batch-send task at the current {@link #intervalMs}. Cancels whatever task
     * was previously scheduled first, so this is safe to call to both start fresh and reschedule
     * (#432).
     */
    private void startBatchSender() {
        if (batchSenderTask != null) {
            batchSenderTask.cancel(false);
        }
        batchSenderTask = batchScheduler.scheduleWithFixedDelay(this::sendBatch,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the currently-scheduled batch-send task, if any, and clears the reference.
     */
    private void stopBatchSender() {
        if (batchSenderTask != null) {
            batchSenderTask.cancel(false);
            batchSenderTask = null;
        }
    }

    /**
     * Sets the batch-send interval, in milliseconds, and -- unlike the field it replaces as a
     * plain Lombok setter -- reschedules the already-running batch-send task to actually use it
     * (#432). A no-op, deliberately not touching the scheduler, when the requested value equals
     * the current one, so a live-config-update round-trip that resends the same value does not
     * churn the scheduler. Has no effect on the scheduler while batching is disabled (there is no
     * running task to reschedule); the new value still takes effect the next time batching is
     * enabled.
     *
     * @param intervalMs the new interval; must be positive
     * @throws IllegalArgumentException if {@code intervalMs} is zero or negative -- the previous
     *         interval is left in effect
     */
    public void setIntervalMs(int intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Batch interval must be positive, got: " + intervalMs);
        }
        if (intervalMs == this.intervalMs) {
            return;
        }
        this.intervalMs = intervalMs;
        if (batchSenderTask != null) {
            startBatchSender();
        }
    }

    /**
     * Enables or disables batched delivery, and -- unlike the field it replaces as a plain Lombok
     * setter -- actually starts or stops the scheduled batch-send task to match (#432): disabling
     * cancels it outright rather than leaving it running with nothing useful to flush; re-enabling
     * starts a fresh one at the current {@link #intervalMs}. A no-op when the requested value
     * equals the current one.
     *
     * @param batchEnabled whether batched delivery should be active
     */
    public void setBatchEnabled(boolean batchEnabled) {
        if (batchEnabled == this.batchEnabled) {
            return;
        }
        this.batchEnabled = batchEnabled;
        if (batchEnabled) {
            startBatchSender();
        } else {
            stopBatchSender();
        }
    }

    /**
     * Sends the batched logs.
     * When externalDrainMode is true, only drops entries that exceed the queue cap and does not send.
     */
    private void sendBatch() {
        if (logQueue.isEmpty()) {
            return;
        }

        // Under external drain mode, only enforce queue-overflow protection (already handled by
        // addToBatch), do not send
        if (externalDrainMode.get()) {
            return;
        }

        if (!webSocketClient.isConnected()) {
            return;
        }

        try {
            JsonArray logs = new JsonArray();

            // Pull logs out of the queue
            for (int i = 0; i < batchSize && !logQueue.isEmpty(); i++) {
                JsonObject log = logQueue.poll();
                if (log != null) {
                    logs.add(log);
                }
            }

            if (logs.size() > 0) {
                // Send the batched-log message
                JsonObject batchMessage = new JsonObject();
                batchMessage.addProperty("type", "log_batch");
                batchMessage.addProperty("serverId", serverId);
                batchMessage.add("data", logs);
                batchMessage.addProperty("timestamp", System.currentTimeMillis());

                webSocketClient.sendMessage(batchMessage);

                // Log the batch-send information
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    String.format("[UltiPanel] 批量发送 %d 条日志", logs.size()));
            }

        } catch (Exception e) {
            System.err.println("[UltiPanel] 发送批量日志失败: " + e.getMessage());
        }
    }

    /**
     * Drains log entries from the queue, returning them as a JsonArray.
     * For external callers to use (e.g. ServerMonitorManager's batch_update).
     *
     * @param maxItems the maximum number of entries to take
     * @return a JsonArray of log entries
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
     * Sets external drain mode.
     * When enabled, sendBatch() no longer sends logs automatically; they are obtained externally
     * via drainQueue() instead.
     *
     * @param enabled whether to enable external drain mode
     */
    public void setExternalDrainMode(boolean enabled) {
        this.externalDrainMode.set(enabled);
    }

    /**
     * Checks whether external drain mode is active.
     */
    public boolean isExternalDrainMode() {
        return externalDrainMode.get();
    }

    /**
     * Gets the exception stack trace as a string.
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
     * Determines the logger name.
     */
    private String determineLoggerName(String source) {
        if (source == null) {
            return "unknown";
        }

        if (source.startsWith("plugin:")) {
            return source.substring(7); // Remove the "plugin:" prefix
        } else if (source.equals("server")) {
            return "MinecraftServer";
        } else {
            return source;
        }
    }

    /**
     * Gets the default server ID.
     */
    private String getDefaultServerId() {
        try {
            return CommonUtils.getUltiToolsUUID();
        } catch (Exception e) {
            return "unknown-server";
        }
    }
    
    /**
     * Enables/disables log transmission.
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
     * Checks whether log transmission is enabled.
     */
    public boolean isLogTransmissionEnabled() {
        return logTransmissionEnabled.get();
    }

    /**
     * Gets the current queue size.
     */
    public int getQueueSize() {
        return logQueue.size();
    }

    /**
     * Immediately sends every log currently in the queue.
     * Temporarily disables external drain mode to make sure the logs actually get sent.
     */
    public void flushLogs() {
        boolean wasExternalDrain = externalDrainMode.getAndSet(false);
        try {
            // The continuation condition must be "the queue actually got shorter", not just
            // "the queue is non-empty".
            //
            // sendBatch() returns directly, **consuming no queue elements at all**, when the
            // WebSocket is not connected (see the isConnected check inside sendBatch). Written as
            // while (!logQueue.isEmpty()), that used to be an infinite loop -- and "the panel is
            // unreachable, the queue has backed up, and the socket is already disconnected" is
            // exactly the scenario most likely to be hit: an admin usually logs out or shuts the
            // server down precisely because the panel is unreachable.
            //
            // This infinite loop predates disableCloud() (onDisable also reaches this path), but
            // back then it only triggered on server shutdown; now logout reaches it from the
            // command thread too, which would hang the server outright. See the PR review for
            // issue #181 / #223.
            int previousSize = -1;
            while (!logQueue.isEmpty()) {
                int currentSize = logQueue.size();
                if (currentSize == previousSize) {
                    // Nothing was sent out in the previous round -- looping any further will not make progress
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
     * Shuts down the log transmitter.
     */
    public void shutdown() {
        try {
            // Send the remaining logs
            flushLogs();

            // Shut down the scheduler
            batchScheduler.shutdown();

            // Wait for the scheduler to shut down
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
