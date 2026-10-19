package com.ultikits.testfixtures.linkageerror.configuration;

import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;

/**
 * A {@code @Configuration} class whose static initializer fails, as one does when it reaches for
 * an optional plugin that is not installed: creating it throws {@link ExceptionInInitializerError}
 * (and {@link NoClassDefFoundError} on every later attempt). Only
 * {@code ComponentScannerConfigurationLinkageTest} initializes it.
 */
@Configuration
public class FailingInitializerConfiguration {

    private static final String VALUE = failAtInitialization();

    private static String failAtInitialization() {
        throw new IllegalStateException("simulated: an optional dependency is missing at class initialization");
    }

    /** @return never reached: the class cannot be initialized */
    @Bean
    public String linkageProduct() {
        return VALUE;
    }
}
