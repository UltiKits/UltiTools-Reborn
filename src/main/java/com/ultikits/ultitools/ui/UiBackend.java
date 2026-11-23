package com.ultikits.ultitools.ui;

import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * A rendering backend. Framework-internal in 7.0: modules do not implement it.
 *
 * <p>Selection: among registered backends whose {@link #supports(UiIntent, Player)} returns true, the one
 * with the highest {@link #priority()} opens the intent. Priorities are unique: registering a backend whose
 * priority equals that of an already registered backend fails with {@link IllegalStateException}, whatever
 * intents the two support, so selection never depends on registration order. The framework registers its
 * backends at start-up, so a clash is a start-up error.
 *
 * <p>The service calls {@code supports}, {@code open} and {@code update} in a task of the viewer's entity
 * context; {@code close} as stated on {@link #close(UiSession)}. It never calls a backend for a session whose
 * outcome has already been delivered.
 */
@ApiStatus.Internal
public interface UiBackend {
    /** Unique backend identity. */
    Key id();
    /** Higher wins; unique among registered backends. Framework values: Dialog 100, chest 0. */
    int priority();
    /**
     * Whether this backend can show {@code intent} to {@code viewer} now. May depend on the intent's content
     * (for example the inputs of a form) and on the viewer (for example client capabilities).
     */
    boolean supports(UiIntent<?> intent, Player viewer);
    /**
     * Opens {@code session}'s intent; reports through {@code callbacks}. For a {@link Grid} it shows
     * {@link GridSession#current()} as it is at this call, which may already differ from the intent.
     * @param <R> answer type
     */
    <R> void open(UiSession<R> session, BackendCallbacks<R> callbacks);

    /**
     * Shows {@code next} instead of {@code previous} on a grid this backend opened, before returning.
     * {@code previous} is the snapshot this backend was last given (by {@code open} or {@code update}). Called
     * only while the session is open. A backend that cannot change the title of a shown screen may reopen it to
     * show a new title; that does not end the session.
     */
    void update(GridSession session, Grid previous, Grid next);
    /**
     * Closes the screen of {@code session} if this backend opened it and it is still showing; called at most once
     * per opened session, after the outcome is chosen and before the result completes. Called in a task of the
     * viewer's entity context; in a task of the global context if the viewer has quit, when the screen is already
     * gone and the backend must only release its own bookkeeping; or on the unloading thread during owner unload.
     */
    void close(UiSession<?> session);

    /**
     * How a backend reports back. May be called from any thread, including inside the backend's own event
     * handler: the service only records the report there, and runs module code (grid actions, result
     * dependents) later in a task of the viewer's entity context. The first {@code completed} or {@code closed}
     * report chooses the outcome if none has been chosen; every report after the outcome is chosen is ignored.
     * {@code clicked} is not terminal. {@code completed} on a {@link Grid} session is ignored.
     * @param <R> answer type
     */
    @ApiStatus.NonExtendable
    interface BackendCallbacks<R> {
        /** Reports an answer. */
        void completed(R value);
        /** Reports an end without an answer. */
        void closed(CloseReason reason);
        /** For Grid: a click on {@code slot}. */
        void clicked(GridSlot slot, ClickKind kind);
    }
}
