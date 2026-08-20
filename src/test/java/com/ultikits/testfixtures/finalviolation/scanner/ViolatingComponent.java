package com.ultikits.testfixtures.finalviolation.scanner;

/**
 * Test-only fixture for {@code ComponentScannerFinalContractTest}: illegally extends
 * {@link SealedComponent}, which is annotated {@code @Final}.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one violation, and the
 * parent {@code finalviolation} package's {@code package-info} for why any of this lives outside
 * {@code com.ultikits.ultitools} at all.
 */
public class ViolatingComponent extends SealedComponent {
}
