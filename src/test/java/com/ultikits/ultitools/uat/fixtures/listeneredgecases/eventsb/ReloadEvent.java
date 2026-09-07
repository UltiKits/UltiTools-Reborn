package com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsb;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A minimal custom event named {@code ReloadEvent}, deliberately sharing its simple name with
 * {@link com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsa.ReloadEvent} in a
 * different package. See that class's javadoc for why this pair exists.
 *
 * @since 6.3.0
 */
public class ReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
