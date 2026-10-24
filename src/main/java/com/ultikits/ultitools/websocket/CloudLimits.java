package com.ultikits.ultitools.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import java.util.Arrays;
import java.util.HashSet;

/** @since 6.3.1 */
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

    public static CloudLimits defaults() {
        return new CloudLimits(1048576, 50, 200, 8192, 100, 1000, 1000, 86400, 65536, 393216);
    }

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

    public int maxFrameBytes() { return maxFrameBytes; }
    public int logRatePerSec() { return logRatePerSec; }
    public int logBurst() { return logBurst; }
    public int logLineMaxBytes() { return logLineMaxBytes; }
    public int logBatchMaxLines() { return logBatchMaxLines; }
    public int outboxMax() { return outboxMax; }
    public int commandDedupeMax() { return commandDedupeMax; }
    public int commandDedupeTtlSeconds() { return commandDedupeTtlSeconds; }
    public int commandOutputMaxBytes() { return commandOutputMaxBytes; }
    public int fileMaxBytes() { return fileMaxBytes; }
}
