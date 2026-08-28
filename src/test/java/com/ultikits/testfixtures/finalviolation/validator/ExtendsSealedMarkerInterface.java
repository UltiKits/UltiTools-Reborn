package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally extends
 * {@link SealedMarkerInterface}, which is annotated {@code @Final}. Before this plan's change,
 * {@code FinalContractValidator.validate} returned immediately for any {@code clazz.isInterface()},
 * so this case was never enforced at all.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public interface ExtendsSealedMarkerInterface extends SealedMarkerInterface {
}
