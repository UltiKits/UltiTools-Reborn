package com.ultikits.ultitools.entities;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;

import lombok.Getter;

/**
 * Internal representation of a registered event handler.
 *
 * @since 6.2.2
 */
@Getter
public class HandlerEntry implements Comparable<HandlerEntry> {
    private final Class<? extends ModuleEvent> eventType;
    private final EventPriority priority;
    private final boolean ignoreCancelled;
    private final String ownerModule;

    /**
     * The module instance that registered this handler, or {@code null} when the registration
     * named only a module name (#506). {@code EventBus#unregisterByOwnerInstance} releases by it.
     *
     * @since 6.3.0
     */
    private final UltiToolsPlugin ownerInstance;

    // Annotation-based handler
    private final Method method;
    private final Object instance;

    // Programmatic handler
    private final Consumer<? extends ModuleEvent> consumer;

    /**
     * Constructor for annotation-based handlers.
     */
    public HandlerEntry(Class<? extends ModuleEvent> eventType, EventPriority priority,
                        boolean ignoreCancelled, String ownerModule,
                        Method method, Object instance) {
        this(eventType, priority, ignoreCancelled, ownerModule, null, method, instance);
    }

    /**
     * Constructor for annotation-based handlers that records the registering module instance
     * (#506).
     *
     * @param eventType       the event type handled
     * @param priority        the dispatch priority
     * @param ignoreCancelled whether a cancelled event skips this handler
     * @param ownerModule     the owning module's name
     * @param ownerInstance   the owning module instance, or {@code null}
     * @param method          the handler method
     * @param instance        the bean the method is invoked on
     * @since 6.3.0
     */
    public HandlerEntry(Class<? extends ModuleEvent> eventType, EventPriority priority,
                        boolean ignoreCancelled, String ownerModule, UltiToolsPlugin ownerInstance,
                        Method method, Object instance) {
        this.eventType = eventType;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.ownerModule = ownerModule;
        this.ownerInstance = ownerInstance;
        this.method = method;
        this.instance = instance;
        this.consumer = null;
    }

    /**
     * Constructor for programmatic handlers.
     */
    public HandlerEntry(Class<? extends ModuleEvent> eventType, EventPriority priority,
                        boolean ignoreCancelled, String ownerModule,
                        Consumer<? extends ModuleEvent> consumer) {
        this.eventType = eventType;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.ownerModule = ownerModule;
        this.ownerInstance = null;
        this.method = null;
        this.instance = null;
        this.consumer = consumer;
    }

    public boolean isProgrammatic() {
        return consumer != null;
    }

    @Override
    public int compareTo(HandlerEntry other) {
        return Integer.compare(this.priority.ordinal(), other.priority.ordinal());
    }
}
