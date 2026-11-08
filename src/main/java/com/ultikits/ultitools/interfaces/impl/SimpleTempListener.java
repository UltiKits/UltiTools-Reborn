package com.ultikits.ultitools.interfaces.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.TempEventHandler;
import com.ultikits.ultitools.interfaces.TempListener;
import lombok.*;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Simple temp listener.
 *
 * @param <E> Event type
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
@AllArgsConstructor
@Setter
@Getter
public class SimpleTempListener<E extends Event> implements TempListener {
    private Class<E> eventClass;
    private EventPriority priority = EventPriority.NORMAL;
    private TempEventHandler<E> eventHandler;
    private Function<E, Boolean> filter = (ignored) -> true;

    /**
     * Tracks which listener instances are currently registered, so a second {@link #register()}
     * call on the same instance is a no-op instead of a silent duplicate Bukkit registration.
     * Static (not an instance field) so it does not change {@code @AllArgsConstructor}'s generated
     * signature. Weak keys so a listener that is simply dropped (never unregistered) does not leak.
     *
     * <p>Thread contract: the set is shared by every temporary listener and is touched from any
     * thread that calls {@link #register()} and from any event thread whose handler returns
     * {@code true}, which is an asynchronous thread for events such as
     * {@code AsyncPlayerChatEvent}. It is therefore wrapped with
     * {@link Collections#synchronizedSet(Set)}, so every {@code add} and {@code remove}
     * synchronizes on this set's own monitor. That lock is held only for the set operation and
     * never across a Bukkit call ({@code registerEvent} or {@code HandlerList.unregisterAll}):
     * synchronous dispatch runs handlers, which may call {@link #unregister()}, under Bukkit's own
     * synchronization, and spanning both locks would invite a lock-order inversion.
     */
    private static final Set<SimpleTempListener<?>> REGISTERED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // GATE-05 group two (08-21): the ClassCastException wrap below is deliberately left raw, not
    // routed to the typed exception hierarchy. It runs inside Bukkit's own event-dispatch
    // callback, invoked by Bukkit's plugin manager rather than by any UltiTools API caller --
    // there is no framework-internal catch site that could ever distinguish a typed exception
    // from an untyped one here, since Bukkit's own dispatcher is what observes it. It also
    // asserts an effectively-impossible internal state: registerEvent only fires this callback
    // for the exact eventClass this listener registered (or a subclass), so the cast can only
    // fail if Bukkit's own dispatch contract is violated. See 08-GATE05-TRIAGE.md.
    /**
     * Register the listener with Bukkit. A second call on an instance that is still registered is
     * a no-op.
     *
     * <p>May be called on any thread. Distinct listeners may be registered and unregistered
     * concurrently from different threads; one instance must not be registered on one thread
     * while it is being unregistered on another.
     */
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    public void register() {
        if (eventClass == null) {
            throw new IllegalStateException(
                    "Cannot register a TempListener: eventClass is not set.");
        }
        if (eventHandler == null) {
            throw new IllegalStateException(
                    "Cannot register a TempListener: eventHandler is not set.");
        }
        if (!REGISTERED.add(this)) {
            // Already registered - a second register() call on the same instance is a no-op
            // rather than a silent duplicate Bukkit registration.
            return;
        }
        Bukkit.getServer().getPluginManager().registerEvent(eventClass, this, priority,
                (ignored, event) -> {
                    try {
                        if (filter != null) {
                            // filter.apply() may return a boxed null - Function<E, Boolean> permits
                            // it. Treat a null result as non-matching rather than letting the
                            // implicit unboxing throw an NPE out of Bukkit's event dispatch on
                            // every event of this class.
                            Boolean matches = filter.apply((E) event);
                            if (matches == null || !matches) {
                                return;
                            }
                        }
                        //noinspection unchecked
                        if (eventHandler.handle((E) event)) {
                            unregister();
                        }
                    } catch (ClassCastException e) {
                        throw new RuntimeException(e);
                    }
                },
                UltiTools.getInstance()
        );
    }

    /**
     * Unregister the listener and clear its registration tracking so a later {@link #register()}
     * call on the same instance actually re-registers.
     *
     * <p>May be called on any thread, including an asynchronous event thread when the handler
     * returns {@code true}. The registration marker is cleared under the shared set's lock first;
     * Bukkit's handler cleanup then runs outside that lock. The same-instance restriction of
     * {@link #register()} applies.
     */
    @Override
    public void unregister() {
        REGISTERED.remove(this);
        TempListener.super.unregister();
    }
}
