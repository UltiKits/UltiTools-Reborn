package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.interfaces.impl.SimpleTempListener;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.function.Function;

/**
 * Temporary listener.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
public interface TempListener extends Listener {
    /**
     * Register the listener.
     */
    void register();

    /**
     * Unregister the listener.
     */
    default void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * Create a common temporary listener builder.
     *
     * @param eventClass Event class
     * @param <E>        Event type
     * @return Builder
     */
    static <E extends Event> DefaultTempListenerBuilder<E> common(Class<E> eventClass) {
        return new DefaultTempListenerBuilder<>(eventClass);
    }

    /**
     * Create a default temporary listener builder.
     *
     * @param <E>        Event type
     */
    class DefaultTempListenerBuilder<E extends Event> {
        private final Class<E> eventClass;
        private TempEventHandler<E> eventHandler;
        private EventPriority priority = EventPriority.NORMAL;
        private Function<E, Boolean> filter = (ignored) -> true;

        /**
         * Constructor.
         *
         * @param eventClass Event class
         */
        public DefaultTempListenerBuilder(Class<E> eventClass) {
            this.eventClass = eventClass;
        }

        /**
         * Set the event handler.
         *
         * @param eventHandler Event handler
         * @return Builder
         */
        public DefaultTempListenerBuilder<E> eventHandler(TempEventHandler<E> eventHandler) {
            this.eventHandler = eventHandler;
            return this;
        }

        /**
         * Set the priority.
         *
         * @param priority Priority
         * @return Builder
         */
        public DefaultTempListenerBuilder<E> priority(EventPriority priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Set the filter.
         *
         * @param filter Filter
         * <br>
         * Return true to handle the event, false to ignore it.
         * @return Builder
         */
        public DefaultTempListenerBuilder<E> filter(Function<E, Boolean> filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Build the listener.
         *
         * @return Listener
         */
        public TempListener build() {
            return new SimpleTempListener<>(eventClass, priority, eventHandler, filter);
        }

        /**
         * Register the listener.
         *
         * @param handler Event handler
         */
        public void listen(TempEventHandler<E> handler) {
            new SimpleTempListener<>(eventClass, priority, handler, filter).register();
        }
    }
}
