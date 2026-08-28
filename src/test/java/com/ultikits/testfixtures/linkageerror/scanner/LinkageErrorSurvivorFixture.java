package com.ultikits.testfixtures.linkageerror.scanner;

import com.ultikits.ultitools.annotations.Component;

/**
 * Test-only control fixture for {@code ComponentScannerTest}'s LinkageError skip-and-continue
 * coverage: a plain, loadable {@code @Component} that must still register after its sibling
 * {@link LinkageErrorBreakingFixture} is skipped - on both scan modes.
 * <p>
 * See this package's {@code package-info} for the full rationale.
 */
@Component
public class LinkageErrorSurvivorFixture {
}
