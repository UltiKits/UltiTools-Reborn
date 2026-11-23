package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.Ticks;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/** A single repeating global task; retirement callbacks only enqueue, never submit scheduler tasks. */
@ApiStatus.Internal
public final class GlobalPump implements AutoCloseable {
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final TaskHandle handle;
    private final Logger logger;

    /** Starts the pump on the framework-owned scheduler. */
    public GlobalPump(Scheduler scheduler, Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        handle = Objects.requireNonNull(scheduler, "scheduler").global()
                .runRepeating(Ticks.TICK, Ticks.TICK, ignored -> drain());
    }

    /** Enqueues work for the next global drain without touching the scheduler. */
    public void execute(Runnable task) { tasks.add(Objects.requireNonNull(task, "task")); }

    private void drain() {
        // Retry submissions enqueued during this batch wait until the next tick, avoiding a rejection spin.
        int remaining = tasks.size();
        Runnable task;
        while (remaining-- > 0 && (task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "UI global continuation failed", failure);
                UiRuntime.rethrowFatal(failure);
            }
        }
    }

    @Override public void close() {
        handle.cancel();
        tasks.clear();
    }
}
