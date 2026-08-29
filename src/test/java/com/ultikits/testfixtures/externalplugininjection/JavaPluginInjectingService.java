package com.ultikits.testfixtures.externalplugininjection;

import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.annotations.Service;

/**
 * A {@code @Service} whose only constructor takes the declared {@code JavaPlugin} type. Has no
 * no-arg constructor, so {@code SimpleContainer.createBean} falls into constructor auto-wiring
 * and resolves this parameter via {@code getBean(JavaPlugin.class)} -- exactly the lookup
 * SILENT-16 (#331) found returning the framework core's own {@code UltiTools} instance instead
 * of the external connector's.
 */
@Service
public class JavaPluginInjectingService {

    private final JavaPlugin injectedPlugin;

    public JavaPluginInjectingService(JavaPlugin injectedPlugin) {
        this.injectedPlugin = injectedPlugin;
    }

    public JavaPlugin getInjectedPlugin() {
        return injectedPlugin;
    }
}
