package com.ultikits.testfixtures.malformedalias.scanner;

/**
 * Test-only fixture for {@code ComponentScannerMalformedAliasPropagationTest}: the one class
 * carrying {@link MalformedComposedAnnotation}, so a directory-based
 * {@code ComponentScanner.scanPackage} walk over this package discovers the malformed
 * declaration exactly the way a module JAR's own composed annotation would be discovered.
 * <p>
 * See this package's {@code package-info} for why it must hold exactly one malformed
 * declaration, and the parent {@code malformedalias} package's {@code package-info} for why any
 * of this lives outside {@code com.ultikits.ultitools} at all.
 */
@MalformedComposedAnnotation
public class MalformedAliasFixture {
}
