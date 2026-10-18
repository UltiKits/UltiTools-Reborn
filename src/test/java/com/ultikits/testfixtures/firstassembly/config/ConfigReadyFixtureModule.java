package com.ultikits.testfixtures.firstassembly.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataOperator;

/**
 * C09 fixture: the module main class {@code @Autowired}s two services, so registering the plugin
 * singleton creates both:
 * <ul>
 *   <li>{@link ConfigNeedingService} needs the module's config entity {@link ReadyConfig}, so the
 *       config entities must already be registered as beans;</li>
 *   <li>{@link ScopeObservingService}'s {@code @PostConstruct} asks the module for a data operator
 *       and records the container it sees, so the module's data scope and container must already
 *       be set.</li>
 * </ul>
 * The test supplies the {@link ReadyConfig} instance through the stubbed
 * {@code ConfigManager.getAllConfigEntities}; nothing here registers it.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.config")
public class ConfigReadyFixtureModule extends UltiToolsPlugin {

    @Autowired
    private ConfigNeedingService configNeedingService;

    @Autowired
    private ScopeObservingService scopeObservingService;

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** @return the injected config-dependent service, or {@code null} */
    public ConfigNeedingService getConfigNeedingService() {
        return configNeedingService;
    }

    /** @return the injected observing service, or {@code null} */
    public ScopeObservingService getScopeObservingService() {
        return scopeObservingService;
    }

    /** The module's config entity, registered as a bean from {@code getAllConfigEntities}. */
    public static class ReadyConfig extends AbstractConfigEntity {

        /** Creates the entity for {@code config/first-assembly.yml}. */
        public ReadyConfig() {
            super("config/first-assembly.yml");
        }
    }

    /** A data entity this module asks an operator for during {@code @PostConstruct}. */
    public static class ReadyEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;
    }

    /** A service that needs the module's config entity. */
    @Service
    public static class ConfigNeedingService {

        @Autowired
        private ReadyConfig config;

        /** @return the injected config entity, or {@code null} */
        public ReadyConfig getConfig() {
            return config;
        }
    }

    /** A service whose {@code @PostConstruct} records what the module offers at that moment. */
    @Service
    public static class ScopeObservingService {

        @Autowired
        private UltiToolsPlugin plugin;

        private DataOperator<ReadyEntity> operatorDuringPostConstruct;
        private SimpleContainer contextDuringPostConstruct;

        @PostConstruct
        void observe() {
            operatorDuringPostConstruct = plugin.getDataOperator(ReadyEntity.class);
            contextDuringPostConstruct = plugin.getContext();
        }

        /** @return the operator {@code getDataOperator} returned during {@code @PostConstruct} */
        public DataOperator<ReadyEntity> getOperatorDuringPostConstruct() {
            return operatorDuringPostConstruct;
        }

        /** @return the container {@code getContext()} returned during {@code @PostConstruct} */
        public SimpleContainer getContextDuringPostConstruct() {
            return contextDuringPostConstruct;
        }
    }
}
