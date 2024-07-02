package com.ultikits.ultitools.interfaces.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.TempEventHandler;
import com.ultikits.ultitools.interfaces.TempListener;
import lombok.*;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

import java.util.function.Function;

/**
 * Simple temp listener.
 * <p>
 * 简单临时监听器。
 *
 * @param <E> Event type (事件类型)
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class SimpleTempListener<E extends Event> implements TempListener {
    private Class<E> eventClass;
    private EventPriority priority = EventPriority.NORMAL;
    private TempEventHandler<E> eventHandler;
    private Function<E, Boolean> filter = (ignored) -> true;

    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
    }

    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler, Function<E, Boolean> filter) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
        this.filter = filter;
    }

    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler, EventPriority priority) {
        this.eventClass = eventClass;
        this.priority = priority;
        this.eventHandler = eventHandler;
    }

    public void register() {
        Bukkit.getServer().getPluginManager().registerEvent(eventClass, this, priority,
                (ignored, event) -> {
                    try {
                        if (filter != null && !filter.apply((E) event)) {
                            return;
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
}
