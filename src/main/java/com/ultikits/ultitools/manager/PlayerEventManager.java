package com.ultikits.ultitools.manager;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import org.jetbrains.annotations.ApiStatus;

/**
 * Player event manager.
 * Handles WebSocket messages for player-related events.
 */
@ApiStatus.Internal
public class PlayerEventManager implements Listener {

    /**
     * Whether this instance has already hooked itself into the Bukkit event system.
     * <p>
     * In 6.3.0 the protocol v1 {@code initialize} method (removed in 6.4.0) was called by the v1 manager wiring
     * (removed in 6.3.1), which was itself hung on the WebSocket's {@code onConnectHandler} --
     * <b>it ran on every single onOpen</b>. Without this guard, N disconnect-reconnect cycles would
     * register N copies of the listener, and the same player event would be sent out N times. See
     * issue #180.
     */
    private volatile boolean listenerRegistered = false;

    /**
     * Unregisters the event listener and detaches the association with the client.
     * <p>
     * Called by {@code PluginInitiationUtils.disableCloud()} -- i.e. after {@code /ulticloud
     * logout}. Distinct from a "disconnect-and-reconnect": in that case {@code initialize} gets
     * called back afterward, so unregistering would just mean re-registering again; this method
     * handles the case where the cloud feature was explicitly turned off, and the listener
     * should genuinely be detached.
     */
    public synchronized void shutdown() {
        HandlerList.unregisterAll(this);
        listenerRegistered = false;
    }

    /** Lets tests assert the idempotency guard's state. */
    boolean isListenerRegistered() {
        return listenerRegistered;
    }
}
