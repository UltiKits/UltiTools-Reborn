package com.ultikits.ultitools.abstracts.command;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A drain's remaining allowance; only time spent waiting for command bodies is charged. */
final class CommandWaitBudget {
    private final LongSupplier clock;
    private final Awaiter awaiter;
    private long remaining;

    CommandWaitBudget() {
        this(Duration.ofSeconds(10), System::nanoTime, TimeUnit.NANOSECONDS::timedWait);
    }

    CommandWaitBudget(Duration allowance, LongSupplier clock, Awaiter awaiter) {
        this.remaining = allowance.toNanos();
        this.clock = clock;
        this.awaiter = awaiter;
    }

    synchronized boolean await(Object monitor) throws InterruptedException {
        if (remaining <= 0) {
            return false;
        }
        long before = clock.getAsLong();
        try {
            awaiter.await(monitor, remaining);
        } finally {
            long elapsed = Math.max(0, clock.getAsLong() - before);
            remaining = Math.max(0, remaining - elapsed);
        }
        return true;
    }

    @FunctionalInterface
    interface Awaiter {
        void await(Object monitor, long nanos) throws InterruptedException;
    }
}
