package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.interfaces.impl.SimpleTempListener;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;

import java.util.function.Function;

/**
 * Temporary listener.
 * <p>
 * 临时监听器。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/event-listener.html#temporary-listener">Temporary Listener</a>
 */
public interface TempListener extends Listener {
    /**
     * Register the listener.
     * <br>
     * 注册监听器。
     */
    void register();

    /**
     * Unregister the listener.
     * <br>
     * 注销监听器。
     */
    default void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * Create a common temporary listener builder.
     * <br>
     * 创建一个普通临时监听器构建器。
     *
     * @param eventClass Event class <br> 事件类
     * @param <E>        Event type <br> 事件类型
     * @return Builder <br> 构建器
     */
    static <E extends Event> DefaultTempListenerBuilder<E> common(Class<E> eventClass) {
        return new DefaultTempListenerBuilder<>(eventClass);
    }

    /**
     * Create a default temporary listener builder.
     * <br>
     * 创建一个默认临时监听器构建器。
     *
     * @param <E>        Event type <br> 事件类型
     */
    class DefaultTempListenerBuilder<E extends Event> {
        private final Class<E> eventClass;
        private TempEventHandler<E> eventHandler;
        private EventPriority priority = EventPriority.NORMAL;
        private Function<E, Boolean> filter = (ignored) -> true;

        /**
         * Constructor.
         * <br>
         * 构造函数。
         *
         * @param eventClass Event class
         * <br>
         * 事件类
         */
        public DefaultTempListenerBuilder(Class<E> eventClass) {
            this.eventClass = eventClass;
        }

        /**
         * Set the event handler.
         * <br>
         * 设置事件处理器。
         *
         * @param eventHandler Event handler
         * <br>
         * 事件处理器
         * @return Builder
         * <br>
         * 构建器
         */
        public DefaultTempListenerBuilder<E> eventHandler(TempEventHandler<E> eventHandler) {
            this.eventHandler = eventHandler;
            return this;
        }

        /**
         * Set the priority.
         * <br>
         * 设置优先级。
         *
         * @param priority Priority
         * <br>
         * 优先级
         * @return Builder
         * <br>
         * 构建器
         */
        public DefaultTempListenerBuilder<E> priority(EventPriority priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Set the filter.
         * <br>
         * 设置过滤器。
         *
         * @param filter Filter
         * <br>
         * 过滤器
         * <br>
         * Return true to handle the event, false to ignore it.
         * <br>
         * 返回 true 来处理事件，返回 false 来忽略它。
         * @return Builder
         * <br>
         * 构建器
         */
        public DefaultTempListenerBuilder<E> filter(Function<E, Boolean> filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Build the listener.
         * <br>
         * 构建监听器。
         *
         * @return Listener
         * <br>
         * 监听器
         */
        public TempListener build() {
            return new SimpleTempListener<>(eventClass, priority, eventHandler, filter);
        }

        /**
         * Register the listener.
         * <br>
         * 注册监听器。
         *
         * @param handler Event handler
         * <br>
         * 事件处理器
         */
        public void listen(TempEventHandler<E> handler) {
            new SimpleTempListener<>(eventClass, priority, handler, filter).register();
        }
    }
}
