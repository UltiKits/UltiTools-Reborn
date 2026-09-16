package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. See
 * {@link JarModuleDuplicateNameA} for the shared {@code plugin.yml} {@code name:} collision this
 * class forms with it (#361 / IN-02).
 */
public class JarModuleDuplicateNameB extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }
}
