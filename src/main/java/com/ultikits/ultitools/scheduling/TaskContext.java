package com.ultikits.ultitools.scheduling;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Submits tasks with one thread affinity. Also an {@link Executor} for future continuations,
 * such as {@code future.thenApplyAsync(fn, scheduler.global())}.
 *
 * <p>Tick contexts round durations up with {@link Ticks#ceil(Duration)} at a nominal 50 ms per tick;
 * delays stretch with server lag. Async scheduling uses wall-clock durations instead.
 */
@ApiStatus.NonExtendable
public interface TaskContext extends Executor {
    /** Runs once, no earlier than the next tick; never inline, even on the context's own thread. */
    TaskHandle run(Runnable task);

    /**
     * Runs once after a delay. Zero behaves as {@link #run(Runnable)}.
     * @throws IllegalArgumentException if the delay is negative
     */
    TaskHandle runLater(Duration delay, Runnable task);

    /**
     * Runs after the initial delay and then at the given period until cancelled, retired or unloaded.
     * The consumer receives its own handle and may cancel itself. An exception from one execution
     * is logged with the owner's name and does not stop repetition.
     * @throws IllegalArgumentException if the initial delay is negative, or the period is less than
     *         one tick for a tick context or not positive for async
     */
    TaskHandle runRepeating(Duration initialDelay, Duration period, Consumer<? super TaskHandle> task);

    /**
     * Runs a supplier once, as {@link #run(Runnable)}, completing its future with the result or thrown
     * exception. Cancelling the future before the task starts cancels the task; a run in progress is
     * not interrupted and the future stays cancelled. Entity retirement completes the future with
     * {@link TaskRetiredException}.
     */
    <T> CompletableFuture<T> supply(Supplier<? extends T> task);

    /** Returns whether the calling thread owns this context; always false for async. */
    boolean isCurrentThread();

    /**
     * Runs the command through {@link #run(Runnable)}, discarding its handle.
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded or the
     *         entity is already removed
     */
    @Override
    void execute(Runnable command);
}
