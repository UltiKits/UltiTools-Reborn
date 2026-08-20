package com.ultikits.testfixtures.finalviolation;

import com.ultikits.ultitools.annotations.Final;

/**
 * Test-only fixture for {@code ComponentScannerFinalContractTest}: a sealed type that a sibling
 * class in this package illegally extends, to prove the {@code @Final} contract check actually
 * halts scanning rather than being logged and swallowed.
 * <p>
 * Deliberately placed outside {@code com.ultikits.ultitools} so it is never picked up by a scan of
 * the framework's own package tree (e.g. {@code ContextConfig}'s
 * {@code @ComponentScan("com.ultikits.ultitools")}) - only the test that explicitly scans this
 * package sees it.
 */
@Final
public class SealedComponent {
}
