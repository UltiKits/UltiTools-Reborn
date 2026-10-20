package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Configuration;
import org.jetbrains.annotations.ApiStatus;

/**
 * Marker configuration class for the framework's own context. It no longer declares a scan:
 * the scan declaration this class used to carry was removed in 6.3.0 because nothing ever
 * invoked {@link SimpleContainer#processConfigurationClass} against it in production - only
 * tests did (see {@code FinalContractValidator}'s "Known gaps" javadoc for the full story).
 */
@Configuration
@ApiStatus.Internal
public class ContextConfig {
}
