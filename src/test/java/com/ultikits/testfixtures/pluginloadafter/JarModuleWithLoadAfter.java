package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. Its {@code plugin.yml}
 * (built by the test, not present on the compile-time classpath) declares a {@code loadAfter}
 * entry naming {@link JarModuleTarget}'s own {@code plugin.yml} {@code name:} value.
 */
public class JarModuleWithLoadAfter extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
