package com.ultikits.ultitools.utils;

import java.util.function.DoubleSupplier;

/**
 * Reconnect delays for the UltiCloud agent connection, with full jitter (as of 6.3.1).
 * <p>
 * Two classes of delay, both uniform over their whole range so that servers that lost the
 * gateway at the same moment do not come back at the same moment:
 * <ul>
 *   <li><b>normal</b> -- attempt {@code n} (0-based) waits a uniform delay in
 *       [1 s, min(60 s, 2^n s)]: network loss, gateway restarts, internal errors;</li>
 *   <li><b>long</b> -- the {@code k}-th consecutive long-class failure (0-based) waits a uniform
 *       delay in [60 s, min(15 min, 60 s x 2^k)]: failures that a quick retry only repeats
 *       (superseded connection, abuse rate, an invalid hello, HTTP 429).</li>
 * </ul>
 * The bounds mirror the backend's contract answer 7 of 2026-10-24 and are aligned with the
 * generated contract together with the provisional table in {@link AgentConnection}.
 */
final class ReconnectPolicy {

    /** Lower bound, and the attempt-0 ceiling, of a normal delay. */
    static final long MIN_DELAY_MS = 1000L;
    /** Upper bound of a normal delay. */
    static final long MAX_DELAY_MS = 60000L;
    /** Lower bound, and the first ceiling, of a long delay. */
    static final long LONG_MIN_DELAY_MS = 60000L;
    /** Upper bound of a long delay. */
    static final long LONG_MAX_DELAY_MS = 900000L;

    /** Past this exponent every ceiling is already capped; also keeps the shift from overflowing. */
    private static final int MAX_EXPONENT = 40;

    private final DoubleSupplier random;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final long longMinDelayMs;
    private final long longMaxDelayMs;

    /**
     * The production policy.
     *
     * @param random a source of uniform values in [0, 1)
     */
    ReconnectPolicy(DoubleSupplier random) {
        this(random, MIN_DELAY_MS, MAX_DELAY_MS, LONG_MIN_DELAY_MS, LONG_MAX_DELAY_MS);
    }

    /**
     * A policy with other bounds, so tests can run the reconnect loop in milliseconds. The
     * formulas are the production ones with {@code minDelayMs} and {@code longMinDelayMs} as the
     * bases.
     */
    ReconnectPolicy(DoubleSupplier random, long minDelayMs, long maxDelayMs,
                    long longMinDelayMs, long longMaxDelayMs) {
        this.random = random;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.longMinDelayMs = longMinDelayMs;
        this.longMaxDelayMs = longMaxDelayMs;
    }

    /**
     * The normal delay before the next attempt.
     *
     * @param attempt consecutive normal-class failures so far, 0-based; negative counts as 0
     * @return a delay in [1 s, min(60 s, 2^attempt s)]
     */
    long delayMillis(int attempt) {
        return jitter(minDelayMs, ceiling(minDelayMs, maxDelayMs, attempt));
    }

    /**
     * The long delay before the next attempt.
     *
     * @param consecutiveLongFailures consecutive long-class failures so far, 0-based; negative
     *                                counts as 0
     * @return a delay in [60 s, min(15 min, 60 s x 2^k)]
     */
    long longDelayMillis(int consecutiveLongFailures) {
        return jitter(longMinDelayMs, ceiling(longMinDelayMs, longMaxDelayMs, consecutiveLongFailures));
    }

    private static long ceiling(long base, long max, int exponent) {
        if (exponent <= 0) {
            return Math.min(base, max);
        }
        if (exponent >= MAX_EXPONENT) {
            return max;
        }
        return Math.min(max, base << exponent);
    }

    private long jitter(long low, long high) {
        if (high <= low) {
            return low;
        }
        double r = random.getAsDouble();
        if (Double.isNaN(r) || r < 0) {
            r = 0;
        }
        if (r >= 1) {
            r = Math.nextDown(1.0);
        }
        long span = high - low;
        long delay = low + (long) Math.floor(r * (span + 1));
        return Math.min(high, delay);
    }
}
