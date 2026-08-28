package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: an interface with a {@code @Final}
 * default method. {@link ImplementsSealedDefaultMethod} illegally overrides it directly;
 * {@link InheritsSealedDefaultMethod} legally inherits without overriding, and
 * {@link OverridesInheritedSealedDefaultMethod} illegally overrides it by way of
 * {@code InheritsSealedDefaultMethod}, reaching {@code @Final} through an interface of a
 * superclass rather than through the superclass chain itself.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public interface SealedInterface {
    @Final
    default void m() {
    }
}
