package com.ultikits.ultitools.entities;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import com.ultikits.ultitools.events.EventPriority;
import com.ultikits.ultitools.events.ModuleEvent;

import lombok.Getter;

/**
 * Internal representation of a registered event handler.
 * <p>
 * 已注册事件处理器的内部表示。
 *
 * @since 6.2.2
 */
@Getter
public class HandlerEntry implements Comparable<HandlerEntry> {
    private final Class<? extends ModuleEvent> eventType;
    private final EventPriority priority;
    private final boolean ignoreCancelled;
    private final String ownerModule;

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
        this.eventType = eventType;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.ownerModule = ownerModule;
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
