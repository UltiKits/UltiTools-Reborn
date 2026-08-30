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

    /**
     * Tracks which listener instances are currently registered, so a second {@link #register()}
     * call on the same instance is a no-op instead of a silent duplicate Bukkit registration.
     * Static (not an instance field) so it does not change {@code @AllArgsConstructor}'s generated
     * signature. Weak keys so a listener that is simply dropped (never unregistered) does not leak.
     * <br>
     * 追踪哪些监听器实例当前已注册，使得同一实例第二次调用 {@link #register()}
     * 是无操作，而不是静默在 Bukkit 层面重复注册。使用静态字段（而非实例字段）
     * 才不会改变 {@code @AllArgsConstructor} 生成的构造器签名。使用弱引用键，
     * 这样一个被直接丢弃（从未注销）的监听器不会泄露。
     */
    private static final Set<SimpleTempListener<?>> REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    public void register() {
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
     * <br>
     * 注销监听器并清除其注册追踪状态，使得同一实例之后再次调用
     * {@link #register()} 能真正重新注册。
     */
    @Override
    public void unregister() {
        REGISTERED.remove(this);
        TempListener.super.unregister();
    }
}
