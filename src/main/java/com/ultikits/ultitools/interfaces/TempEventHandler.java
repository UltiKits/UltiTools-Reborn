package com.ultikits.ultitools.interfaces;

import org.bukkit.event.Event;

/**
 * Temporary event handler.
 *
 * @param <E> Event type
 */
@FunctionalInterface
public interface TempEventHandler<E extends Event> {
    /**
     * @param event Event
     * @return Whether to unregister the listener
     */
    boolean handle(E event);
}
