package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: a type with one sealed method and one
 * open method. {@link IllegalOverride} illegally overrides the sealed one;
 * {@code FinalContractValidatorTest.LegalSubclass} legally overrides the open one.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one violation shape, and
 * the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class OpenBase {
    @Final
    public void sealedMethod() {
    }

    public void openMethod() {
    }
}
