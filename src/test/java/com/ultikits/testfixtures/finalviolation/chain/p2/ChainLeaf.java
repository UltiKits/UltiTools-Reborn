package com.ultikits.testfixtures.finalviolation.chain.p2;

import com.ultikits.testfixtures.finalviolation.chain.p1.ChainMiddle;

/**
 * Test-only fixture: overrides {@link ChainMiddle#m()} from a package different than both
 * {@code ChainMiddle} and {@link com.ultikits.testfixtures.finalviolation.chain.p1.ChainRoot}.
 * <p>
 * This is issue #190's cross-package transitive scenario: {@code FinalContractValidator.validate}
 * comparing this class's {@code m()} directly against {@code ChainRoot#m()} fails the
 * package-private access check, but the override genuinely holds transitively through
 * {@code ChainMiddle#m()} - a real, same-package widening override of the {@code @Final}
 * package-private method. See the parent {@code chain} package-info for the full scenario.
 */
public class ChainLeaf extends ChainMiddle {
    @Override
    public void m() {
    }
}
