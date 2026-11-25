package com.ultikits.ultitools.ui;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Options for one show. Immutable. */
public final class ShowOptions {
    private static final ShowOptions DEFAULTS = new ShowOptions(new Builder());
    private final @Nullable Duration timeout;
    private final boolean noTimeout;

    private ShowOptions(Builder builder) { timeout = builder.timeout; noTimeout = builder.noTimeout; }
    /**
     * Intent-dependent defaults: 10 minutes for Notice, Confirm, Form and Choice; none for Grid.
     * Detectable game interruptions end Dialogs with {@link CloseReason#INTERRUPTED}; other interruptions
     * still need this timeout. Select a suitable explicit timeout for long-lived Dialogs.
     */
    public static ShowOptions defaults() { return DEFAULTS; }
    /** Starts with intent-dependent defaults. */
    public static Builder builder() { return new Builder(); }
    /** Empty means the intent's default. */
    public Optional<Duration> timeout() { return Optional.ofNullable(timeout); }
    /** Whether no timeout applies at all (only honoured for Grid). */
    public boolean noTimeout() { return noTimeout; }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof ShowOptions other && Objects.equals(timeout, other.timeout)
                && noTimeout == other.noTimeout;
    }

    @Override public int hashCode() { return Objects.hash(timeout, noTimeout); }
    @Override public String toString() { return "ShowOptions[timeout=" + timeout + ", noTimeout=" + noTimeout + ']'; }

    /** A reusable, non-thread-safe builder; the last timeout selection wins. */
    public static final class Builder {
        private @Nullable Duration timeout;
        private boolean noTimeout;

        private Builder() {
            // All fields initially represent intent-dependent defaults.
        }

        /** @throws IllegalArgumentException if not positive or above 12 hours */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(12)) > 0) {
                throw new IllegalArgumentException("timeout must be positive and at most 12 hours");
            }
            this.timeout = timeout;
            noTimeout = false;
            return this;
        }

        /** Selects no timeout for Grid; other intents still use their default. */
        public Builder noTimeout() { timeout = null; noTimeout = true; return this; }
        /** Builds an immutable options snapshot. */
        public ShowOptions build() { return new ShowOptions(this); }
    }
}
