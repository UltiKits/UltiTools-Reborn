package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. See
 * {@link JarModuleMutualLoadAfterX} for the mutual {@code loadAfter} pairing this class forms
 * with it.
 */
public class JarModuleMutualLoadAfterY extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
