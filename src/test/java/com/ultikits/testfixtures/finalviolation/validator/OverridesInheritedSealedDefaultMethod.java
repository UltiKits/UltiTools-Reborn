package com.ultikits.testfixtures.finalviolation.validator;

/**
 * Test-only fixture for {@code FinalContractValidatorTest}: extends
 * {@link InheritsSealedDefaultMethod} (a class that implements {@link SealedInterface} but never
 * declares {@code m()} itself) and overrides {@code m()} here. {@code FinalContractValidator} can
 * only find the {@code @Final} declaration for this case by walking
 * {@code InheritsSealedDefaultMethod}'s own interfaces during the chain walk, not merely its
 * superclass - proving the walk reaches a {@code @Final} method declared on an interface of a
 * superclass, not only one reachable through the superclass chain directly.
 * <p>
 * See this package's {@code package-info} for why it is safe to hold more than one violation shape,
 * and the parent {@code finalviolation} package's {@code package-info} for why any of this lives
 * outside {@code com.ultikits.ultitools} at all.
 */
public class OverridesInheritedSealedDefaultMethod extends InheritsSealedDefaultMethod {
    @Override
    public void m() {
    }
}
