package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.text.Text;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Immutable. */
public final class TransferDecision {
    private static final TransferDecision ALLOW = new TransferDecision(Integer.MAX_VALUE, null);
    private static final TransferDecision REFUSE = new TransferDecision(0, null);
    private final int limit;
    private final @Nullable Text message;

    private TransferDecision(int limit, @Nullable Text message) { this.limit = limit; this.message = message; }
    /** Allows the whole requested amount. */
    public static TransferDecision allow() { return ALLOW; }
    /** Allows at most {@code amount} (non-negative; 0 refuses). @throws IllegalArgumentException if negative */
    public static TransferDecision allowAtMost(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return new TransferDecision(amount, null);
    }
    /** Refuses without a message. */
    public static TransferDecision refuse() { return REFUSE; }
    /** Refuses and sends {@code message} to the viewer (rendered in their locale). */
    public static TransferDecision refuse(Text message) {
        return new TransferDecision(0, Objects.requireNonNull(message, "message"));
    }
    /** The allowed part of {@code requested}. @throws IllegalArgumentException if requested is negative */
    public int allowedAmount(int requested) {
        if (requested < 0) {
            throw new IllegalArgumentException("requested must not be negative");
        }
        return Math.min(requested, limit);
    }
    /** The refusal message, if any. */
    public Optional<Text> message() { return Optional.ofNullable(message); }
}
