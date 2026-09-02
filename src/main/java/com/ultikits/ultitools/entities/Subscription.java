package com.ultikits.ultitools.entities;

/**
 * Handle returned by programmatic event subscriptions.
 * Call {@link #unsubscribe()} to stop receiving events.
 *
 * @since 6.2.2
 */
public interface Subscription {
    void unsubscribe();
    boolean isActive();
}
