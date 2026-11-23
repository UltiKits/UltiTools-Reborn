package com.ultikits.ultitools.ui;

import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * One shown intent. Thread-safe.
 * @param <R> the answer type
 */
@ApiStatus.NonExtendable
public interface UiSession<R> {
    /** The viewer. */
    Player viewer();
    /** The originally submitted intent. */
    UiIntent<R> intent();
    /**
     * The outcome. Completes exactly once, with the outcome chosen first (see {@link #isOpen()}); a minimal
     * stage, so callers cannot complete it.
     *
     * <p>Thread: the stage is completed in a task of the viewer's entity context, or of the global context if
     * the viewer has quit. The one exception is owner unload, which completes it on the unloading thread before
     * the unload returns (on Paper the main thread, which owns every entity context), while the owner's services
     * are still usable. Non-async dependents attached before completion run on the completing thread. Following
     * {@link CompletionStage} rules, a non-async dependent attached after completion runs at once on the
     * attaching thread, which may be an async thread. To touch the player from a dependent whatever thread
     * attaches it, use an async variant with the scheduler:
     * {@code session.result().thenAcceptAsync(result -> ..., scheduler.entity(viewer))}. If the viewer has quit
     * by then, that executor rejects the task and the dependent stage completes exceptionally with
     * {@link java.util.concurrent.RejectedExecutionException}; use {@code scheduler.global()} for work that must
     * run even then.
     */
    CompletionStage<UiResult<R>> result();

    /**
     * Whether no outcome has been chosen yet. The outcome is chosen, at once and on whatever thread it happens,
     * by the first of: a backend's answer or close report, {@link #close()}, the timeout, the viewer quitting,
     * another intent opening for the viewer, the owner's unload. From then on this returns {@code false},
     * {@link GridSession#update(Grid)} does nothing and later reports are ignored; the screen is closed and
     * {@link #result()} completed afterwards, as described there.
     */
    boolean isOpen();

    /**
     * Chooses {@code Closed(CLOSED_BY_CODE)} if no outcome has been chosen yet, at once and from any thread. The
     * screen closes and the result completes later, in a task of the viewer's entity context, never inside this
     * call, so calling it from a grid action or an event handler is safe. Idempotent.
     */
    void close();
}
