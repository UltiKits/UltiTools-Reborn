package com.ultikits.ultitools.interfaces.impl.data.json;

import java.util.concurrent.TimeUnit;

/**
 * Table write locks of the JSON backend: one per {@link JsonStore}.
 * <p>
 * Skeleton: acquiring and releasing do nothing yet.
 *
 * @since 6.3.0
 */
final class JsonLockTable {

    /** The default upper bound of one lock wait, in milliseconds (question 7, option A). */
    static final long DEFAULT_LOCK_WAIT_MILLIS = 5000L;

    private volatile long defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(DEFAULT_LOCK_WAIT_MILLIS);

    /** Test seam: sets the default upper bound of one lock wait. */
    void setDefaultWaitMillis(long millis) {
        defaultWaitNanos = TimeUnit.MILLISECONDS.toNanos(millis);
    }

    long defaultWaitNanos() {
        return defaultWaitNanos;
    }

    void acquire(SimpleJsonDataOperator<?> table, long deadlineNanos) {
        // Skeleton: no lock is taken yet.
    }

    void release(SimpleJsonDataOperator<?> table) {
        // Skeleton: no lock is taken yet.
    }
}
