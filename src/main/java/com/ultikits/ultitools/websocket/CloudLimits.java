package com.ultikits.ultitools.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Immutable limits from one accepted welcome; internal, not for module use.
 * Values follow the frozen protocol contract's bounds and are rejected, not clamped,
 * when invalid. A new connection may negotiate a different snapshot.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class CloudLimits {
    private final int maxFrameBytes;
    private final int logRatePerSec;
    private final int logBurst;
    private final int logLineMaxBytes;
    private final int logBatchMaxLines;
    private final int outboxMax;
    private final int commandDedupeMax;
    private final int commandDedupeTtlSeconds;
    private final int commandOutputMaxBytes;
    private final int fileMaxBytes;

    private CloudLimits(int maxFrameBytes, int logRatePerSec, int logBurst, int logLineMaxBytes, int logBatchMaxLines,
                        int outboxMax, int commandDedupeMax, int commandDedupeTtlSeconds, int commandOutputMaxBytes, int fileMaxBytes) {
        this.maxFrameBytes = maxFrameBytes;
        this.logRatePerSec = logRatePerSec;
        this.logBurst = logBurst;
        this.logLineMaxBytes = logLineMaxBytes;
        this.logBatchMaxLines = logBatchMaxLines;
        this.outboxMax = outboxMax;
        this.commandDedupeMax = commandDedupeMax;
        this.commandDedupeTtlSeconds = commandDedupeTtlSeconds;
        this.commandOutputMaxBytes = commandOutputMaxBytes;
        this.fileMaxBytes = fileMaxBytes;
    }

    /** @return the frozen protocol's DEFAULT_LIMITS, used before welcome */
    public static CloudLimits defaults() {
        return new CloudLimits(1048576, 50, 200, 8192, 100, 1000, 1000, 86400, 65536, 393216);
    }

    /**
     * Reads the exact welcome.limits shape, including its nested log object.
     * @param limits all required integer fields, with no unknown fields
     * @return an immutable snapshot with every value inside the contract bounds
     * @throws IllegalArgumentException if the shape, types or bounds are invalid
     */
    public static CloudLimits fromWelcome(JsonObject limits) {
        exactKeys(limits, "maxFrameBytes", "log", "outboxMax", "commandDedupeMax", "commandDedupeTtlSeconds",
                "commandOutputMaxBytes", "fileMaxBytes");
        if (!limits.get("log").isJsonObject()) { throw new IllegalArgumentException("Invalid welcome log limits"); }
        JsonObject log = limits.getAsJsonObject("log");
        exactKeys(log, "ratePerSec", "burst", "lineMaxBytes", "batchMaxLines");
        return new CloudLimits(integer(limits, "maxFrameBytes", 65536, 1048576),
                integer(log, "ratePerSec", 1, 1000), integer(log, "burst", 1, 10000),
                integer(log, "lineMaxBytes", 256, 8192), integer(log, "batchMaxLines", 1, 100),
                integer(limits, "outboxMax", 1, 10000), integer(limits, "commandDedupeMax", 1, 10000),
                integer(limits, "commandDedupeTtlSeconds", 60, 604800),
                integer(limits, "commandOutputMaxBytes", 1024, 65536), integer(limits, "fileMaxBytes", 1024, 393216));
    }

    private static void exactKeys(JsonObject object, String... keys) {
        if (object == null || !object.keySet().equals(new HashSet<>(Arrays.asList(keys)))) {
            throw new IllegalArgumentException("Invalid welcome limit fields");
        }
    }

    private static int integer(JsonObject object, String key, int min, int max) {
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid welcome limit: " + key);
        }
        try {
            int number = value.getAsBigDecimal().intValueExact();
            if (number >= min && number <= max) { return number; }
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid welcome limit: " + key);
        }
        throw new IllegalArgumentException("Invalid welcome limit: " + key);
    }

    /** @return maximum UTF-8 bytes of a complete serialized frame */
    public int maxFrameBytes() { return maxFrameBytes; }
    /** @return log lines replenished per second in the token bucket */
    public int logRatePerSec() { return logRatePerSec; }
    /** @return maximum log-line burst allowed by the token bucket */
    public int logBurst() { return logBurst; }
    /** @return maximum UTF-8 bytes of one serialized log-line object */
    public int logLineMaxBytes() { return logLineMaxBytes; }
    /** @return maximum number of lines in one log.lines batch */
    public int logBatchMaxLines() { return logBatchMaxLines; }
    /** @return maximum number of unacknowledged reliable events retained */
    public int outboxMax() { return outboxMax; }
    /** @return maximum number of deduplicated request ids retained */
    public int commandDedupeMax() { return commandDedupeMax; }
    /** @return deduplication retention time in seconds */
    public int commandDedupeTtlSeconds() { return commandDedupeTtlSeconds; }
    /** @return maximum UTF-8 bytes of the serialized command-output array */
    public int commandOutputMaxBytes() { return commandOutputMaxBytes; }
    /** @return maximum raw UTF-8 file-content bytes, not JSON frame bytes */
    public int fileMaxBytes() { return fileMaxBytes; }
}
