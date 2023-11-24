package com.ultikits.plugins.home;

import com.ultikits.plugins.home.commands.HomeCommands;
import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.plugins.home.services.HomeServiceImpl;
import com.ultikits.plugins.home.services.HomeServiceRegister;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.I18n;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@I18n({"zh", "en"})
public class PluginMain extends UltiToolsPlugin {
    @Getter
    private static PluginMain pluginMain;
    private HomeServiceRegister homeServiceRegister;

    @Override
    public boolean registerSelf() {
        pluginMain = this;
        homeServiceRegister = new HomeServiceRegister(HomeService.class, new HomeServiceImpl());
        getCommandManager().register(this, new HomeCommands());
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
        getListenerManager().unregisterAll(this);
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new HomeConfig("config/config.yml")
        );
    }
}
