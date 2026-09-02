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
     * <br>
     * 追踪哪些监听器实例当前已注册，使得同一实例第二次调用 {@link #register()}
     * 是无操作，而不是静默在 Bukkit 层面重复注册。使用静态字段（而非实例字段）
     * 才不会改变 {@code @AllArgsConstructor} 生成的构造器签名。使用弱引用键，
     * 这样一个被直接丢弃（从未注销）的监听器不会泄露。
     */
    private static final Set<SimpleTempListener<?>> REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Hand-written rather than Lombok-generated so it can carry its own javadoc.
     * Lombok's {@code @NoArgsConstructor(onConstructor_ = @Deprecated(...))} form was
     * confirmed (via javap on the compiled class) to correctly carry
     * {@code since}/{@code forRemoval} into the class file under this project's
     * {@code -source 1.8} / JDK 21 toolchain, but a Lombok-generated member has no
     * source declaration to attach a {@code @deprecated} tag to.
     * <br>
     * 手写而非由 Lombok 生成，以便携带自己的 javadoc。
     *
     * @deprecated Use the four-argument
     * {@code (Class, EventPriority, TempEventHandler, Function)} constructor (Lombok-generated
     * via {@code @AllArgsConstructor}, so it has no source declaration to link to) or
     * {@link TempListener#common(Class)}. Scheduled for removal in 6.4.0.
     * <br>
     * 请使用四参构造器 {@code (Class, EventPriority, TempEventHandler, Function)}
     * （由 Lombok 的 {@code @AllArgsConstructor} 生成，没有源码声明可供链接）
     * 或 {@link TempListener#common(Class)}。计划在 6.4.0 中移除。
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public SimpleTempListener() {
    }

    /**
     * No filter, default priority. Indistinguishable at the call site from the other
     * three-and-fewer-argument constructors below - this confusability is exactly why
     * these constructors are being removed (T-05-37).
     * <br>
     * 无过滤器，默认优先级。
     *
     * @deprecated Use the four-argument
     * {@code (Class, EventPriority, TempEventHandler, Function)} constructor (Lombok-generated
     * via {@code @AllArgsConstructor}, so it has no source declaration to link to) or
     * {@link TempListener#common(Class)}. Scheduled for removal in 6.4.0.
     * <br>
     * 请使用四参构造器 {@code (Class, EventPriority, TempEventHandler, Function)}
     * （由 Lombok 的 {@code @AllArgsConstructor} 生成，没有源码声明可供链接）
     * 或 {@link TempListener#common(Class)}。计划在 6.4.0 中移除。
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
    }

    /**
     * Filter, default priority.
     * <br>
     * 有过滤器，默认优先级。
     *
     * @deprecated Use the four-argument
     * {@code (Class, EventPriority, TempEventHandler, Function)} constructor (Lombok-generated
     * via {@code @AllArgsConstructor}, so it has no source declaration to link to) or
     * {@link TempListener#common(Class)}. Scheduled for removal in 6.4.0.
     * <br>
     * 请使用四参构造器 {@code (Class, EventPriority, TempEventHandler, Function)}
     * （由 Lombok 的 {@code @AllArgsConstructor} 生成，没有源码声明可供链接）
     * 或 {@link TempListener#common(Class)}。计划在 6.4.0 中移除。
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler, Function<E, Boolean> filter) {
        this.eventClass = eventClass;
        this.eventHandler = eventHandler;
        this.filter = filter;
    }

    /**
     * No filter, explicit priority. This is the overload {@code build()} used to resolve
     * to before SILENT-12 was fixed - the last-parameter arity clash with the constructor
     * above (a {@code Function} vs. an {@code EventPriority} in the same position) is the
     * defect mechanism this deprecation window exists to remove.
     * <br>
     * 无过滤器，显式优先级。
     *
     * @deprecated Use the four-argument
     * {@code (Class, EventPriority, TempEventHandler, Function)} constructor (Lombok-generated
     * via {@code @AllArgsConstructor}, so it has no source declaration to link to) or
     * {@link TempListener#common(Class)}. Scheduled for removal in 6.4.0.
     * <br>
     * 请使用四参构造器 {@code (Class, EventPriority, TempEventHandler, Function)}
     * （由 Lombok 的 {@code @AllArgsConstructor} 生成，没有源码声明可供链接）
     * 或 {@link TempListener#common(Class)}。计划在 6.4.0 中移除。
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public SimpleTempListener(Class<E> eventClass, TempEventHandler<E> eventHandler, EventPriority priority) {
        this.eventClass = eventClass;
        this.priority = priority;
        this.eventHandler = eventHandler;
    }

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
