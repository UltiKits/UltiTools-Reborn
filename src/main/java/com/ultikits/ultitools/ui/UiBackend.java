package com.ultikits.ultitools.ui;

import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * A rendering backend. Framework-internal in 7.0: modules do not implement it.
 * Selection: among backends whose {@link #supports(Class, Player)} returns true for (intent kind, viewer),
 * the highest {@link #priority()} wins; two registered backends with equal priority that both support a kind
 * are a start-up error.
 */
@ApiStatus.Internal
public interface UiBackend {
    /** Unique backend identity. */
    Key id();
    /** Higher wins. Framework values: Dialog 100, chest 0. */
    int priority();
    /** May depend on the viewer (for example client capabilities in a later version). */
    boolean supports(Class<? extends UiIntent<?>> kind, Player viewer);
    /** Opens {@code session}'s intent on the viewer's entity thread; reports through {@code callbacks}. */
    <R> void open(UiSession<R> session, BackendCallbacks<R> callbacks);
    /** Closes the screen if this backend opened it; called on the viewer's entity thread. */
    void close(UiSession<?> session);

    /**
     * How a backend reports back; every method after the first terminal report is ignored.
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
