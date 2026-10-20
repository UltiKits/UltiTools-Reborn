package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally implements
 * {@link SealedMarkerInterface}, which is annotated {@code @Final}.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class ImplementsSealedMarkerInterface implements SealedMarkerInterface {
}
