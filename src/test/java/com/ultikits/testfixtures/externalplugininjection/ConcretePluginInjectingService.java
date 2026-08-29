package com.ultikits.testfixtures.externalplugininjection;

import com.ultikits.ultitools.annotations.Service;

/**
 * A {@code @Service} whose only constructor takes {@link ConnectorPluginFixture} -- the real,
 * concrete {@code JavaPlugin} subclass loaded for this scan, standing in for "the connector's
 * own concrete plugin class" (SILENT-16, #331). {@code registerType} keys by exact {@code Class},
 * so resolving this parameter needs a second registration under
 * {@code adapter.getJavaPlugin().getClass()}, distinct from the {@code JavaPlugin.class}
 * registration {@link JavaPluginInjectingService} exercises.
 */
@Service
public class ConcretePluginInjectingService {

    private final ConnectorPluginFixture injectedPlugin;

    public ConcretePluginInjectingService(ConnectorPluginFixture injectedPlugin) {
        this.injectedPlugin = injectedPlugin;
    }

    public ConnectorPluginFixture getInjectedPlugin() {
        return injectedPlugin;
    }
}
