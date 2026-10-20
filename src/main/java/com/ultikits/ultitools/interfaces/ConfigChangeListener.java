package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

/**
 * Listener interface for configuration changes.
 * Implementations can react to configuration reloads.
 *
 * <pre>{@code
 * @Service
 * public class MyService {
 *     @Autowired
 *     private MyConfig config;
 *     
 *     @PostConstruct
 *     public void init() {
 *         config.addChangeListener(cfg -> {
 *             // Handle config reload
 *             refreshCache();
 *         });
 *     }
 * }
 * }</pre>
 *
 * @author wisdomme
 * @since 6.2.0
 */
@FunctionalInterface
public interface ConfigChangeListener {
    
    /**
     * Called when the configuration is reloaded.
     *
     * @param config the reloaded configuration object
     */
    void onConfigReload(AbstractConfigEntity config);
}
