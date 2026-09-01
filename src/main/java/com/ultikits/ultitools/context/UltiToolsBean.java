package com.ultikits.ultitools.context;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import org.jetbrains.annotations.ApiStatus;

@Configuration
@ApiStatus.Internal
public class UltiToolsBean {
    @Bean
    public UltiTools getUltiTools() {
        return UltiTools.getInstance();
    }

    @Bean
    public DataStore getDataStore() {
        return UltiTools.getInstance().getDataStore();
    }

    @Bean
    public Language getLanguage() {
        return UltiTools.getInstance().getLanguage();
    }

    @Bean
    public ConfigManager getConfigManager() {
        return UltiTools.getInstance().getConfigManager();
    }

    @Bean
    public PluginManager pluginManager() {
        return UltiTools.getInstance().getPluginManager();
    }
}
