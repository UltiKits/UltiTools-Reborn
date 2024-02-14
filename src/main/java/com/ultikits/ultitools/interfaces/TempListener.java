package com.ultikits.ultitools.interfaces;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * Temporary listener.
 * <p>
 * 临时监听器。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
public interface TempListener extends Listener {
    /**
     * Register the listener.
     * <br>
     * 注册监听器。
     */
    void register();

    /**
     * Unregister the listener.
     * <br>
     * 注销监听器。
     */
    default void unregister() {
        HandlerList.unregisterAll(this);
    }
}
