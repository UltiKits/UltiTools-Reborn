package com.ultikits.ultitools.entities;

/**
 * Handle returned by programmatic event subscriptions.
 * Call {@link #unsubscribe()} to stop receiving events.
 * <p>
 * 程序化事件订阅返回的句柄。
 * 调用 {@link #unsubscribe()} 以停止接收事件。
 *
 * @since 6.2.2
 */
public interface Subscription {
    void unsubscribe();
    boolean isActive();
}
