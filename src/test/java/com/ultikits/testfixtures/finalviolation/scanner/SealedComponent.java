package com.ultikits.testfixtures.finalviolation.scanner;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code ComponentScannerFinalContractTest}: a sealed type that
 * {@link ViolatingComponent} illegally extends, to prove the {@code @Final} contract check
 * actually halts scanning rather than being logged and swallowed.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one violation, and the
 * parent {@code finalviolation} package's {@code package-info} for why any of this lives outside
 * {@code com.ultikits.ultitools} at all.
 */
@Final
public class SealedComponent {
}
