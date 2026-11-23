package com.ultikits.ultitools.ui;

import java.util.Objects;

/**
 * Outcome of a shown intent.
 * @param <R> the answer type
 */
public sealed interface UiResult<R> permits UiResult.Completed, UiResult.Closed {
    /**
     * The player answered.
     * @param <R> answer type
     * @param value the answer
     */
    record Completed<R>(R value) implements UiResult<R> {
        /** Rejects a null answer. */
        public Completed { Objects.requireNonNull(value, "value"); }
    }

    /**
     * The intent ended without an answer.
     * @param <R> answer type
     * @param reason why it ended
     */
    record Closed<R>(CloseReason reason) implements UiResult<R> {
        /** Rejects a null reason. */
        public Closed { Objects.requireNonNull(reason, "reason"); }

        /** @throws IllegalStateException because a closed intent has no answer */
        @Override public R value() { throw new IllegalStateException("Intent closed: " + reason); }
    }

    /** Whether an answer was submitted. */
    default boolean isCompleted() { return this instanceof Completed; }
    /** The value. @throws IllegalStateException if {@link Closed} */
    R value();
}
