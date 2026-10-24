package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;

/** @since 6.3.1 */
@ApiStatus.Internal
public final class CloudLimits {
    private CloudLimits() { }

    public static CloudLimits defaults() { return new CloudLimits(); }
    public int maxFrameBytes() { return 1048576; }
    public int logRatePerSec() { return 50; }
    public int logBurst() { return 200; }
    public int logLineMaxBytes() { return 8192; }
    public int logBatchMaxLines() { return 100; }
    public int outboxMax() { return 1000; }
    public int commandDedupeMax() { return 1000; }
    public int commandDedupeTtlSeconds() { return 86400; }
    public int commandOutputMaxBytes() { return 65536; }
    public int fileMaxBytes() { return 393216; }
}
