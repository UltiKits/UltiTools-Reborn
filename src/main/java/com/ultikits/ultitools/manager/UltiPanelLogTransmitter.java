package com.ultikits.ultitools.manager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Shared lower bound for the batch-send interval (#432/WR-01). Before this fix, the
     * boot-time path ({@link LogStreamManager#loadBatchConfiguration()}) clamped to 1000ms via
     * its own inline {@code Math.max(1000, interval)}, while this class's own
     * {@link #setIntervalMs(int)} -- reachable live, over the panel's WebSocket {@code config}
     * action -- only rejected a non-positive value, leaving every value from 1ms up accepted. A
     * panel operator (or a compromised/buggy panel session) could drive the batch scheduler to a
     * roughly 1000-sends/second cadence indefinitely. Both entry points now share this one
     * constant instead of the live path being silently more permissive than the boot path.
     */
    static final int MIN_INTERVAL_MS = 1000;

    private final UltiPanelWebSocketClient webSocketClient;
    private final String serverId;
    private final AtomicBoolean logTransmissionEnabled = new AtomicBoolean(true);

    /**
     * Guards the batch-vs-immediate DISPATCH DECISION in {@link #sendLog} against the
     * enabled-to-disabled TRANSITION in {@link #setBatchEnabled(boolean)} (Gate-2 finding,
     * round 6). Before this lock, {@code setBatchEnabled(false)} wrote {@code this.batchEnabled
     * = false} before {@link #flushLogs()} drained the existing queue, so a concurrent
     * {@code sendLog()} call could read the new {@code false} value and send a NEWER record
     * immediately while {@code flushLogs()} was still draining OLDER queued records -- the
     * newer record then arrived at the panel before the older ones, breaking FIFO order.
     * Holding this lock across both {@code sendLog}'s dispatch decision and the WHOLE disable
     * transition means every {@code sendLog} call observes the transition as atomic: either
     * entirely before it (queued, and included in the flush that is about to run) or entirely
     * after it (flag already false, sent immediately) -- never during.
     */
    private final Object batchModeLock = new Object();

    // External drain mode: when true, sendBatch() no longer sends automatically, and logs are
    // obtained externally by calling drainQueue()
    private final AtomicBoolean externalDrainMode = new AtomicBoolean(false);

    /**
     * Invoked from {@link #addToBatch(JsonObject)} whenever the queue reaches {@link #batchSize}
     * WHILE {@link #externalDrainMode} is active (Gate-2 finding, round 6). Without this,
     * reaching the size threshold under external drain mode had no effect at all --
     * {@link #sendBatch()}'s own early-return for external drain mode silently swallowed the
     * threshold crossing that would otherwise have triggered an immediate send, so a documented
     * size threshold never actually shortened delivery latency while monitoring was active, and a
     * sustained burst could fill {@link #MAX_QUEUE_SIZE} and start discarding old entries despite
     * repeatedly crossing the threshold. {@code null} by default (no external owner wired up);
     * {@link LogStreamManager#initialize} sets it alongside {@link #setExternalDrainMode(boolean)}.
     */
    private volatile Runnable externalSizeThresholdCallback;

    /**
     * External coordination lock for {@link #flushLogs()} (Gate-2 finding, round 9). {@code null}
     * by default (no external owner wired up -- {@code flushLogs()} runs unsynchronized, its
     * original behaviour). {@link LogStreamManager#initialize} wires this to
     * {@code ServerMonitorManager}'s OWN {@code logDrainLock} (the same object
     * {@link ServerMonitorManager#drainAndSendLogsOnly} and its inline {@code sendBatchUpdate}
     * drain already synchronize on) alongside {@link #setExternalSizeThresholdCallback}. Without
     * this, a live {@code batchConfig.enabled: false} update's flush ran entirely outside that
     * lock, so it could still send NEWER records ahead of OLDER ones the monitor had already
     * polled but not yet sent, even after round 7's monitor-side-only serialization.
     */
    private volatile Object externalDrainCoordinationLock;

    // Batch-send configuration
    @Getter
    private boolean batchEnabled = true; // setter below (#432) -- starts/stops the scheduled sender
    @Getter
    private int batchSize = 10; // setter below (Gate-2) -- rejects a value below 1
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

            // Gate-2 finding (round 6): held across the read of batchEnabled AND the resulting
            // call, matching setBatchEnabled(false)'s own lock -- see batchModeLock's javadoc.
            synchronized (batchModeLock) {
                if (batchEnabled) {
                    // Batch-send mode
                    addToBatch(logData);
                } else {
                    // Immediate-send mode
                    sendLogImmediately(logData);
                }
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
            // Gate-2 finding (round 6): sendBatch() itself is a no-op under external drain mode
            // (see its own early return), so the threshold crossing above would otherwise have
            // no effect at all while monitoring is active. Notify the external owner instead, so
            // it can perform its own immediate drain rather than waiting for its next scheduled
            // tick -- see externalSizeThresholdCallback's own javadoc.
            if (externalDrainMode.get() && externalSizeThresholdCallback != null) {
                externalSizeThresholdCallback.run();
            }
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
     * @param intervalMs the new interval; must be at least {@link #MIN_INTERVAL_MS}
     * @throws IllegalArgumentException if {@code intervalMs} is below {@link #MIN_INTERVAL_MS}
     *         (including zero or negative) -- the previous interval is left in effect
     */
    public void setIntervalMs(int intervalMs) {
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(
                    "Batch interval must be at least " + MIN_INTERVAL_MS + "ms, got: " + intervalMs);
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
     * Sets the batch send-threshold size, rejecting anything below 1 (Gate-2 finding). A value of
     * zero or negative would make {@link #sendBatch()}'s own {@code for (int i = 0; i < batchSize
     * ...)} loop consume nothing on every scheduled run, while {@link #addToBatch(JsonObject)}'s
     * {@code logQueue.size() >= batchSize} check is simultaneously always true (any non-negative
     * queue size satisfies {@code >= 0} or {@code >= a negative number}) -- so every single
     * enqueued record would trigger an immediate {@code sendBatch()} call that dequeues nothing,
     * silently stalling delivery while burning CPU on every log line, rather than the panel
     * request being rejected outright. {@code LogStreamManager#loadBatchConfiguration()}'s own
     * boot-time path already clamped to {@code Math.max(1, batchSize)}; the live panel path had no
     * lower bound at all before this fix, unlike {@link #setIntervalMs(int)}'s pre-existing floor.
     *
     * @param batchSize the new batch size; must be at least 1
     * @throws IllegalArgumentException if {@code batchSize} is below 1 -- the previous value is
     *         left in effect
     */
    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1, got: " + batchSize);
        }
        this.batchSize = batchSize;
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
        if (batchEnabled) {
            this.batchEnabled = true;
            startBatchSender();
            return;
        }

        // Gate-2 finding, round 6: flush and the flag flip must be atomic against sendLog's own
        // dispatch decision, via the SAME batchModeLock sendLog holds -- see its own javadoc.
        // Before this lock, this.batchEnabled was written BEFORE flushLogs() drained the existing
        // queue, so a concurrent sendLog() call could read the new false value and send a NEWER
        // record immediately while flushLogs() was still draining OLDER queued records, arriving
        // at the panel out of order. Flushing while batchEnabled is STILL true is safe -- flushLogs
        // (via sendBatch) never reads batchEnabled at all, only externalDrainMode -- so any record
        // enqueued by a concurrent sendLog() during the flush (which the lock forces to happen
        // entirely before or entirely after this block, never during) is picked up by the SAME
        // ConcurrentLinkedQueue-backed flush that is already running, still in FIFO order.
        synchronized (batchModeLock) {
            // Gate-2 finding: cancelling the scheduled sender with entries still queued (fewer
            // than batchSize, so addToBatch's own size-threshold send never fired) used to strand
            // them -- new records after this point go out immediately (batching is now off), while
            // the older queued ones sat unsent until batching was re-enabled or shutdown() ran,
            // arriving late and out of order. Flush whatever is already queued BEFORE cancelling
            // its only consumer, so disabling batching means "deliver what's pending now, then send
            // immediately from here on" rather than "silently defer some records indefinitely."
            flushLogs();
            this.batchEnabled = false;
        }
        stopBatchSender();
    }

    /**
     * Sends the batched logs.
     * When externalDrainMode is true, only drops entries that exceed the queue cap and does not send.
     * <p>
     * Gate-2 finding (round 10): this method is ALSO reached directly, on the scheduler's own
     * thread, by the periodically-firing {@link #batchSenderTask}. Before this fix, that call was
     * completely unsynchronized -- a live {@code batchConfig.enabled: false} update could hold
     * {@link #batchModeLock} across its own {@link #flushLogs()} call while the scheduled task
     * fired concurrently on the scheduler's thread and called this method too, both polling the
     * SAME {@link #logQueue} and both calling {@code webSocketClient.sendMessage(...)}
     * independently -- interleaving queue polls between two frames with no ordering guarantee
     * between the two {@code sendMessage} calls. Synchronizing the whole body on
     * {@link #batchModeLock} serializes the scheduled tick against {@code setBatchEnabled(false)}'s
     * own flush, against {@link #flushLogs()} however else it is reached (it now also acquires
     * this same lock, see its own javadoc), and against {@code sendLog}'s own dispatch decision
     * (reentrant when this method is reached via {@code addToBatch}'s size-threshold trigger,
     * since that call chain already holds the lock). No new deadlock risk: this method never
     * acquires any OTHER lock, so it can only ever be the innermost link in any lock chain that
     * reaches it.
     */
    private void sendBatch() {
        synchronized (batchModeLock) {
            if (logQueue.isEmpty()) {
                return;
            }

            // Under external drain mode, only enforce queue-overflow protection (already handled
            // by addToBatch), do not send
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

                    // Gate-2 finding (round 6): this diagnostic USED to log via
                    // UltiTools.getInstance().getLogger() at Level.FINE. That logger is the shared
                    // PLUGIN logger (Bukkit's JavaPlugin#getLogger()), not a per-class logger named
                    // after this class -- so SystemLogHandler#shouldProcessRecord's class-name-based
                    // loop-prevention check (which matches on loggerName.contains("...")) could never
                    // catch it. Before this plan, that was harmless because the handler's own JUL
                    // level floor stayed at Level.INFO, silently dropping this FINE record before it
                    // ever reached shouldProcessRecord. #433/CR-02 (this same PR) made "debug"
                    // genuinely lower that floor to Level.FINEST -- so this record became reachable
                    // for the first time, and with batchConfig.size:1 it recursively re-triggered
                    // this very method (send -> log FINE -> SystemLogHandler -> sendLog -> addToBatch
                    // -> threshold reached -> sendBatch -> log FINE -> ...) until StackOverflowError.
                    // Removed rather than routed around the loop guard -- this line's information
                    // value (a batch-size count) does not justify carrying a self-recursion hazard.
                }

            } catch (Exception e) {
                System.err.println("[UltiPanel] 发送批量日志失败: " + e.getMessage());
            }
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
     * Sets the callback invoked when the queue reaches {@link #batchSize} while external drain
     * mode is active (Gate-2 finding, round 6). Pass {@code null} to clear it.
     *
     * @param callback a no-argument, non-blocking callback; called on whichever thread
     *        {@link #sendLog(String, String, String, Throwable)} happened to run on
     */
    public void setExternalSizeThresholdCallback(Runnable callback) {
        this.externalSizeThresholdCallback = callback;
    }

    /**
     * Sets the lock {@link #flushLogs()} coordinates disable-time flushes against (Gate-2
     * finding, round 9). Pass {@code null} to clear it (flushLogs runs unsynchronized again).
     *
     * @param lock any object usable as a monitor; the SAME instance must be used by whatever
     *        external code also drains this transmitter's queue (see this field's own javadoc)
     */
    public void setExternalDrainCoordinationLock(Object lock) {
        this.externalDrainCoordinationLock = lock;
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
     * <p>
     * Gate-2 finding (round 10): now ALSO acquires {@link #batchModeLock} as the OUTERMOST lock,
     * before the external coordination lock below -- {@link #sendBatch()} (which this method
     * reaches via {@link #doFlushLogs()}) is itself now synchronized on {@link #batchModeLock}
     * (see its own javadoc), so without this method also acquiring it first, a caller that had NOT
     * already taken {@link #batchModeLock} (e.g. {@link #shutdown()}, or {@link
     * com.ultikits.ultitools.handler.SystemLogHandler#flush()}/{@code close()}) would acquire the
     * coordination lock FIRST and only then try for {@link #batchModeLock} inside {@code
     * sendBatch()} -- the reverse of the order every other path in this class already establishes
     * ({@link #batchModeLock} before {@code logDrainLock}, see {@link
     * #setExternalDrainCoordinationLock(Object)}'s own javadoc), and a reverse-order acquisition
     * on two different threads is exactly how a lock-ordering deadlock happens. Taking {@link
     * #batchModeLock} first here keeps every acquisition path in this class consistent with that
     * one order, so this addition introduces no new deadlock risk -- verified by enumerating every
     * lock-acquiring path in this class and {@code ServerMonitorManager} (see this plan's gate
     * record).
     */
    public void flushLogs() {
        synchronized (batchModeLock) {
            // Gate-2 finding (round 9): coordinate with ServerMonitorManager's own drain paths,
            // when wired up, so a disable-time flush (setBatchEnabled(false)) cannot interleave
            // with either of the monitor's own drains and reorder the backlog -- see
            // externalDrainCoordinationLock's own javadoc.
            Object coordinationLock = externalDrainCoordinationLock;
            if (coordinationLock != null) {
                synchronized (coordinationLock) {
                    doFlushLogs();
                }
            } else {
                doFlushLogs();
            }
        }
    }

    private void doFlushLogs() {
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
