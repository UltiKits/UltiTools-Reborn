package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Buffers console log lines and releases them as protocol 2 {@code log.lines} frames, within the
 * contract's per-line, per-frame and rate limits.
 *
 * <p><b>Redaction.</b> This class never redacts. Its only producer must be the redacting builders
 * of this phase ({@code UltiPanelLogTransmitter#buildLogData}, the error-report collector's
 * serialisation and the player-event builders): they hand over text in which a sensitive command's
 * arguments are already replaced by {@code ***} (D-20). Two producers with two redaction paths
 * would let one of them miss, so the batcher takes already-redacted text and states that contract
 * here instead of repeating the work.</p>
 *
 * <p><b>Limits.</b> Every limit comes from {@link CloudChannel#limits()} on each flush, because a
 * reconnect negotiates a fresh welcome. {@link #offer} has no channel, so it applies the limits of
 * the last flush and, before the first one, the contract's own defaults (which are the values an
 * unnegotiated connection uses too). Nothing about a local drop is ever reported to the gateway:
 * the contract has no such message (S-56), so the gateway tells the browser about gaps itself. Only
 * a local FINE line summarises what was dropped, at most once a minute.</p>
 *
 * <p><b>Threading.</b> {@link #offer} may be called from any thread, {@link #flush} from the
 * connection's scheduler and {@link #reset} from the session's close path; every method is safe to
 * call concurrently and nothing blocks on I/O beyond the channel's own send.</p>
 *
 * <p>Package-private: wired by the session plan (22-22), not part of the public API.</p>
 */
final class AgentLogBatcher {

    /** Appended to a message that had to be cut, so a reader knows the line is incomplete. */
    static final String TRUNCATION_SUFFIX = "…[truncated]";

    /** The protocol level names. */
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_DEBUG = "DEBUG";

    /** Runs the network call and the socket's state machine; never re-enters this batcher. */
    private static final Logger LOGGER = Logger.getLogger(AgentLogBatcher.class.getName());

    /** Local diagnostics only: how often the drop summary may be written. */
    private static final long DROP_REPORT_INTERVAL_MS = 60_000L;

    /**
     * UTF-8 bytes the channel's envelope adds around {@code payload.lines}: {@code v}, a 26-character
     * id, the type, a 16-digit timestamp and the {@code {"lines":[]}} wrapper. The real id is 26
     * characters and a real timestamp 13 digits, so this is an upper bound.
     */
    private static final int FRAME_OVERHEAD_BYTES = frameOverheadBytes();

    /** A buffered line, kept unformatted so limits are applied when the flush reads them. */
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
    private double tokens = CloudLimits.defaults().logBurst();
    private long lastRefillAt = Long.MIN_VALUE;
    private long dropped;
    private long dropsSinceReport;
    private long lastDropReportAt = -1L;

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
     * Offers one console line. Never throws.
     *
     * <p>A line over the token bucket's limit is dropped and counted; so is the oldest buffered
     * line when the buffer would exceed one burst (a closed channel that keeps receiving lines must
     * not grow the heap).</p>
     *
     * @param ts              the line's timestamp in milliseconds
     * @param level           the JUL level; {@code null} counts as {@link Level#INFO}
     * @param redactedMessage the text with sensitive command arguments already replaced by
     *                        {@code ***}; {@code null} counts as an empty message
     */
    void offer(long ts, Level level, String redactedMessage) {
        Level effective = level == null ? Level.INFO : level;
        String message = redactedMessage == null ? "" : redactedMessage;
        long now = clock.getAsLong();
        synchronized (this) {
            refill(now);
            if (tokens < 1.0) {
                countDrop();
            } else {
                tokens -= 1.0;
                buffered.addLast(new Line(ts, effective, message));
                trimToCapacity();
            }
        }
    }

    /**
     * Sends the buffered lines as one {@code log.lines} frame, if the channel can take it.
     *
     * <p>Limits are read from {@code channel.limits()} on every flush, because a reconnect's new
     * welcome may negotiate different ones. Nothing is sent while the channel is closed; the lines
     * stay buffered for the next open channel, and a frame the socket refuses is put back for the
     * next attempt.</p>
     *
     * @param channel the connection to send on; {@code null} is ignored
     */
    void flush(CloudChannel channel) {
        if (channel == null) {
            return;
        }
        long now = clock.getAsLong();
        synchronized (this) {
            refill(now);
            CloudLimits next = channel.limits();
            if (next != null) {
                limits = next;
                if (tokens > limits.logBurst()) {
                    tokens = limits.logBurst();
                }
            }
            if (!channel.isOpen() || buffered.isEmpty()) {
                reportDrops(now);
                return;
            }
            JsonArray lines = new JsonArray();
            List<Line> taken = new ArrayList<>();
            long budget = (long) limits.maxFrameBytes() - FRAME_OVERHEAD_BYTES;
            long payloadBytes = 0;
            int maxLines = limits.logBatchMaxLines();
            int lineMaxBytes = limits.logLineMaxBytes();
            while (!buffered.isEmpty() && taken.size() < maxLines) {
                Line line = buffered.peekFirst();
                String levelName = levelName(line.level);
                String message = truncate(line.timestamp, levelName, line.message, lineMaxBytes);
                JsonObject object = lineObject(line.timestamp, levelName, message);
                long candidate = payloadBytes + utf8Bytes(object.toString()) + (taken.isEmpty() ? 0L : 1L);
                if (candidate > budget) {
                    if (!taken.isEmpty()) {
                        // The frame is full; the rest belongs to the next flush.
                        break;
                    }
                    // A single line larger than a frame can ever be: drop it rather than send an
                    // oversized frame the gateway would reject.
                    buffered.removeFirst();
                    countDrop();
                    continue;
                }
                buffered.removeFirst();
                taken.add(line);
                lines.add(object);
                payloadBytes = candidate;
            }
            if (taken.isEmpty()) {
                reportDrops(now);
                return;
            }
            JsonObject payload = new JsonObject();
            payload.add("lines", lines);
            if (!channel.send("log.lines", payload)) {
                for (int index = taken.size() - 1; index >= 0; index--) {
                    buffered.addFirst(taken.get(index));
                }
                trimToCapacity();
            }
            reportDrops(now);
        }
    }

    /**
     * Forgets every buffered line, refills the rate bucket and zeroes the local drop counters.
     * Called when the session closes: the next session starts clean.
     */
    void reset() {
        long now = clock.getAsLong();
        synchronized (this) {
            buffered.clear();
            tokens = limits.logBurst();
            lastRefillAt = now;
            dropped = 0;
            dropsSinceReport = 0;
            lastDropReportAt = -1L;
        }
    }

    /**
     * Reports how many lines were dropped locally, by the rate bucket or by the bounded buffer.
     * Nothing of this reaches the gateway.
     *
     * @return the count since the last {@link #reset()}
     */
    long droppedByRateLimit() {
        synchronized (this) {
            return dropped;
        }
    }

    /**
     * Adds the tokens earned since the last call, capped at the burst the current limits allow.
     *
     * @param now the current clock value in milliseconds
     */
    private void refill(long now) {
        if (lastRefillAt == Long.MIN_VALUE) {
            lastRefillAt = now;
            return;
        }
        if (now <= lastRefillAt) {
            return;
        }
        long elapsed = now - lastRefillAt;
        lastRefillAt = now;
        double earned = (elapsed * (double) limits.logRatePerSec()) / 1000.0;
        tokens = Math.min((double) limits.logBurst(), tokens + earned);
    }

    /** Drops the oldest buffered lines until the buffer fits one burst. */
    private void trimToCapacity() {
        int capacity = limits.logBurst();
        while (buffered.size() > capacity) {
            buffered.removeFirst();
            countDrop();
        }
    }

    private void countDrop() {
        dropped++;
        dropsSinceReport++;
    }

    /**
     * Writes the local drop summary, at most once per interval and only when something was dropped
     * since the last one. Called from {@link #flush}, the once-a-second tick, so a burst is
     * summarised by its total rather than by its first line. The count is the cumulative number
     * dropped since the last {@link #reset()}, the same figure {@link #droppedByRateLimit()}
     * reports. FINE on the batcher's own logger: it is a diagnostic for the operator and must not
     * depend on the connection it describes.
     *
     * @param now the current clock value in milliseconds
     */
    private void reportDrops(long now) {
        if (dropsSinceReport == 0) {
            return;
        }
        if (lastDropReportAt >= 0 && now - lastDropReportAt < DROP_REPORT_INTERVAL_MS) {
            return;
        }
        dropsSinceReport = 0;
        lastDropReportAt = now;
        LOGGER.fine(String.format(Locale.ROOT,
                "UltiCloud: dropped %d log line(s) locally: log rate limit %d/s with a burst of %d.",
                dropped, limits.logRatePerSec(), limits.logBurst()));
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
     * Cuts a message so its serialised line fits {@code lineMaxBytes}, appending
     * {@link #TRUNCATION_SUFFIX}. The cut lands on a code-point boundary, so a surrogate pair is
     * never split into an unpaired half.
     *
     * @param ts           the line's timestamp, whose digits are part of the measured size
     * @param levelName    the contract level name
     * @param message      the full redacted message
     * @param lineMaxBytes the largest UTF-8 size the serialised line object may have
     * @return the message unchanged when it fits, otherwise a cut message ending in the suffix
     */
    private static String truncate(long ts, String levelName, String message, int lineMaxBytes) {
        if (utf8Bytes(lineObject(ts, levelName, message).toString()) <= lineMaxBytes) {
            return message;
        }
        int codePoints = message.codePointCount(0, message.length());
        int best = -1;
        int low = 0;
        int high = codePoints;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = prefix(message, middle) + TRUNCATION_SUFFIX;
            if (utf8Bytes(lineObject(ts, levelName, candidate).toString()) <= lineMaxBytes) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return prefix(message, Math.max(best, 0)) + TRUNCATION_SUFFIX;
    }

    /**
     * Returns the first {@code codePoints} characters of {@code text}, counted in code points so a
     * surrogate pair is either fully in or fully out.
     *
     * @param text       the text
     * @param codePoints how many code points to keep
     * @return the prefix
     */
    private static String prefix(String text, int codePoints) {
        if (codePoints <= 0) {
            return "";
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
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

    /**
     * Measures the envelope the channel adds around the payload, so a frame is never built larger
     * than {@code maxFrameBytes} before the channel sees it.
     *
     * @return the upper-bound UTF-8 size of the frame without its lines
     */
    private static int frameOverheadBytes() {
        JsonObject skeleton = new JsonObject();
        skeleton.addProperty("v", 2);
        // The contract's id is a 26-character ULID; digits keep this a valid-length placeholder.
        skeleton.addProperty("id", "00000000000000000000000000");
        skeleton.addProperty("type", "log.lines");
        // The widest timestamp the schema admits, so the measured size is an upper bound.
        skeleton.addProperty("ts", 8640000000000000L);
        JsonObject payload = new JsonObject();
        payload.add("lines", new JsonArray());
        skeleton.add("payload", payload);
        return utf8Bytes(skeleton.toString());
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
