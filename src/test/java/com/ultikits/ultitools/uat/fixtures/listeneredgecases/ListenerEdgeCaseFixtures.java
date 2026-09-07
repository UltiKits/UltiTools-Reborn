package com.ultikits.ultitools.uat.fixtures.listeneredgecases;

import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsa.ReloadEvent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Fixtures for the three {@code ListenerRowScanner} edge cases raised by Codex review of PR
 * #427 (Phase 10): a legitimately overloaded handler method name, and the two ends of the
 * "declared directly vs. inherited" non-public visibility split. See
 * {@link com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsa.ReloadEvent}'s javadoc
 * for the separate cross-package same-simple-name fixture pair.
 *
 * @since 6.3.0
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class ListenerEdgeCaseFixtures {

    private ListenerEdgeCaseFixtures() {
    }

    /**
     * Two {@code @EventHandler} methods sharing the SAME name but overloaded by event
     * parameter type -- legal Java, and Bukkit registers both as independent handlers. Before
     * the fix, both produced the identical {@code (kind, origin, cls, member)} row id and the
     * second {@code claim} call aborted extraction with a false collision.
     */
    @EventListener
    public static class OverloadedHandlerListener implements Listener {
        @EventHandler
        public void onPlayerAction(PlayerJoinEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }

        @EventHandler
        public void onPlayerAction(PlayerQuitEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    /** Not itself annotated -- exists only to be extended by the two subclasses below. */
    public static class NonPublicHandlerBase implements Listener {
        @EventHandler
        protected void onInheritedNonPublicHandler(PlayerQuitEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    /**
     * Does not redeclare {@code onInheritedNonPublicHandler} -- Bukkit's own listener
     * discovery (public methods across the whole hierarchy, plus any-visibility methods
     * declared directly on the concrete class) never reaches this {@code protected} handler
     * through inheritance, so the scanner must emit no row for it on this subclass.
     */
    @EventListener
    public static class SubclassNotOverridingHandler extends NonPublicHandlerBase {
    }

    /**
     * Declares its OWN non-public handler directly (not inherited) -- Bukkit's
     * {@code getDeclaredMethods()} half of its discovery reaches this regardless of
     * visibility, so the scanner must still emit a row for it.
     */
    @EventListener
    public static class SubclassWithOwnNonPublicHandler extends NonPublicHandlerBase {
        @EventHandler
        protected void onDirectlyDeclaredNonPublicHandler(PlayerJoinEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    /**
     * Two differently-named handlers for two different-package events that happen to share
     * the simple name {@code ReloadEvent} -- proves the scanner's {@code event} field carries
     * the fully qualified name, distinguishing the two.
     */
    @EventListener
    public static class CrossPackageSameSimpleNameListener implements Listener {
        @EventHandler
        public void onReloadA(ReloadEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }

        @EventHandler
        public void onReloadB(com.ultikits.ultitools.uat.fixtures.listeneredgecases.eventsb.ReloadEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }

    /**
     * Carries {@code @EventListener} and a structurally valid {@code @EventHandler} method, but
     * deliberately does NOT implement {@link Listener} -- {@code ListenerManager.registerAll}
     * discovers handlers only through {@code getBeanNamesForType(Listener.class)}, so this bean
     * is never returned by that lookup and its handler can never fire (Codex review of PR #427).
     */
    @EventListener
    public static class AnnotatedButNotAListener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            // no-op: the scanner reads the annotation, never invokes this method
        }
    }
}
