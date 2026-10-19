/**
 * Fixtures for {@code ComponentScannerConfigurationLinkageTest}: a {@code @Configuration} class that
 * fails to link when the scan creates it (after discovery, decision P-07) is skipped with the same
 * scan diagnostics as a class that fails to load, and the rest of the package still registers.
 * Kept in its own package, outside {@code com.ultikits.ultitools}, so no other scan meets the
 * failing class.
 */
package com.ultikits.testfixtures.linkageerror.configuration;
