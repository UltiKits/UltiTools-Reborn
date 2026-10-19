package com.ultikits.testfixtures.firstassembly.factory;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * C51 fixture: a {@code @Configuration} whose {@code @Bean} product has a required
 * {@code @Autowired} field of a {@code @Service} type in the same package. Whether the module
 * loads must not depend on whether its JAR lists the configuration class before or after the
 * service.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.factory")
public class FactoryOrderFixtureModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** Produces a {@link FactoryProduct}. */
    @Configuration
    public static class FactoryConfiguration {

        /** @return a product whose {@code @Autowired} service the container injects */
        @Bean
        public FactoryProduct factoryProduct() {
            return new FactoryProduct();
        }
    }

    /** A {@code @Bean} product that needs {@link FactoryService}; not itself a component. */
    public static class FactoryProduct {

        @Autowired
        private FactoryService service;

        /** @return the injected service, or {@code null} */
        public FactoryService getService() {
            return service;
        }
    }

    /** The service the product needs. */
    @Service
    public static class FactoryService {
    }
}
