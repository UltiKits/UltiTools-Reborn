package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.testfixtures.missingdependency.MissingDependencyType;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: illegally extends {@link SealedBase}
 * <em>and</em> declares a method referencing
 * {@link com.ultikits.testfixtures.missingdependency.MissingDependencyType}, so that when loaded
 * through a class loader that hides that type, {@code FinalContractValidator.validate} must still
 * report the "extends a sealed class" violation - found before the method loop runs - even though
 * the method loop itself cannot complete. See
 * {@code com.ultikits.testfixtures.missingdependency}'s package-info for the class loader trick and
 * issue #190 for why the method loop can throw {@link NoClassDefFoundError} at all.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class IllegalSubclassWithMissingTypeMethod extends SealedBase {

    public MissingDependencyType methodWithMissingType() {
        return null;
    }
}
