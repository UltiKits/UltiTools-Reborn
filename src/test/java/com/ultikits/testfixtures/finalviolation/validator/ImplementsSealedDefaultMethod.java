package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally overrides
 * {@link SealedInterface#m()}, a {@code @Final} default method, directly.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class ImplementsSealedDefaultMethod implements SealedInterface {
    @Override
    public void m() {
    }
}
