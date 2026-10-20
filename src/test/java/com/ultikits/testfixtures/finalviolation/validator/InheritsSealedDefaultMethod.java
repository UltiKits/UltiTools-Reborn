package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: implements {@link SealedInterface}
 * without overriding {@code m()} - merely inheriting a {@code @Final} default method is not a
 * violation of anything, since {@code @Final} restricts overriding, not use. This class is not
 * itself a violation; it exists so {@link OverridesInheritedSealedDefaultMethod} can reach
 * {@code SealedInterface#m()} through a superclass's interface rather than declaring the interface
 * itself.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class InheritsSealedDefaultMethod implements SealedInterface {
}
