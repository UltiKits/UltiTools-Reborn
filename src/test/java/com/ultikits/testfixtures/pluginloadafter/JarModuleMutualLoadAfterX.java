package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. Its {@code plugin.yml} (built
 * by the test) declares {@code loadAfter} on {@link JarModuleMutualLoadAfterY}'s plugin.yml
 * {@code name:}, while that class declares the mirror-image {@code loadAfter} back onto this
 * one's {@code name:} - a mutual {@code loadAfter} cycle.
 */
public class JarModuleMutualLoadAfterX extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
