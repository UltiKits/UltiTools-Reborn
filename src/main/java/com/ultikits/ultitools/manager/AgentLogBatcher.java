package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * Buffers console log lines and releases them as protocol 2 {@code log.lines} frames.
 *
 * <p><b>Redaction.</b> This class never redacts. Its only producer must be the redacting builders
 * of this phase ({@code UltiPanelLogTransmitter#buildLogData}, the error-report collector's
 * serialisation and the player-event builders): they hand over text in which a sensitive command's
 * arguments are already replaced by {@code ***} (D-20). Two producers with two redaction paths
 * would let one of them miss, so the batcher takes already-redacted text and documents that
 * contract here instead of repeating the work.</p>
 *
 * <p><b>Threading.</b> {@link #offer} may be called from any thread, {@link #flush} from the
 * connection's scheduler and {@link #reset} from the session's close path; every public method is
 * safe to call concurrently and no method blocks on I/O beyond the channel's own send.</p>
 *
 * <p>Package-private: wired by the session plan (22-22), not part of the public API.</p>
 */
final class AgentLogBatcher {

    /** Protocol level names, in the order the contract's enum declares them. */
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_DEBUG = "DEBUG";

    /** A buffered line, kept unformatted so limits are applied when the flush sees them. */
    private static final class Line {
        private final long timestamp;
        private final Level level;
        private final String message;

        private Line(long timestamp, Level level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }

    private final LongSupplier clock;
    private final ArrayDeque<Line> buffered = new ArrayDeque<>();
    private CloudLimits limits = CloudLimits.defaults();

    /**
     * Creates a batcher.
     *
     * @param clock the clock whose milliseconds drive the rate bucket; the same unit as the
     *              {@code ts} values passed to {@link #offer}, so a test can fake it
     */
    AgentLogBatcher(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Offers one console line. Never throws: a line that cannot be admitted is dropped.
     *
     * @param ts             the line's timestamp in milliseconds
     * @param level          the JUL level; {@code null} counts as {@link Level#INFO}
     * @param redactedMessage the text with sensitive command arguments already replaced by
     *                        {@code ***}; {@code null} counts as an empty message
     */
    void offer(long ts, Level level, String redactedMessage) {
        Level effective = level == null ? Level.INFO : level;
        String message = redactedMessage == null ? "" : redactedMessage;
        synchronized (this) {
            buffered.addLast(new Line(ts, effective, message));
        }
    }

    /**
     * Sends the buffered lines as one {@code log.lines} frame, if the channel can take it.
     *
     * <p>Limits are read from {@code channel.limits()} on every flush, because a reconnect's new
     * welcome may negotiate different ones. Nothing is sent while the channel is closed or not yet
     * open; the lines stay buffered for the next open channel.</p>
     *
     * @param channel the connection to send on; {@code null} is ignored
     */
    void flush(CloudChannel channel) {
        if (channel == null) {
            return;
        }
        synchronized (this) {
            limits = channel.limits();
            if (!channel.isOpen() || buffered.isEmpty()) {
                return;
            }
            JsonArray lines = new JsonArray();
            int maxLines = limits.logBatchMaxLines();
            while (!buffered.isEmpty() && lines.size() < maxLines) {
                Line line = buffered.removeFirst();
                lines.add(lineObject(line.timestamp, levelName(line.level), line.message));
            }
            if (lines.size() == 0) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.add("lines", lines);
            channel.send("log.lines", payload);
        }
    }

    /**
     * Forgets every buffered line. Called when the session closes.
     */
    void reset() {
        synchronized (this) {
            buffered.clear();
        }
    }

    /**
     * Maps a JUL level onto the contract's four names by thresholds, so custom levels map the same
     * way the built-in ones do: {@code >= 1000} ERROR ({@link Level#SEVERE}), {@code >= 900} WARN
     * ({@link Level#WARNING}), {@code >= 700} INFO ({@link Level#INFO} and {@link Level#CONFIG}),
     * anything lower DEBUG ({@link Level#FINE} and below).
     *
     * @param level the JUL level, never {@code null}
     * @return the contract level name
     */
    private static String levelName(Level level) {
        int value = level.intValue();
        if (value >= 1000) {
            return LEVEL_ERROR;
        }
        if (value >= 900) {
            return LEVEL_WARN;
        }
        if (value >= 700) {
            return LEVEL_INFO;
        }
        return LEVEL_DEBUG;
    }

    /**
     * Builds one {@code log.lines} line object: exactly {@code ts}, {@code level} and
     * {@code message}, which is all the schema allows.
     *
     * @param ts      the line's timestamp
     * @param level   the contract level name
     * @param message the redacted text
     * @return the line object
     */
    private static JsonObject lineObject(long ts, String level, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ts", ts);
        object.addProperty("level", level);
        object.addProperty("message", message);
        return object;
    }
}
