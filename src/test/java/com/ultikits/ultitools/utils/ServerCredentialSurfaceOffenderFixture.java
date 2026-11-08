package com.ultikits.ultitools.utils;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Deliberately violates the credential static-surface invariant with the real guarded type,
 * {@link ServerCredential}, in the {@code utils} package it lives in. It exists only so
 * {@code CredentialStaticSurfaceInvariantTest} can prove the rule it runs against the real
 * {@code utils} package still catches a {@link ServerCredential} leak (the protocol v1
 * {@code TokenEntity} the rule was written against was removed in 6.4.0). It lives in the test tree,
 * so the real-package scan over {@code target/classes} never sees it. Never called, only reflected
 * over.
 */
public final class ServerCredentialSurfaceOffenderFixture {

    /** A public static field typed as the credential. */
    public static ServerCredential leakedCredentialField = null;

    private ServerCredentialSurfaceOffenderFixture() {
    }

    /**
     * @return always {@code null}
     */
    public static ServerCredential leakCredential() {
        return null;
    }

    /**
     * @return always empty
     */
    public static Optional<ServerCredential> leakOptionalCredential() {
        return Optional.empty();
    }

    /**
     * @param onCredential never invoked
     */
    public static void acceptCredentialConsumer(Consumer<ServerCredential> onCredential) {
        // no-op -- reflected over, never called
    }
}
