package com.ultikits.testfixtures.linkageerror.scanner;

/**
 * Test-only fixture for {@code ComponentScannerTest}'s LinkageError skip-and-continue coverage.
 * <p>
 * This class is never actually loaded during those tests - the test-supplied {@code ClassLoader}
 * intercepts {@code loadClass} for this exact name and throws a chosen {@link LinkageError}
 * before delegating. It exists only so a real {@code .class} file with this name is present on
 * disk (directory mode) and can be named as a real entry in a hand-built temporary JAR (jar
 * mode).
 * <p>
 * See this package's {@code package-info} for the full rationale.
 */
public class LinkageErrorBreakingFixture {
}
