package com.ultikits.ultitools.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * Holds the log records written between {@code UltiTools#onLoad()} and the moment the panel's log
 * stream starts ({@code LogStreamManager#initialize}), so they reach the panel too (#487).
 * <p>
 * Before 6.3.0 the stream's capture was attached inside {@code initialize()}, which runs only once
 * the panel connection is open, so everything the server logged before it -- module loading,
 * dependency resolution, the framework's own start-up diagnostics -- never reached the panel. This
 * handler is attached to the {@code java.util.logging} root logger in {@code onLoad()}; when the
 * stream starts, {@link #drainInto} replays what it kept, oldest first, attaches the live handler,
 * and detaches itself. {@code LogStreamManager} replays into the stream handler's replay path, so
 * the transmitter delivers the early records in batches of the configured batch size, one per batch
 * interval, even with live batching off (as of 6.3.0); a live record can therefore arrive before the
 * last early batches.
 * <p>
 * The buffer is bounded three ways, so a server that never connects to the panel cannot grow it:
 * by record count, by an estimate of the bytes it holds, and by time -- after
 * {@link #RELEASE_AFTER_MS} without the stream starting it detaches itself and keeps nothing. A
 * record past the count or byte bound is not kept and is counted; {@link #drainInto} returns the
 * count. It applies the live stream's default filters as records arrive (level {@code INFO} and
 * above, the configured {@code excluded-loggers}), so it never holds a record the stream would not
 * send; the live handler applies the current configuration again when the records are replayed.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class EarlyLogCapture extends Handler {

    /** Most records kept. */
    static final int MAX_RECORDS = 2000;

    /** Most bytes kept, estimated from each record's message, logger name and exception. */
    static final long MAX_BYTES = 512L * 1024L;

    /** How long the capture waits for the stream to start before it releases what it holds. */
    public static final long RELEASE_AFTER_MS = TimeUnit.MINUTES.toMillis(5);

    /** Charged per record carrying an exception, whose stack trace the stream will render. */
    private static final int THROWN_ESTIMATE_BYTES = 2048;

    private static final Object LOCK = new Object();

    /** The capture attached to the root logger, or {@code null}. Guarded by {@link #LOCK}. */
    private static EarlyLogCapture active;

    private final Deque<LogRecord> records = new ArrayDeque<>();
    private final List<String> excludedLoggers;
    private final int maxRecords;
    private final long maxBytes;
    private final long deadline;
    private final LongSupplier clock;
    private long bytes;
    private int dropped;
    private boolean draining;

    private EarlyLogCapture(Collection<String> excludedLoggers, int maxRecords, long maxBytes, long releaseAfterMs,
                            LongSupplier clock) {
        this.excludedLoggers = new ArrayList<>(excludedLoggers);
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.deadline = clock.getAsLong() + releaseAfterMs;
    }

    /**
     * Attaches the capture to the root logger, replacing one already attached, whatever the logs
     * capability says; {@code UltiTools#onLoad()} goes through {@link #startIfLogsEnabled}.
     *
     * @param excludedLoggers the configured {@code ultipanel.logging.excluded-loggers}, applied as
     *                        records arrive
     */
    public static void start(Collection<String> excludedLoggers) {
        start(excludedLoggers, MAX_RECORDS, MAX_BYTES, RELEASE_AFTER_MS, System::currentTimeMillis);
    }

    /**
     * Attaches the capture only when the panel receives the log stream: with
     * {@code ultipanel.capabilities.logs: false} nothing is collected, as a disabled capability
     * prevents collection rather than collecting and discarding (D-12). Called from
     * {@code UltiTools#onLoad()}, after the plugin instance is set, so the configured value is read.
     *
     * @param excludedLoggers the configured {@code ultipanel.logging.excluded-loggers}, applied as
     *                        records arrive
     * @return whether the capture was attached
     */
    public static boolean startIfLogsEnabled(Collection<String> excludedLoggers) {
        if (!Capability.LOGS.isEnabled()) {
            return false;
        }
        start(excludedLoggers);
        return true;
    }

    static void start(Collection<String> excludedLoggers, int maxRecords, long maxBytes, long releaseAfterMs,
                      LongSupplier clock) {
        EarlyLogCapture capture = new EarlyLogCapture(excludedLoggers, maxRecords, maxBytes, releaseAfterMs, clock);
        capture.setLevel(Level.INFO);
        synchronized (LOCK) {
            detachActive();
            active = capture;
            Logger.getLogger("").addHandler(capture);
        }
    }

    /**
     * Replays every record the capture kept to {@code live}, oldest first, then runs
     * {@code attachLive} (which attaches the live handler to the root logger) and detaches the
     * capture. Records logged while the replay runs are kept and replayed too, before the live
     * handler is attached. When no capture is attached -- a reconnect after the first stream, or a
     * capture already released -- this only runs {@code attachLive}.
     *
     * @param live       the live stream's handler
     * @param attachLive attaches {@code live} to the root logger
     * @return how many records the capture could not keep because a bound was reached
     */
    public static int drainInto(Handler live, Runnable attachLive) {
        EarlyLogCapture capture;
        List<LogRecord> batch;
        synchronized (LOCK) {
            capture = active;
            if (capture == null || capture.expired()) {
                detachActive();
                attachLive.run();
                return 0;
            }
            capture.draining = true;
            batch = capture.takeAll();
        }
        while (true) {
            // Replayed outside the lock: the live handler sends through the panel connection, and
            // a thread logging meanwhile only has to wait for the lock long enough to append.
            for (LogRecord record : batch) {
                live.publish(record);
            }
            synchronized (LOCK) {
                batch = capture.takeAll();
                if (batch.isEmpty()) {
                    attachLive.run();
                    detachActive();
                    return capture.dropped;
                }
            }
        }
    }

    /**
     * Detaches the capture and discards what it holds -- when the stream cannot start (the
     * {@code logs} capability is off, or the framework is disabling) or its time is up.
     */
    public static void release() {
        synchronized (LOCK) {
            detachActive();
        }
    }

    /**
     * Releases the capture if the stream did not start within {@link #RELEASE_AFTER_MS}. Scheduled
     * once from {@code UltiTools#onEnable()} so a quiet server does not wait for its next log line.
     */
    public static void releaseIfExpired() {
        synchronized (LOCK) {
            if (active != null && !active.draining && active.expired()) {
                detachActive();
            }
        }
    }

    private static void detachActive() {
        if (active != null) {
            Logger.getLogger("").removeHandler(active);
            active.records.clear();
            active.bytes = 0;
            active = null;
        }
    }

    @Override
    public void publish(LogRecord record) {
        // A panel-connection line is never sent to the panel, so it is not kept for it either.
        if (record == null || !isLoggable(record) || isExcluded(record.getLoggerName())
                || PanelConnectionLog.isPanelConnectionRecord(record)) {
            return;
        }
        synchronized (LOCK) {
            if (active != this) {
                return;
            }
            if (!draining && expired()) {
                detachActive();
                return;
            }
            long size = estimateBytes(record);
            if (records.size() >= maxRecords || bytes + size > maxBytes) {
                dropped++;
                return;
            }
            records.addLast(record);
            bytes += size;
        }
    }

    private boolean expired() {
        return clock.getAsLong() > deadline;
    }

    private boolean isExcluded(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        for (String excluded : excludedLoggers) {
            if (loggerName.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    private List<LogRecord> takeAll() {
        List<LogRecord> taken = new ArrayList<>(records);
        records.clear();
        bytes = 0;
        return taken;
    }

    private static long estimateBytes(LogRecord record) {
        long size = 64;
        if (record.getMessage() != null) {
            size += 2L * record.getMessage().length();
        }
        if (record.getLoggerName() != null) {
            size += 2L * record.getLoggerName().length();
        }
        if (record.getThrown() != null) {
            size += THROWN_ESTIMATE_BYTES;
        }
        return size;
    }

    @Override
    public void flush() {
        // Nothing is written anywhere until drainInto replays the records.
    }

    @Override
    public void close() {
        release();
    }
}
