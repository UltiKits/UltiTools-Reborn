package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used. Its {@code plugin.yml} (built
 * by the test) declares the SAME {@code name:} as {@link JarModuleDuplicateNameB} - two modules
 * claiming one manifest name (#361 / IN-02). This one is loaded first in the fixture's declared
 * order, so it is the alias map's winner; {@link JarModuleDuplicateNameB} is the loser the
 * operator must be warned about.
 */
public class JarModuleDuplicateNameA extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
