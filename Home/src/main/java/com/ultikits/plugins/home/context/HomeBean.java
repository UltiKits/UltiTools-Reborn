package com.ultikits.plugins.home.context;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HomeBean {
    final DataStore dataStore;

    public HomeBean(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Bean
    public PluginMain getPluginMain() {
        return PluginMain.getPluginMain();
    }

    @Bean
    public DataOperator<HomeEntity> getDataOperator() {
        return dataStore.getOperator(PluginMain.getPluginMain(), HomeEntity.class);
    }

    @Bean
    public HomeConfig getConfig() {
        return PluginMain.getPluginMain().getConfig("config/config.yml", HomeConfig.class);
    }
}
