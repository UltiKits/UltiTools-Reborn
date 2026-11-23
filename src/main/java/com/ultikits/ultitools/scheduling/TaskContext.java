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
 * <p>Delays and periods are {@link Duration}s. Tick contexts (global, region, entity) round them up
 * to whole ticks with {@link Ticks#ceil(Duration)} (50 ms nominal per tick, so they stretch when the
 * server lags); a zero delay means the next tick, never the current one. The async context uses
 * wall-clock time: a task starts no earlier than its delay after submission, on a thread that owns
 * no tick context, but no upper bound is promised beyond "eventually" (it depends on the server's
 * async pool). An async task submitted with {@link #run(Runnable)} may start on another thread before
 * {@code run} returns; that is still not inline. Whether two executions of one repeating async task
 * can overlap is not specified in 7.0.
 *
 * <p>On an entity that is already removed, run, runLater and runRepeating return a handle that is
 * already RETIRED (its completion fails with TaskRetiredException), supply returns a future already
 * failed with TaskRetiredException, and execute throws RejectedExecutionException.
 */
@ApiStatus.NonExtendable
public interface TaskContext extends Executor {
    /**
     * Runs once, never inline, even on the context's own thread; on tick contexts, on the next tick.
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded
     */
    TaskHandle run(Runnable task);

    /**
     * Runs once after a delay. Zero behaves as {@link #run(Runnable)}.
     * @throws IllegalArgumentException if the delay is negative
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded
     */
    TaskHandle runLater(Duration delay, Runnable task);

    /**
     * Runs after the initial delay and then at the given period until cancelled, retired or unloaded.
     * The consumer receives its own handle and may cancel itself. An exception from one execution
     * is logged with the owner's name and does not stop repetition.
     * @throws IllegalArgumentException if the initial delay is negative, or the period is less than
     *         one tick for a tick context or not positive for async
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded
     */
    TaskHandle runRepeating(Duration initialDelay, Duration period, Consumer<? super TaskHandle> task);

    /**
     * Runs {@code task} once (as {@link #run(Runnable)}) and completes the returned future with its
     * result, or exceptionally with what it threw. The future follows {@link CompletableFuture} rules:
     * cancelling it before it completes always succeeds. If the task has not started it then never runs;
     * if it has started it finishes, its result is discarded, and an exception it throws is logged once
     * as a WARNING with the owner's name, because no caller can observe it any more. An entity removed
     * before the start completes the future with {@link TaskRetiredException}.
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded
     */
    <T> CompletableFuture<T> supply(Supplier<? extends T> task);

    /**
     * Whether the calling thread owns this context right now, whether or not it is running inside a
     * task submitted through this API.
     *
     * <p>On Paper every tick context (global, every region, every entity) is owned by the server main
     * thread, so code on the main thread (an event listener, a command, a task) gets {@code true} and
     * every other thread gets {@code false}. Under Folia the answer follows region ownership. The async
     * context is owned by no thread and always answers {@code false}; to check that the caller is off
     * the tick threads use {@code !scheduler.global().isCurrentThread()}. For an entity that is already
     * removed the answer is unspecified.
     */
    boolean isCurrentThread();

    /**
     * Runs the command through {@link #run(Runnable)}, discarding its handle.
     * @throws java.util.concurrent.RejectedExecutionException if the owner is unloaded or the
     *         entity is already removed
     */
    @Override
    void execute(Runnable command);
}
