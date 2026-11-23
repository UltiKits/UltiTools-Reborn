package com.ultikits.ultitools.ui;

import java.util.Objects;

/**
 * Decides each transfer before it is applied. Runs on the container's thread; must not block.
 *
 * <p>A gesture is decided transfer by transfer, in order: one transfer for a pickup, place, quick move out or
 * one-sided hotbar swap; one per target slot for a quick move in or a spread; a take-out then a put-in for a swap.
 * When a transfer that is not part of a swap is decided, the container's getters already reflect every earlier
 * transfer of the same gesture that was allowed, so a policy that caps the container by reading
 * {@link SlotContainer#contents()} cannot be bypassed by a multi-slot gesture. Both halves of a swap see the
 * container as it was before the gesture, and the swap is applied only if both are allowed in full.
 * {@link TransferDecision#allowAtMost(int)} limits the one transfer it answers. Listeners run after the whole
 * gesture. A policy must not call the container's mutators ({@code setReadOnly}, {@code load}, {@code commit},
 * {@code drain}, {@code end}); doing so is not supported.
 */
@FunctionalInterface
public interface TransferPolicy {
    TransferDecision decide(TransferRequest request);
    /** Allows every proposed transfer. */
    static TransferPolicy allowAll() {
        return request -> {
            Objects.requireNonNull(request, "request");
            return TransferDecision.allow();
        };
    }
}
