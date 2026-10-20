package com.ultikits.testfixtures.finalviolation.chain.p1;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture: declares a <b>package-private</b> {@code @Final} method. Only a same-package
 * subclass - {@link ChainMiddle} - can legitimately override it (JLS 8.4.8.1). See this
 * subpackage's {@code p1} folder and the parent {@code chain} package-info for the full scenario.
 */
public class ChainRoot {
    @Final
    void m() {
        // Intentionally empty: this fixture only exercises override eligibility across the
        // p1/p2 package boundary (see class javadoc), not runtime behavior.
    }
}
