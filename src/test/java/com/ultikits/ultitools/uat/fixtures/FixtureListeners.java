package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Fixture {@code @EventListener} classes for {@code ListenerRowScannerTest} (Phase 10 plan
 * 10-02, Task 1): one class with a single handler, one class with two handlers (proving the row
 * unit is the handler method, not the class, per D-10-05), and one {@code manualRegister = true}
 * class that must still be represented rather than suppressed (D-10-04's discretion note, applied
 * to listeners the same way it is applied to {@code manualRegister}-true command executors).
 *
 * @since 6.3.0
 */
public final class FixtureListeners {

    private FixtureListeners() {
    }

    @EventListener
    public static class JoinListener implements Listener {
        @EventHandler(priority = EventPriority.HIGH)
        public void onJoin(PlayerJoinEvent event) {
        }
    }

    @EventListener
    public static class MultiHandlerListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
        }
    }

    @EventListener(manualRegister = true)
    public static class ManualListener implements Listener {
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
        }
    }
}
