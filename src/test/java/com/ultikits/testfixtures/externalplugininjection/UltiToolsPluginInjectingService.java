package com.ultikits.testfixtures.externalplugininjection;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Service;

/**
 * A {@code @Service} whose only constructor takes {@code UltiToolsPlugin} -- unrelated to the
 * {@code JavaPlugin} type this plan's fix registers in the child container. Proves the new
 * child-container registration did not shadow an unrelated type's own parent-fallback
 * resolution (D-13).
 */
@Service
public class UltiToolsPluginInjectingService {

    private final UltiToolsPlugin injectedPlugin;

    public UltiToolsPluginInjectingService(UltiToolsPlugin injectedPlugin) {
        this.injectedPlugin = injectedPlugin;
    }

    public UltiToolsPlugin getInjectedPlugin() {
        return injectedPlugin;
    }
}
