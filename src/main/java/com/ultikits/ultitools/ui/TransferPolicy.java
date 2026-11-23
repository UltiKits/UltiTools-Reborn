package com.ultikits.ultitools.ui;

import java.util.Objects;

/** Decides each transfer before it is applied. Runs on the container's thread; must not block. */
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
