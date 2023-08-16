package com.ultikits.plugins.home;

import com.ultikits.plugins.home.commands.HomeCommands;
import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.plugins.home.services.HomeServiceImpl;
import com.ultikits.plugins.home.services.HomeServiceRegister;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

public class PluginMain extends UltiToolsPlugin {
    @Getter
    private static PluginMain pluginMain;
    private HomeServiceRegister homeServiceRegister;

    @Override
    public boolean registerSelf() {
        pluginMain = this;
        homeServiceRegister = new HomeServiceRegister(HomeService.class, new HomeServiceImpl());
        getCommandManager().register(new HomeCommands(), "ultikits.home.command.all", this.i18n("家功能"), "home");
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
        getListenerManager().unregisterAll(this);
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    @Override
    public List<AbstractConfigEntity> getAllConfigs() {
        return Arrays.asList(
                new HomeConfig("config/config.yml")
        );
    }
}
