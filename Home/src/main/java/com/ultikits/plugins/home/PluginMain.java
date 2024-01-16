package com.ultikits.plugins.home;

import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@Component
@ComponentScan
@I18n({"zh", "en"})
@EnableAutoRegister
public class PluginMain extends UltiToolsPlugin {
    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired
    private DataStore dataStore;

    @Getter
    private static PluginMain pluginMain;

    public PluginMain() {
        super();
        pluginMain = this;
    }

    @Override
    public boolean registerSelf() {
        return true;
    }

    @Override
    public void unregisterSelf() {
    }

    @Bean
    public HomeConfig homeConfig() {
        return getConfig("config/config.yml", HomeConfig.class);
    }
    @Bean
    public DataOperator<HomeEntity> dataOperator() {
        return dataStore.getOperator(pluginMain, HomeEntity.class);
    }
}
