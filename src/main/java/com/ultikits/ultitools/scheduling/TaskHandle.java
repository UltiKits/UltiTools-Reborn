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
     * Cancels future runs without interrupting an execution in progress. Idempotent.
     * @return true only for the call that moved the task to {@link TaskState#CANCELLED}
     */
    boolean cancel();

    /**
     * Completes when the task will never run again: normally after a successful one-shot;
     * exceptionally with its thrown exception for {@link TaskState#FAILED},
     * {@link java.util.concurrent.CancellationException} for {@link TaskState#CANCELLED}
     * (including owner unload), or {@link TaskRetiredException} for {@link TaskState#RETIRED}.
     *
     * <p>This is a minimal stage: callers cannot complete or cancel the task through it.
     * Non-async dependent actions run on the thread completing the stage.
     */
    CompletionStage<Void> completion();
}
