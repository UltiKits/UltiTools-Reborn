/**
 * Fixtures for the {@code @Bean} alias lifecycle tests: a {@code @Configuration} whose single
 * {@code @Bean} method binds one product under two names, and that product, which carries a
 * {@code @Scheduled} method, a {@code @ModuleEventHandler} method and a {@code @PreDestroy}
 * method.
 * <p>
 * Only two tests scan this package, each by its explicit name:
 * {@code com.ultikits.ultitools.context.SimpleContainerLifecycleDedupTest$AliasIdentityTests} and
 * {@code com.ultikits.ultitools.manager.PluginManagerAliasLifecycleTest}. The package lives
 * outside {@code com.ultikits.ultitools.context} and {@code com.ultikits.ultitools.manager} on
 * purpose: {@code ComponentScanner.scanDirectory} recurses into subpackages, so a
 * {@code @Configuration} compiled under either of those packages would be swept into every
 * unrelated test that scans them.
 */
package com.ultikits.testfixtures.beanalias;
