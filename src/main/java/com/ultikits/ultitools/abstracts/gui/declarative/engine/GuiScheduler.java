package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthExceededException;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Marshals GUI work onto the viewer's entity context using the framework scheduler.
 * A frame is always deferred to the next tick. Repeated requests for the same Runnable are coalesced;
 * distinct tasks share that frame. Tasks queued during a drain run on a later frame, bounding the work
 * of even a widget whose initState requests a rebuild and then throws.
 * @since 6.2.0
 */
public class GuiScheduler {
    private final TaskContext context;
    // This monitor also protects submitted handles, scheduling flags and the cancellation generation.
    private final Set<Runnable> pendingTasks = new LinkedHashSet<>();
    private final Set<TaskHandle> submitted = Collections.newSetFromMap(new IdentityHashMap<>());
    private TaskHandle frameHandle;
    private boolean frameScheduled;
    private boolean flushing;
    private long generation;

    /**
     * @param scheduler the framework-owned scheduler, not the module's scheduler
     * @param viewer the entity whose screen is being built
     */
    public GuiScheduler(@NotNull Scheduler scheduler, @NotNull Player viewer) {
        context = Objects.requireNonNull(scheduler, "scheduler").entity(Objects.requireNonNull(viewer, "viewer"));
    }

    /** @return whether the caller currently owns the viewer's entity context */
    public boolean isOnMainThread() { return context.isCurrentThread(); }

    /**
     * Runs synchronously on the owning thread, otherwise submits tracked work there.
     * A synchronous depth failure is reported and rethrown; deferred failures are logged instead.
     * @param task the work to execute
     */
    public void runOnMainThread(@NotNull Runnable task) {
        Objects.requireNonNull(task, "task");
        if (isOnMainThread()) {
            runSynchronous(task);
        } else {
            synchronized (pendingTasks) {
                submit(() -> runDeferred(task));
            }
        }
    }

    /** @param frameTask the stable frame callback to enqueue, at most once per pending frame */
    public void scheduleFrame(@NotNull Runnable frameTask) {
        synchronized (pendingTasks) {
            pendingTasks.add(Objects.requireNonNull(frameTask, "frameTask"));
            schedulePendingFrame();
        }
    }

    private void schedulePendingFrame() {
        if (frameScheduled || flushing || pendingTasks.isEmpty()) {
            return;
        }
        frameScheduled = true;
        try {
            frameHandle = submit(this::executeFrame);
        } catch (RuntimeException failure) {
            frameScheduled = false;
            throw failure;
        }
    }

    /** Called with the queue monitor held; TaskContext.run never runs inline. */
    private TaskHandle submit(Runnable task) {
        long submittedGeneration = generation;
        TaskHandle handle = context.run(() -> {
            synchronized (pendingTasks) {
                if (submittedGeneration != generation) {
                    return;
                }
            }
            task.run();
        });
        submitted.add(handle);
        handle.completion().whenComplete((ignored, failure) -> {
            synchronized (pendingTasks) {
                submitted.remove(handle);
            }
        });
        return handle;
    }

    private void executeFrame() {
        if (!isOnMainThread()) {
            runOnMainThread(this::executeFrame);
            return;
        }
        List<Runnable> budget;
        long frameGeneration;
        synchronized (pendingTasks) {
            frameScheduled = false;
            frameHandle = null;
            frameGeneration = generation;
            budget = new ArrayList<>(pendingTasks);
            pendingTasks.clear();
        }
        for (Runnable task : budget) {
            synchronized (pendingTasks) {
                if (generation != frameGeneration) {
                    return;
                }
            }
            runDeferred(task);
        }
    }

    private void runSynchronous(Runnable task) {
        try {
            task.run();
        } catch (RenderDepthExceededException failure) {
            reportRenderDepthExceeded(failure);
            throw failure;
        }
    }

    private void runDeferred(Runnable task) {
        try {
            task.run();
        } catch (RenderDepthExceededException failure) {
            logFrameTaskError(failure);
            reportRenderDepthExceeded(failure);
        } catch (Exception failure) {
            logFrameTaskError(failure);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // A logger handler may throw any runtime exception.
    private void logFrameTaskError(Exception failure) {
        try {
            UltiTools instance = UltiTools.getInstance();
            Logger logger = instance == null ? Logger.getLogger(GuiScheduler.class.getName()) : instance.getLogger();
            if (logger != null) {
                logger.log(Level.WARNING, "Error executing GUI frame task: " + failure.getMessage(), failure);
            }
        } catch (RuntimeException ignored) {
            // Paper re-logs stderr through the same handlers; never retry a failed logger there.
        }
    }

    private void reportRenderDepthExceeded(RenderDepthExceededException failure) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            ErrorReportCollector collector = instance.getErrorReportCollector();
            if (collector != null) {
                collector.reportError(failure, null, TriggerContext.uncaught("GUI render frame: " + failure.getMessage()));
            }
        } catch (Exception ignored) {
            // Reporting is best-effort and must never re-enter logging.
        }
    }

    /**
     * Drains pending frame work synchronously, ignoring the normal per-frame budget (for tests).
     * @throws IllegalStateException if the caller does not own the viewer's context
     */
    public void flush() {
        if (!isOnMainThread()) {
            throw new IllegalStateException("flush() must be called on main thread");
        }
        synchronized (pendingTasks) {
            flushing = true;
            cancelFrame();
        }
        try {
            Runnable task;
            while ((task = pollPending()) != null) {
                runSynchronous(task);
            }
        } finally {
            synchronized (pendingTasks) {
                flushing = false;
                schedulePendingFrame();
            }
        }
    }

    private Runnable pollPending() {
        synchronized (pendingTasks) {
            if (pendingTasks.isEmpty()) {
                return null;
            }
            Runnable first = pendingTasks.iterator().next();
            pendingTasks.remove(first);
            return first;
        }
    }

    private void cancelFrame() {
        if (frameHandle != null) {
            frameHandle.cancel();
            frameHandle = null;
        }
        frameScheduled = false;
    }

    /** Cancels every submitted handle as well as pending frames; the scheduler may be reused afterwards. */
    public void cancelAll() {
        synchronized (pendingTasks) {
            generation++;
            pendingTasks.clear();
            for (TaskHandle handle : new ArrayList<>(submitted)) {
                handle.cancel();
            }
            submitted.clear();
            frameHandle = null;
            frameScheduled = false;
        }
    }
}
