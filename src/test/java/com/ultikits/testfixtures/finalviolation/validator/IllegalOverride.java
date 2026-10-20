package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally overrides
 * {@link OpenBase#sealedMethod()}, which is annotated {@code @Final}.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one violation shape, and
 * the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class IllegalOverride extends OpenBase {
    @Override
    public void sealedMethod() {
    }
}
