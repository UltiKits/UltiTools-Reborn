package com.ultikits.ultitools.scheduling;

import java.util.Objects;

/** Completes a task whose entity was removed before it could run. */
public final class TaskRetiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates an exception describing the retired task. */
    public TaskRetiredException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }
}
