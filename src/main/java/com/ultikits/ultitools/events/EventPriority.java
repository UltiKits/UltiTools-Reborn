package com.ultikits.ultitools.events;

/**
 * Priority levels for module event handlers.
 * Handlers execute in order from LOWEST to MONITOR.
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
