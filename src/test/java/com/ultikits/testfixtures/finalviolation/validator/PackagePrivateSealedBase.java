package com.ultikits.testfixtures.finalviolation.validator;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: declares a <b>package-private</b>
 * {@code @Final} method. Per JLS 8.4.8.1, a package-private method is overridden only by a subclass
 * in the same package - a subclass in a different package that declares its own same-signature
 * package-private method is not overriding it at all, just coincidentally sharing a name. See
 * {@code FinalContractValidatorTest.CrossPackageShadowsSealedMethod}, which lives in a different
 * package on purpose to exercise exactly that non-override case.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one fixture shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all. This class is not itself a violation of anything.
 */
public class PackagePrivateSealedBase {
    @Final
    void sealedPackageMethod() {
    }
}
