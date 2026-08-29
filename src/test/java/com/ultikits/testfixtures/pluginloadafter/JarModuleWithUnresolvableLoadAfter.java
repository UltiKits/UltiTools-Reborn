package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. Its {@code plugin.yml} (built
 * by the test) declares a {@code loadAfter} entry naming a module that is never installed
 * alongside it, so the entry must resolve as inert rather than an error (Paper parity).
 */
public class JarModuleWithUnresolvableLoadAfter extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
