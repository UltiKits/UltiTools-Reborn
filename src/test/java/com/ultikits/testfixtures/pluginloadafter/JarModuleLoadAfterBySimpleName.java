package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. Its {@code plugin.yml} (built
 * by the test) declares a {@code loadAfter} entry naming {@link JarModuleTarget}'s simple class
 * name rather than its {@code plugin.yml} {@code name:} value, proving the alias map resolves
 * both naming conventions (D-12).
 */
public class JarModuleLoadAfterBySimpleName extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
