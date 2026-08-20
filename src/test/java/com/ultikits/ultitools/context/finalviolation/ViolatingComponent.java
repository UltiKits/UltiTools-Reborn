package com.ultikits.ultitools.context.finalviolation;

/**
 * Test-only fixture for {@code ComponentScannerFinalContractTest}: illegally extends
 * {@link SealedComponent}, which is annotated {@code @Final}.
 */
public class ViolatingComponent extends SealedComponent {
}
