package com.ultikits.ultitools.events;

/**
 * Priority levels for module event handlers.
 * Handlers execute in order from LOWEST to MONITOR.
 * <p>
 * 模块事件处理器的优先级。
 * 处理器按从 LOWEST 到 MONITOR 的顺序执行。
 *
 * @since 6.2.2
 */
public enum EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    MONITOR
}
