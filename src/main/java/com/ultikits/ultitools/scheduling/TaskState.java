package com.ultikits.ultitools.scheduling;

/**
 * Task lifecycle. One-shot: SCHEDULED to RUNNING to COMPLETED or FAILED, or SCHEDULED to
 * CANCELLED or RETIRED. Repeating: SCHEDULED to RUNNING and back, ending CANCELLED or RETIRED,
 * never FAILED. Constants may be added in a 7.x minor release.
 * A one-shot task that has started always ends COMPLETED or FAILED: cancellation, owner unload and
 * entity removal do not affect a run in progress.
 */
public enum TaskState {
    /** Queued for its next execution. */
    SCHEDULED,
    /** Currently executing. */
    RUNNING,
    /** A one-shot execution returned normally. */
    COMPLETED,
    /** A one-shot execution threw. */
    FAILED,
    /** Future executions were cancelled, including owner unload. */
    CANCELLED,
    /** The owning entity was removed before execution. */
    RETIRED
}
