package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: an interface annotated
 * {@code @Final} at the type level, with no members of its own.
 * {@link ExtendsSealedMarkerInterface} illegally extends it; {@link ImplementsSealedMarkerInterface}
 * illegally implements it.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
@Final
public interface SealedMarkerInterface {
}
