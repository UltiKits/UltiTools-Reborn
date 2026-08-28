package com.ultikits.testfixtures.finalviolation.chain.p1;

/**
 * Test-only fixture: legitimately widens {@link ChainRoot#m()} from package-private to
 * {@code public} from within {@code ChainRoot}'s own package - a real override per JLS 8.4.8.1,
 * and the step that makes {@code m()} reachable (and further overridable) from outside this
 * package. See the parent {@code chain} package-info for the full scenario.
 * <p>
 * This class is itself already a live {@code @Final} violation under the pre-existing
 * (non-transitive) check - it is the same shape as
 * {@code com.ultikits.testfixtures.finalviolation.validator.WideningOverrideOfSealedPackageMethod}.
 * It is not asserted on directly by name; {@link com.ultikits.testfixtures.finalviolation.chain.p2.ChainLeaf}
 * is the fixture that exercises the new transitive behaviour.
 */
public class ChainMiddle extends ChainRoot {
    @Override
    public void m() {
        // Intentionally empty: this fixture only exercises the widening-override shape
        // (see class javadoc), not runtime behavior.
    }
}
