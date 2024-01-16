package com.ultikits.ultitools.interfaces;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

public interface TempEventHandler<E extends Event> {
    boolean handle(E event);
}
