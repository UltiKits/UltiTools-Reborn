package com.ultikits.ultitools.manager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

import lombok.Getter;
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

    /**
     * Where the v2 connection takes each redacted line (plan 22-22). {@link #sendLog} and {@link #replayLog} hand
     * every entry {@link #buildLogData} built to this sink and nothing else: no queue, batch or frame is ever built.
     */
    private final AgentLineSink agentSink;
    private final AtomicBoolean logTransmissionEnabled = new AtomicBoolean(true);

    /** Makes {@link #takePending()} and {@link #adoptPending(JsonArray)} atomic with respect to each other. */
    private final Object batchModeLock = new Object();

    // External drain mode: when true, sendBatch() no longer sends automatically, and logs are
    // obtained externally by calling drainQueue()
    private final AtomicBoolean externalDrainMode = new AtomicBoolean(false);

    // Batch-send configuration. All three are written from the panel's WebSocket receive thread
    // (setBatchEnabled/setBatchSize/setIntervalMs, reachable live over the panel's `config`
    // action) and read without further synchronization from the logging thread (addToBatch's
    // logQueue.size() >= batchSize check) and the batch scheduler thread (sendBatch's own
    // for (int i = 0; i < batchSize ...) loop) -- volatile, like the two neighbouring
    // cross-thread fields above (externalSizeThresholdCallback, externalDrainCoordinationLock),
    // is required for the writer's value to ever become visible to those readers (Gate-2
    // finding, review round 12, pull request #467). Structural regression test:
    // UltiPanelLogTransmitterBatchFieldVolatilityInvariantTest.
    @Getter
    private volatile boolean batchEnabled = true; // setter below (#432) -- starts/stops the scheduled sender
    @Getter
    private volatile int batchSize = 10; // setter below (Gate-2) -- rejects a value below 1
    @Getter
    private volatile int intervalMs = 5000; // 5-second interval; setter below (#432) reschedules the sender

    // Batch-send queue and scheduler
    private final ConcurrentLinkedQueue<JsonObject> logQueue;

    /**
     * The one batch whose send failed, kept to be sent before anything newer (#486). Before 6.3.0
     * a batch was polled off {@link #logQueue} and then sent, and nothing put it back when the send
     * failed, so a socket that closed around the send lost the batch without a trace. While a
     * batch is held, neither this class's own sender nor an external drain ({@link #drainQueue})
     * takes a newer record, so the panel still receives records in order. At most one batch is
     * held, so a permanently failing send retries once per attempt and never spins, and the held
     * records count against nothing else (the queue stays bounded by {@link #MAX_QUEUE_SIZE}).
     * <p>
     * A failed send is one that throws or after which the client no longer reports itself
     * connected (the closed-socket path of {@code UltiPanelWebSocketClient#sendMessage} returns
     * without sending). Whether the panel processed a frame that was sent cannot be known at this
     * layer, so delivery stays best effort: a batch whose connection drops just after it was
     * written may be sent again after reconnecting.
     */
    private final AtomicReference<JsonArray> heldBatch = new AtomicReference<>();

    /**
     * The transmitter of the v2 connection (plan 22-22): every entry goes to {@code sink}, already redacted by
     * {@link #buildLogData}. No batch sender is started and no v1 client is held.
     *
     * @param sink receives each line; called on the logging thread, so it must not block
     */
    UltiPanelLogTransmitter(AgentLineSink sink) {
        this.agentSink = java.util.Objects.requireNonNull(sink, "sink");
        this.logQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Receives one redacted console line for the v2 {@code log.lines} stream (plan 22-22).
     */
    interface AgentLineSink {
        /**
         * @param timestamp the line's time in milliseconds since the epoch
         * @param level     the line's level
         * @param message   the redacted message, with the redacted stack trace appended on the following lines
         */
        void offer(long timestamp, Level level, String message);

        /**
         * Asked before a line is built: while it answers {@code false} the transmitter neither redacts nor hands over
         * anything, so a connection that does not take lines costs a logging thread nothing (22-22 fix).
         *
         * @return whether a line offered now would be sent
         */
        default boolean accepting() {
            return true;
        }
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
        offerToAgent(level, message, source, throwable, System.currentTimeMillis());
    }

    /**
     * Builds one log entry as the panel receives it.
     * <p>
     * This is the only place a cloud log entry is built, for the live stream ({@link #sendLog}) and
     * for the start-up replay ({@link #replayLog}) alike, so the message and the stack trace are
     * passed through {@link SensitiveCommandRegistry#redactText} here and no path can bypass it. The
     * replay builds its entries when the stream opens, so a command a module registers while it
     * enables also covers lines captured before it enabled. Only this copy is redacted: the
     * {@link java.util.logging.LogRecord}, the local console and the log files keep the original.
     *
     * @param level     the log level, {@code info} when blank
     * @param message   the message
     * @param source    the log source, {@code server} when blank
     * @param throwable the exception, or {@code null}
     * @param timestamp the entry's time, in milliseconds since the epoch
     * @return the entry
     */
    private JsonObject buildLogData(String level, String message, String source, Throwable throwable,
                                    long timestamp) {
        String logLevel = level == null || level.trim().isEmpty() ? "info" : level;
        String logSource = source == null || source.trim().isEmpty() ? "server" : source;
        JsonObject logData = new JsonObject();
        logData.addProperty("level", logLevel);
        logData.addProperty("message", redact(message != null ? message : ""));
        logData.addProperty("timestamp", timestamp);
        logData.addProperty("source", logSource);
        logData.addProperty("thread", Thread.currentThread().getName());

        // Add the logger name (optional)
        logData.addProperty("logger", determineLoggerName(logSource));

        // If there is an exception, add the stack trace
        if (throwable != null) {
            logData.addProperty("stackTrace", redact(getStackTrace(throwable)));
        } else {
            logData.add("stackTrace", null);
        }
        return logData;
    }

    /**
     * Hands one line to the v2 sink: the message and stack trace {@link #buildLogData} redacted, the stack trace
     * appended to the message because {@code log.lines} has no stack-trace field. Never throws: a log line must not
     * break the logging call that produced it.
     */
    private void offerToAgent(String level, String message, String source, Throwable throwable, long timestamp) {
        if (!logTransmissionEnabled.get() || !agentSink.accepting()) {
            return;
        }
        try {
            JsonObject entry = buildLogData(level, message, source, throwable, timestamp);
            String text = entry.get("message").getAsString();
            JsonElement stackTrace = entry.get("stackTrace");
            if (stackTrace != null && !stackTrace.isJsonNull()) {
                text = text + "\n" + stackTrace.getAsString();
            }
            agentSink.offer(timestamp, julLevel(entry.get("level").getAsString()), text);
        } catch (RuntimeException failure) {
            PanelConnectionLog.log(Level.FINE, "[UltiPanel] A log line could not be handed to UltiCloud: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** The JUL level of a panel level name, as {@code SystemLogHandler#mapLogLevel} produced it. */
    private static Level julLevel(String level) {
        switch (level.toLowerCase(java.util.Locale.ROOT)) {
            case "error":
            case "severe":
                return Level.SEVERE;
            case "warning":
            case "warn":
                return Level.WARNING;
            case "debug":
                return Level.FINE;
            default:
                return Level.INFO;
        }
    }

    /**
     * Redacts registered commands' arguments from text bound for the panel. Without a framework
     * instance nothing can have been registered, so the text is returned unchanged.
     */
    private static String redact(String text) {
        UltiTools instance = UltiTools.getInstance();
        SensitiveCommandRegistry registry = instance == null ? null : instance.getSensitiveCommandRegistry();
        return registry == null ? text : registry.redactText(text);
    }

    /**
     * Queues one start-up record the early capture replays (#487) for delivery in batches.
     * <p>
     * Queuing is an in-memory hand-off and does not depend on the connection: the capture has
     * already given the record up, so a socket that closes while the capture is still handing its
     * records over (#677) must not lose the rest. Nothing is sent here; {@link #startReplay()}'s
     * sender sends only while the client reports itself connected, and records still queued when
     * the panel reconnects on a new client are handed to the next transmitter
     * ({@link #takePending()}). The transmission switch still applies -- nothing is queued while
     * transmission is off, or when there is no client -- and the queue holds at most what
     * {@code EarlyLogCapture} kept: at most 2,000 records and about 512 KiB, and nothing once it has
     * waited five minutes for the stream.
     *
     * @param level     the log level (info, warning, error, debug)
     * @param message   the log message
     * @param source    the log source (e.g.: "server", "plugin:name")
     * @param throwable the exception object (optional)
     * @param timestamp when the record was logged -- kept, so the panel can place a replayed line
     *                  among the live ones that may arrive while the replay is still being sent
     */
    public void replayLog(String level, String message, String source, Throwable throwable, long timestamp) {
        offerToAgent(level, message, source, throwable, timestamp);
    }

    /**
     * Does nothing as of 6.4.0: {@link #replayLog} hands every replayed record to the sink at once,
     * so no replay is ever queued. The protocol v1 replay sender this started was removed with the
     * v1 client.
     */
    public void startReplay() {
        // Nothing to replay: every replayed line goes straight to the sink (see replayLog).
    }

    /**
     * How many replayed records are still waiting to be sent.
     *
     * @return the number of replayed records not yet delivered
     */
    public int getReplayQueueSize() {
        return 0;
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
        this.intervalMs = intervalMs;
    }

    /**
     * Sets the batch send-threshold size, rejecting anything below 1 (Gate-2 finding). A value of
     * zero or negative would make the former v1 sender's own {@code for (int i = 0; i < batchSize
     * ...)} loop consume nothing on every scheduled run, while its
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
        this.batchEnabled = batchEnabled;
    }

    /**
     * Drains log entries from the queue, returning them as a JsonArray.
     * For external callers to use (e.g. ServerMonitorManager's batch_update).
     *
     * @param maxItems the maximum number of entries to take
     * @return a JsonArray of log entries
     */
    public JsonArray drainQueue(int maxItems) {
        // #486: a batch an external sender could not deliver is handed back first, whole, and
        // nothing newer is taken with it, so the next frame repeats it in order.
        JsonArray held = heldBatch.getAndSet(null);
        if (held != null) {
            return held;
        }
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
     * Gives back a batch an external drain took with {@link #drainQueue(int)} but could not send,
     * so the next {@link #drainQueue(int)} -- or this class's own sender -- sends it before any
     * newer record (#486). {@code ServerMonitorManager} calls this when its {@code batch_update}
     * frame carrying these records failed.
     *
     * @param logs the undelivered records, oldest first; ignored when {@code null} or empty
     * @since 6.3.0
     */
    public void holdUndelivered(JsonArray logs) {
        if (logs == null || logs.size() == 0) {
            return;
        }
        heldBatch.accumulateAndGet(logs, (existing, undelivered) -> {
            if (existing == null) {
                return undelivered;
            }
            // Both were drained earlier than anything still queued; the ones just handed back
            // were taken first, so they lead.
            JsonArray merged = new JsonArray();
            merged.addAll(undelivered);
            merged.addAll(existing);
            return merged;
        });
    }

    /**
     * Removes and returns every record this transmitter has not sent -- replayed start-up records
     * not yet delivered first, then the held batch, then the queue, oldest first -- so
     * {@code LogStreamManager} can hand them to the transmitter that replaces this one when the
     * panel reconnects on a new client (#486). Call it after
     * {@link #shutdown()}, whose flush sends what the old connection still can.
     *
     * @return the unsent records, oldest first; empty when there are none
     * @since 6.3.0
     */
    public JsonArray takePending() {
        synchronized (batchModeLock) {
            JsonArray pending = new JsonArray();
            JsonArray held = heldBatch.getAndSet(null);
            if (held != null) {
                pending.addAll(held);
            }
            JsonObject log;
            while ((log = logQueue.poll()) != null) {
                pending.add(log);
            }
            return pending;
        }
    }

    /**
     * Queues records a replaced transmitter could not send ({@link #takePending()}) ahead of anything
     * this one logs afterwards, so they reach the panel first and in order (#486). The queue's own
     * bound applies: beyond {@link #MAX_QUEUE_SIZE} the oldest are discarded and counted.
     *
     * @param pending the records, oldest first; ignored when {@code null}
     * @since 6.3.0
     */
    public void adoptPending(JsonArray pending) {
        if (pending == null) {
            return;
        }
        synchronized (batchModeLock) {
            for (JsonElement record : pending) {
                while (logQueue.size() >= MAX_QUEUE_SIZE) {
                    logQueue.poll();
                }
                logQueue.offer(record.getAsJsonObject());
            }
        }
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
        // Nothing reaches a size threshold any more: no line is queued by this transmitter itself.
    }

    /**
     * Sets the lock {@link #flushLogs()} coordinates disable-time flushes against (Gate-2
     * finding, round 9). Pass {@code null} to clear it (flushLogs runs unsynchronized again).
     *
     * @param lock any object usable as a monitor; the SAME instance must be used by whatever
     *        external code also drains this transmitter's queue (see this field's own javadoc)
     */
    public void setExternalDrainCoordinationLock(Object lock) {
        // Nothing is sent by this transmitter itself, so there is no drain to coordinate with.
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
     * Enables/disables log transmission.
     */
    public void setLogTransmissionEnabled(boolean enabled) {
        this.logTransmissionEnabled.set(enabled);

        if (enabled) {
            PanelConnectionLog.log(Level.INFO, FrameworkText.text("[UltiPanel] 日志传输已启用"));
        } else {
            PanelConnectionLog.log(Level.INFO, FrameworkText.text("[UltiPanel] 日志传输已禁用"));
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
     * Does nothing as of 6.4.0: every line went to the sink when it was logged, and the protocol
     * v1 sender that sent the queue was removed with the v1 client.
     */
    public void flushLogs() {
        // Nothing to flush: every line went straight to the sink when it was logged.
    }

    /**
     * Shuts down the log transmitter.
     */
    public void shutdown() {
        logTransmissionEnabled.set(false);
        PanelConnectionLog.log(Level.INFO, FrameworkText.text("[UltiPanel] 日志传输器已关闭"));
    }
}
