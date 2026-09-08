package com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsa;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A minimal custom event named {@code ReloadEvent}, deliberately sharing its simple name with
 * {@link com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsb.ReloadEvent} in a
 * different package -- Bukkit treats the two as completely unrelated event classes, proving
 * {@code ListenerRowScanner}'s {@code event} field must carry the fully qualified name, not the
 * simple one, or the two would be indistinguishable to {@code tools/uat/uat.py next}'s grouping
 * (Phase 10, Codex review of PR #427).
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
