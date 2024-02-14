package com.ultikits.ultitools.interfaces;

import org.bukkit.event.Event;

/**
 * Temporary event handler.
 * <p>
 * 临时事件处理器。
 *
 * @param <E> Event type (事件类型)
 */
public interface TempEventHandler<E extends Event> {
    /**
     * @param event Event <br> 事件
     * @return Whether to unregister the listener <br> 是否注销监听器
     */
    boolean handle(E event);
}
