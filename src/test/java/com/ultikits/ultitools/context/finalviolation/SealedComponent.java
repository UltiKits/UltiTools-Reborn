package com.ultikits.ultitools.context.finalviolation;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code ComponentScannerFinalContractTest}: a sealed type that a sibling
 * class in this package illegally extends, to prove the {@code @Final} contract check actually
 * halts scanning rather than being logged and swallowed.
 */
@Final
public class SealedComponent {
}
