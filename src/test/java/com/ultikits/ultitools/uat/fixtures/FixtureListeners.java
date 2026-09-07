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
// This class is a namespace for the nested @EventListener fixtures below, not a utility
// class with static helpers of its own -- the private constructor exists only to block a
// pointless `new FixtureListeners()`. PMD's rule assumes a non-instantiatable class with no
// static members serves no purpose; the purpose here is holding the nested classes.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class FixtureListeners {

    private FixtureListeners() {
    }

    @EventListener
    public static class JoinListener implements Listener {
        @EventHandler(priority = EventPriority.HIGH)
        public void onJoin(PlayerJoinEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    @EventListener
    public static class MultiHandlerListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    @EventListener(manualRegister = true)
    public static class ManualListener implements Listener {
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
        }
    }
}
