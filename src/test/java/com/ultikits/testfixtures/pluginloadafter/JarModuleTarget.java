package com.ultikits.testfixtures.pluginloadafter;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * Never instantiated - only its {@code .class} reference is used, so
 * {@code UltiToolsPlugin}'s own plugin.yml-reading constructor never runs. Loaded fresh from a
 * synthetic single-entry JAR by the test so its code source is genuinely a JAR.
 */
public class JarModuleTarget extends UltiToolsPlugin {
    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
        // No-op fixture.
    }
}
