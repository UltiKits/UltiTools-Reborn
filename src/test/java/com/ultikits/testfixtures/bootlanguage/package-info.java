/**
 * Fixture module for the start-up language tests of plan 17-46 (#540, #459, #460):
 * {@link com.ultikits.testfixtures.bootlanguage.BootFixturePlugin} is copied into a throwaway jar
 * together with the {@code lang/*} entries a test declares and loaded from it, so that the
 * module's own constructor extracts resources and resolves its language against a real jar.
 * <p>
 * It lives in its own package, with nothing else in it, so that a real
 * {@code PluginManager#register(UltiToolsPlugin)} -- whose component scan covers the main class's
 * package -- finds no other class to scan.
 */
package com.ultikits.testfixtures.bootlanguage;
