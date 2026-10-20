package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: a sealed type. {@link IllegalSubclass}
 * illegally extends it.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one violation shape, and
 * the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
@Final
public class SealedBase {
    public void ok() {
    }
}
