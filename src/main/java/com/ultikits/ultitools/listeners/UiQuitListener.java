package com.ultikits.ultitools.listeners;

import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

/** Retires the viewer lane after all ordinary quit handlers have observed the player. */
@ApiStatus.Internal
public final class UiQuitListener implements Listener {
    private final UiRuntime runtime;

    public UiQuitListener(UiRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        runtime.quit(event.getPlayer());
    }
}
