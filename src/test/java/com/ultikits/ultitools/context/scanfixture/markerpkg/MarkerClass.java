package com.ultikits.ultitools.context.scanfixture.markerpkg;

/**
 * A plain marker class used only as a {@code Class} reference for
 * {@code scanBasePackageClasses()}/{@code basePackageClasses()} tests (03-09, Task 2) -- it is
 * never itself scanned or registered as a bean; only its <em>package</em> is what
 * {@code getPluginScanPackages}/{@code processConfigurationClass} must add to the scan set.
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class MarkerClass {
    private MarkerClass() {
    }
}
