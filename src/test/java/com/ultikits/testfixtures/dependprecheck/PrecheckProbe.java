package com.ultikits.testfixtures.dependprecheck;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts how often {@link PrecheckModule} was initialized. Loaded by the test's own class loader,
 * never from the module jar, so the test reads the same counter the module writes.
 */
public final class PrecheckProbe {

    /** Incremented by {@link PrecheckModule}'s static initializer. */
    public static final AtomicInteger INITIALIZED = new AtomicInteger();

    private PrecheckProbe() {
    }
}
