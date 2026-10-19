package com.ultikits.testfixtures.linkageerror.configuration;

import com.ultikits.ultitools.annotations.Service;

/**
 * The control: a plain {@code @Service} in the same package as
 * {@link FailingInitializerConfiguration}, which must still be available after that configuration
 * class is skipped.
 */
@Service
public class ConfigurationLinkageSurvivor {
}
