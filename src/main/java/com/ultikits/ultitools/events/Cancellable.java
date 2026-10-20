package com.ultikits.ultitools.events;

/**
 * Interface for events that can be cancelled by handlers.
 * Only meaningful for synchronous events — async events must NOT implement this.
 *
 * @since 6.2.2
 */
public interface Cancellable {
    boolean isCancelled();
    void setCancelled(boolean cancelled);
}
