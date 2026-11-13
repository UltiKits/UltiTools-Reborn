package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Real single agent thread and socket callbacks; only delayed work needs the test clock to advance. */
final class ControlledAgentExecutor extends ScheduledThreadPoolExecutor {
    private final AtomicLong clock;
    private final List<TimedTask> delayed = new ArrayList<>();

    ControlledAgentExecutor(AtomicLong clock, ThreadFactory threads) {
        super(1, threads);
        this.clock = clock;
    }

    @Override
    public synchronized ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (delay <= 0) {
            return super.schedule(task, 0, unit);
        }
        if (isShutdown()) {
            throw new java.util.concurrent.RejectedExecutionException("agent executor stopped");
        }
        TimedTask scheduled = new TimedTask(task, clock.get() + unit.toMillis(delay));
        delayed.add(scheduled);
        return scheduled;
    }

    /** Runs a snapshot of due timers on the same agent thread, then drains immediate callbacks. */
    void runDue() throws Exception {
        List<TimedTask> due = new ArrayList<>();
        synchronized (this) {
            delayed.removeIf(task -> {
                if (task.isCancelled()) {
                    return true;
                }
                if (task.due <= clock.get()) {
                    due.add(task);
                    return true;
                }
                return false;
            });
        }
        due.sort(Comparator.comparingLong(task -> task.due));
        for (TimedTask task : due) {
            execute(task);
            task.get(10, TimeUnit.SECONDS);
        }
        if (!isShutdown()) {
            submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void shutdown() {
        delayed.forEach(task -> task.cancel(false));
        delayed.clear();
        super.shutdown();
    }

    private final class TimedTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final long due;

        TimedTask(Runnable task, long due) {
            super(task, null);
            this.due = due;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(due - clock.get(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
