package com.ultikits.ultitools.buildtools.fixtures;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Deliberately violates D-18's static-surface invariant, in every shape
 * {@code CredentialStaticSurfaceInvariant} checks. Exists solely so
 * {@code CredentialStaticSurfaceInvariantTest} can prove its package-scanning mechanism is
 * non-vacuous -- pointed at this fixture package, the scan must find and report every one of these
 * members every time the test runs, not just once at authoring time. Never referenced from
 * {@code src/main}; compiled only into test classes.
 */
public final class CredentialSurfaceOffenderFixture {

    /**
     * WR-03: a {@code public static} FIELD typed as {@link StandInCredential} -- fields were entirely
     * outside this invariant's original scope, only {@code java.lang.reflect.Method} objects were
     * ever passed to {@code evaluate(Collection)}. Declared here, ahead of every method (PMD
     * FieldDeclarationsShouldBeAtStartOfClass, plan 16-10 Gate 2).
     */
    public static StandInCredential offendingLeakedField = null;

    private CredentialSurfaceOffenderFixture() {
    }

    /**
     * A public, non-final stand-in for a credential type. The protocol v1 {@code TokenEntity} these
     * fixtures were written against was removed in 6.4.0; the real guarded type,
     * {@code utils.ServerCredential}, is package-private and final, so it can neither be named here
     * nor subclassed. The synthetic cases evaluate against this stand-in so every branch of the rule
     * (including the subclass branch) stays exercised; the real type is pinned separately by
     * {@code utils.ServerCredentialSurfaceOffenderFixture}.
     */
    public static class StandInCredential {
    }

    /**
     * The original offending signature this fixture provided -- a {@code public static} method
     * returning a {@link StandInCredential} directly (the erased-type check), exactly the shape D-18
     * forbids anywhere in {@code utils}.
     *
     * @return always {@code null}; never actually called, only reflected over
     */
    public static StandInCredential offendByReturningToken() {
        return null;
    }

    /**
     * WR-03 (16-REVIEW-cloud.md): a {@code public static} method whose ERASED return type is
     * {@code Optional.class} -- {@link Method#getReturnType()} alone would miss this entirely -- but
     * whose generic return type ({@code Optional<StandInCredential>}) still hands the raw token out.
     *
     * @return always empty; never actually called, only reflected over
     */
    public static Optional<StandInCredential> offendByReturningOptionalOfToken() {
        return Optional.empty();
    }

    /**
     * WR-03: a {@code public static} method whose ERASED parameter type is {@code Consumer.class},
     * but whose generic parameter type ({@code Consumer<StandInCredential>}) still receives the raw token.
     *
     * @param onToken never actually invoked, only reflected over
     */
    public static void offendByAcceptingConsumerOfToken(Consumer<StandInCredential> onToken) {
        // no-op -- reflected over, never called
    }
}
