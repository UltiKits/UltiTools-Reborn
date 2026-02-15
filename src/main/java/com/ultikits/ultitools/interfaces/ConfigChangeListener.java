package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

/**
 * Listener interface for configuration changes.
 * Implementations can react to configuration reloads.
 * <p>
 * 配置变更监听器接口。
 * 实现类可以对配置重载做出响应。
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
     * <p>
     * 当配置重载时调用。
     *
     * @param config the reloaded configuration object <br> 重载后的配置对象
     */
    void onConfigReload(AbstractConfigEntity config);
}
