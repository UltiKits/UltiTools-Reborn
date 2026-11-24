package com.ultikits.ultitools.scheduling;

import java.util.concurrent.CompletionStage;
import org.jetbrains.annotations.ApiStatus;

/** A thread-safe, framework-owned submitted task; never a Bukkit or Paper task object. */
@ApiStatus.NonExtendable
public interface TaskHandle {
    /** Returns the current lifecycle state. */
    TaskState state();

    /** Returns whether this task was submitted with {@link TaskContext#runRepeating}. */
    boolean isRepeating();

    /**
     * Prevents every run that has not started. Never interrupts a run in progress. Idempotent.
     *
     * <p>A one-shot task that is already {@link TaskState#RUNNING} cannot be cancelled: this returns
     * {@code false} and the task still ends {@link TaskState#COMPLETED} or {@link TaskState#FAILED}
     * (as Paper's {@code ScheduledTask#cancel()} reports {@code RUNNING}). For a repeating task whose run
     * is in progress this returns {@code true}, the state becomes {@link TaskState#CANCELLED} at once and
     * {@link #completion()} completes with a {@code CancellationException}; the current run finishes, and
     * an exception it throws is logged with the owner's name like any other repeating failure.
     *
     * @return {@code true} only for the call that moved the task to {@link TaskState#CANCELLED}
     */
    boolean cancel();

    /**
     * Completes when the task will never run again: normally after a successful one-shot;
     * exceptionally with its thrown exception for {@link TaskState#FAILED},
     * {@link java.util.concurrent.CancellationException} for {@link TaskState#CANCELLED}
     * (including owner unload), or {@link TaskRetiredException} for {@link TaskState#RETIRED}.
     *
     * <p>A one-shot body failure from {@link TaskContext#run(Runnable)},
     * {@link TaskContext#runLater(java.time.Duration, Runnable)} or {@link TaskContext#execute(Runnable)}
     * is also logged exactly once at WARNING with its owner's name and original throwable.
     * A {@link TaskContext#supply(java.util.function.Supplier)} failure is reported only through its
     * future unless that future was cancelled after the body started.
     *
     * <p>This is a minimal stage: callers cannot complete or cancel the task through it.
     * Non-async dependent actions run on the thread completing the stage.
     */
    CompletionStage<Void> completion();
}
