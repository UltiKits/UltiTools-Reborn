package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import com.ultikits.ultitools.scheduling.TaskState;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The task lifecycle; Paper's task state is deliberately not the API's state. */
@ApiStatus.Internal
final class TaskRecord implements TaskHandle {
    private final Context context;
    private final Consumer<? super TaskHandle> body;
    private final long period;
    private final @Nullable CompletableFuture<?> supplyFuture;
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.SCHEDULED);
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Set<Delivery> pending = ConcurrentHashMap.newKeySet();
    private final Object repeatLock = new Object();

    TaskRecord(Context context, Consumer<? super TaskHandle> body, long period,
               @Nullable CompletableFuture<?> supplyFuture) {
        this.context = context;
        this.body = body;
        this.period = period;
        this.supplyFuture = supplyFuture;
    }

    @Override
    public TaskState state() {
        return state.get();
    }

    @Override
    public boolean isRepeating() {
        return period > 0;
    }

    @Override
    public CompletionStage<Void> completion() {
        return done.minimalCompletionStage();
    }

    @Override
    public boolean cancel() {
        while (true) {
            TaskState observed = state.get();
            if (observed != TaskState.SCHEDULED && !(observed == TaskState.RUNNING && isRepeating())) {
                return false;
            }
            if (state.compareAndSet(observed, TaskState.CANCELLED)) {
                finish(new CancellationException("Task cancelled for " + context.owner.owner()));
                return true;
            }
        }
    }

    void schedule(long delay) {
        if (isTerminal()) {
            return;
        }
        Delivery delivery = new Delivery();
        pending.add(delivery);
        // A cancellation can win before Paper returns a handle, or an async callback can run first.
        if (isTerminal()) {
            pending.remove(delivery);
            delivery.cancel();
            return;
        }
        ScheduledTask task = context.deliver(ignored -> {
            pending.remove(delivery);
            executeBody();
        }, () -> {
            pending.remove(delivery);
            retireEntity();
        }, delay);
        if (task == null) {
            pending.remove(delivery);
            retireEntity();
        } else {
            delivery.attach(task);
            if (isTerminal()) {
                delivery.cancel();
                pending.remove(delivery);
            }
        }
    }

    private void executeBody() {
        if (isRepeating()) {
            // Serialization is allowed by the async contract. A short period must not lose its chain
            // merely because the next delivery arrives before the previous body finishes.
            synchronized (repeatLock) {
                executeClaimed();
            }
        } else {
            executeClaimed();
        }
    }

    private void executeClaimed() {
        if (!state.compareAndSet(TaskState.SCHEDULED, TaskState.RUNNING)) {
            return;
        }
        if (supplyFuture != null && supplyFuture.isCancelled()) {
            state.set(TaskState.CANCELLED);
            finish(new CancellationException("Supplier cancelled before execution"));
            return;
        }
        if (isRepeating() && !scheduleNext()) {
            return;
        }
        try {
            body.accept(this);
            if (isRepeating()) {
                state.compareAndSet(TaskState.RUNNING, TaskState.SCHEDULED);
            } else {
                state.set(TaskState.COMPLETED);
                finish(null);
            }
        } catch (Throwable failure) {
            bodyFailed(failure);
        }
    }

    private void bodyFailed(Throwable failure) {
        if (isRepeating()) {
            warn(failure);
            state.compareAndSet(TaskState.RUNNING, TaskState.SCHEDULED);
        } else {
            state.set(TaskState.FAILED);
            if (supplyFuture != null && !supplyFuture.completeExceptionally(failure) && supplyFuture.isCancelled()) {
                warn(failure);
            }
            finish(failure);
        }
    }

    private boolean scheduleNext() {
        try {
            schedule(period);
            return !isTerminal();
        } catch (IllegalPluginAccessException | RejectedExecutionException failure) {
            finishPending(TaskState.RETIRED, failure);
        } catch (RuntimeException | Error failure) {
            submissionFailed(failure);
        }
        return false;
    }

    private void retireEntity() {
        finishPending(TaskState.RETIRED,
                new TaskRetiredException("Entity removed before the task of " + context.owner.owner() + " could run"));
    }

    void submissionFailed(Throwable failure) {
        finishPending(TaskState.FAILED, failure);
    }

    private void finishPending(TaskState terminal, Throwable failure) {
        while (true) {
            TaskState observed = state.get();
            if (observed != TaskState.SCHEDULED && !(observed == TaskState.RUNNING && isRepeating())) {
                return;
            }
            if (state.compareAndSet(observed, terminal)) {
                finish(failure);
                return;
            }
        }
    }

    private boolean isTerminal() {
        TaskState current = state.get();
        return current != TaskState.SCHEDULED && current != TaskState.RUNNING;
    }

    private void finish(@Nullable Throwable failure) {
        context.owner.live.remove(this);
        pending.forEach(Delivery::cancel);
        pending.clear();
        if (supplyFuture != null && failure != null) {
            if (failure instanceof CancellationException) {
                supplyFuture.cancel(false);
            } else {
                supplyFuture.completeExceptionally(failure);
            }
        }
        if (failure == null) {
            done.complete(null);
        } else {
            done.completeExceptionally(failure);
        }
    }

    private void warn(Throwable failure) {
        context.owner.engine.logger.log(Level.WARNING,
                "[UltiTools-API] Error executing scheduled task of " + context.owner.owner(), failure);
    }

    /** Each delivery publishes its own handle so an early callback cannot overwrite its successor. */
    final class Delivery {
        private @Nullable ScheduledTask task;
        private boolean cancelled;

        synchronized void attach(ScheduledTask scheduled) {
            task = scheduled;
            if (cancelled) {
                cancelPaper(scheduled);
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (task != null) {
                cancelPaper(task);
            }
        }

        void cancelPaper(ScheduledTask scheduled) {
            try {
                scheduled.cancel();
            } catch (RuntimeException failure) {
                // Backend cleanup must not prevent completion or cancellation of the other tasks.
                // The lifecycle CAS still prevents this delivery from entering a cancelled body.
                context.owner.engine.logger.log(Level.FINE,
                        "[UltiTools-API] Task already cancelled: " + context.owner.owner(), failure);
            }
        }
    }
}
