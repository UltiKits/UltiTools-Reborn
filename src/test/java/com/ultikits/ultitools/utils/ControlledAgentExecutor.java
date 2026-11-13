package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real single agent thread and socket callbacks; only delayed work needs the test clock to advance.
 *
 * <p>Delayed work follows the policy of the production executor
 * ({@code Executors.newSingleThreadScheduledExecutor()}): {@code shutdown()} keeps delayed tasks that
 * are still pending, because {@code executeExistingDelayedTasksAfterShutdown} is {@code true} in the
 * JDK, and {@link #runDue()} runs the ones that fall due after shutdown inline rather than rejecting
 * them. A test that watches for "no new connection after teardown" is therefore not made true by the
 * double: the product's own {@code AgentConnection.stop()} has to cancel the retry, which
 * {@link #pendingDelayedAtShutdown()} makes visible.
 *
 * <p>{@link #awaitTerminationTimeouts()} records every wait the product asks for, so a test can
 * assert that teardown never waits for the agent thread, as {@code CloudSession}'s javadoc requires.
 */
final class ControlledAgentExecutor extends ScheduledThreadPoolExecutor {
    private final AtomicLong clock;
    private final List<TimedTask> delayed = new ArrayList<>();
    private final List<Long> awaitTerminationTimeouts = new CopyOnWriteArrayList<>();
    /** Uncanceled delayed tasks queued when the executor was first shut down, or -1 before that. */
    private int pendingAtShutdown = -1;

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
            throw new RejectedExecutionException("agent executor stopped");
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
            // One due task may cancel or finish another from the same snapshot (for example
            // runRetry -> beginAttempt -> cancelTimers), so the snapshot is re-checked per task.
            if (task.isCancelled()) {
                continue;
            }
            run(task);
        }
        if (!isShutdown()) {
            submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Runs one future task the way the production executor would: on its thread while it is still
     * running, inline once it has been shut down (the JDK keeps executing delayed tasks after
     * {@code shutdown()}).
     */
    private void run(TimedTask task) throws Exception {
        try {
            if (isShutdown()) {
                task.run();
            } else {
                execute(task);
            }
            task.get(10, TimeUnit.SECONDS);
        } catch (CancellationException cancelled) {
            // The task was cancelled between the isCancelled() check and the run; nothing to wait for.
        } catch (RejectedExecutionException stopped) {
            // Shut down between the check and the execute: fall back to the inline path.
            task.run();
            task.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Keeps pending delayed tasks, as production does, and records how many were still uncancelled
     * at this moment.
     */
    @Override
    public synchronized void shutdown() {
        if (pendingAtShutdown < 0) {
            pendingAtShutdown = (int) delayed.stream().filter(task -> !task.isCancelled()).count();
        }
        super.shutdown();
    }

    /**
     * @return the number of uncancelled delayed tasks queued when this executor was shut down, or
     *         {@code -1} if it has not been shut down -- teardown that left a retry behind shows up
     *         here instead of being hidden by the double
     */
    int pendingDelayedAtShutdown() {
        synchronized (this) {
            return pendingAtShutdown;
        }
    }

    /**
     * @return the {@code timeout} argument of every {@code awaitTermination} call, in call order; a
     *         teardown that waits for the agent thread leaves an entry here
     */
    List<Long> awaitTerminationTimeouts() {
        return new ArrayList<>(awaitTerminationTimeouts);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        awaitTerminationTimeouts.add(unit.toMillis(timeout));
        return super.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        // JDK 19+ AutoCloseable form of the same wait; recorded before delegating so that neither
        // the intent nor the wait hides from a test.
        awaitTerminationTimeouts.add(-1L);
        super.close();
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
