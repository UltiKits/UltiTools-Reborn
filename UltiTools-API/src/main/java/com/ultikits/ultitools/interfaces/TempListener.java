package com.ultikits.ultitools.interfaces;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

public interface TempListener extends Listener {
    void register();

    default void unregister() {
        HandlerList.unregisterAll(this);
    }
}
