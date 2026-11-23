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
     * Completes exactly once; minimal stage (callers cannot complete it); completed on the viewer's
     * entity thread, or the global thread if the viewer is gone.
     */
    CompletionStage<UiResult<R>> result();
    /** Whether no terminal outcome has been chosen yet. */
    boolean isOpen();
    /** Closes the screen; the result becomes {@code Closed(CLOSED_BY_CODE)} if still open. Idempotent. */
    void close();
}
