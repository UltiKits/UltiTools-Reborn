package com.ultikits.ultitools.scheduling;

import java.time.Duration;
import java.util.Objects;

/** Tick/duration conversions at the nominal 20 ticks per second. */
public final class Ticks {
    /** One tick, 50 ms. */
    public static final Duration TICK = Duration.ofMillis(50);
    private static final long NANOS_PER_TICK = 50_000_000L;

    private Ticks() {
    }

    /**
     * Returns {@code ticks * 50 ms} without overflowing an intermediate millisecond count.
     * @throws IllegalArgumentException if ticks is negative
     */
    public static Duration of(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must not be negative");
        }
        return Duration.ofSeconds(ticks / 20, (ticks % 20) * NANOS_PER_TICK);
    }

    /**
     * Returns whole ticks covering the duration, rounded up: zero to 0, 1 ms to 1,
     * 50 ms to 1, and 51 ms to 2.
     * @throws IllegalArgumentException if the duration is negative
     * @throws ArithmeticException if the tick count overflows a long
     */
    public static long ceil(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        long whole = Math.multiplyExact(duration.getSeconds(), 20);
        long fractional = (duration.getNano() + NANOS_PER_TICK - 1) / NANOS_PER_TICK;
        return Math.addExact(whole, fractional);
    }
}
