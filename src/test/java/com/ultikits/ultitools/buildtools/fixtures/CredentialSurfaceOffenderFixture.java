package com.ultikits.ultitools.buildtools.fixtures;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * Deliberately violates D-18's static-surface invariant: a {@code public static} method returning
 * {@link TokenEntity}. Exists solely so {@code CredentialStaticSurfaceInvariantTest} can prove its
 * package-scanning mechanism is non-vacuous -- pointed at this fixture package, the scan must find
 * and report this method every time the test runs, not just once at authoring time. Never referenced
 * from {@code src/main}; compiled only into test classes.
 */
public final class CredentialSurfaceOffenderFixture {

    private CredentialSurfaceOffenderFixture() {
    }

    /**
     * The one offending signature this fixture exists to provide -- a {@code public static} method
     * returning a {@link TokenEntity}, exactly the shape D-18 forbids anywhere in {@code utils}.
     *
     * @return always {@code null}; never actually called, only reflected over
     */
    public static TokenEntity offendByReturningToken() {
        return null;
    }
}
