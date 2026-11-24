package com.ultikits.ultitools.ui.runtime;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<Object> submitted = new AtomicReference<>();
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
        if (tasks.isEmpty()) { return; }
        Object ticket = new Object();
        if (!submitted.compareAndSet(null, ticket)) { return; }
        try {
            if (isRetired()) { handOff(ticket); return; }
            TaskHandle handle = scheduler.entity(viewer).run(() -> drain(ticket, false));
            handle.completion().whenComplete((ignored, failure) -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof TaskRetiredException) { handOff(ticket); }
                else if (cause != null) { retry(ticket); }
            });
        } catch (RejectedExecutionException failure) {
            handOff(ticket);
        } catch (Throwable failure) {
            retry(ticket);
            logger.log(Level.WARNING, "UI viewer submission failed for " + viewer.getUniqueId(), failure);
            UiRuntime.rethrowFatal(failure);
        }
    }

    private void handOff(Object ticket) {
        retired = true;
        pump.execute(() -> drain(ticket, true));
    }

    private void retry(Object ticket) {
        // A late completion must not release a newer drain's reservation. Never submit from a completion callback.
        if (submitted.compareAndSet(ticket, null) && !tasks.isEmpty()) { pump.execute(this::schedule); }
    }

    private void drain(Object ticket, boolean global) {
        if (!ticket.equals(submitted.get())) { return; }
        boolean handedOff = false;
        try {
            if (!global && isRetired()) { handedOff = true; handOff(ticket); return; }
            Runnable task;
            while ((task = tasks.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable failure) {
                    logger.log(Level.WARNING, "UI viewer continuation failed for " + viewer.getUniqueId(), failure);
                    UiRuntime.rethrowFatal(failure);
                }
                if (!global && isRetired()) { handedOff = true; handOff(ticket); return; }
            }
        } finally {
            if (!handedOff) { retry(ticket); }
        }
    }

    boolean belongsTo(Player player) { return viewer == player; } // NOPMD - entity identity distinguishes player connections
    boolean wasRetired() { return retired; }

    /** Marks this viewer's entity context retired; an outstanding drain observes this before doing work. */
    public void retire() { retired = true; }
    /** Whether the entity context can no longer deliver items. */
    public boolean isRetired() { return retired || !viewer.isOnline(); }
}
