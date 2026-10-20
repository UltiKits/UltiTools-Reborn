package com.ultikits.testfixtures.firstassembly.configautowire;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * Review round 1 fixture: the module's config entity {@link AutowiringConfig} has a required
 * {@code @Autowired} field of the module's own {@code @Service} {@link HelperService} (6.2.5
 * loaded such a module, because config entities were registered after the component scan), and
 * the {@code @Bean} product {@link ConfigReadingProduct} of a {@code @Configuration} class needs
 * the config entity while the scan creates it (C09). The test supplies the {@link AutowiringConfig}
 * instance through the stubbed {@code ConfigManager.getAllConfigEntities}.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.configautowire")
public class ConfigAutowireFixtureModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** A config entity that injects a service of its own module. */
    public static class AutowiringConfig extends AbstractConfigEntity {

        @Autowired
        private HelperService helper;

        /** Creates the entity for {@code config/config-autowire.yml}. */
        public AutowiringConfig() {
            super("config/config-autowire.yml");
        }

        /** @return the injected service, or {@code null} */
        public HelperService getHelper() {
            return helper;
        }
    }

    /** The service the config entity needs. */
    @Service
    public static class HelperService {
    }

    /** Produces a {@link ConfigReadingProduct} while the scan runs. */
    @Configuration
    public static class ProductConfiguration {

        /** @return a product whose {@code @Autowired} config entity the container injects */
        @Bean
        public ConfigReadingProduct configReadingProduct() {
            return new ConfigReadingProduct();
        }
    }

    /** A {@code @Bean} product that needs the module's config entity; not itself a component. */
    public static class ConfigReadingProduct {

        @Autowired
        private AutowiringConfig config;

        /** @return the injected config entity, or {@code null} */
        public AutowiringConfig getConfig() {
            return config;
        }
    }
}
