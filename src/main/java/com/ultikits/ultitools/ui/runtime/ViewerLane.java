package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/** Ordered work for one viewer, independent of scheduler ordering. */
@ApiStatus.Internal
public final class ViewerLane {
    private final Scheduler scheduler;
    private final Player viewer;
    private final GlobalPump pump;
    private final Logger logger;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean submitted = new AtomicBoolean();
    private volatile boolean retired;

    /** Creates a lane sharing the runtime's global fallback. */
    public ViewerLane(Scheduler scheduler, Player viewer, GlobalPump pump, Logger logger) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.pump = Objects.requireNonNull(pump, "pump");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Queues work without running it inline. Only one drain is submitted at a time. */
    public void execute(Runnable task) {
        tasks.add(Objects.requireNonNull(task, "task"));
        schedule();
    }

    private void schedule() {
        if (!submitted.compareAndSet(false, true)) {
            return;
        }
        if (isRetired()) {
            retired = true;
            pump.execute(() -> drain(true));
            return;
        }
        try {
            TaskHandle handle = scheduler.entity(viewer).run(() -> drain(false));
            handle.completion().whenComplete((ignored, failure) -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof TaskRetiredException) {
                    retired = true;
                    pump.execute(() -> drain(true));
                }
            });
        } catch (RejectedExecutionException failure) {
            retired = true;
            pump.execute(() -> drain(true));
        }
    }

    private void drain(boolean global) {
        if (!global && isRetired()) {
            retired = true;
            pump.execute(() -> drain(true));
            return;
        }
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "UI viewer continuation failed for " + viewer.getUniqueId(), failure);
            }
            if (!global && isRetired()) {
                retired = true;
                pump.execute(() -> drain(true));
                return;
            }
        }
        submitted.set(false);
        if (!tasks.isEmpty()) {
            schedule();
        }
    }

    boolean belongsTo(Player player) { return viewer == player; } // NOPMD - entity identity distinguishes player connections
    boolean wasRetired() { return retired; }

    /** Marks this viewer's entity context retired; an outstanding drain observes this before doing work. */
    public void retire() { retired = true; }
    /** Whether the entity context can no longer deliver items. */
    public boolean isRetired() { return retired || !viewer.isOnline(); }
}
