package com.ultikits.plugins.home;

import com.ultikits.plugins.home.commands.HomeCommands;
import com.ultikits.plugins.home.config.HomeConfig;
import com.ultikits.plugins.home.listeners.HomeListPageListener;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.plugins.home.services.HomeServiceImpl;
import com.ultikits.plugins.home.services.HomeServiceRegister;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.util.Arrays;
import java.util.List;

public class PluginMain extends UltiToolsPlugin {
    private static PluginMain pluginMain;
    private HomeServiceRegister homeServiceRegister;

    public static PluginMain getPluginMain() {
        return pluginMain;
    }

    @Override
    public boolean registerSelf() {
        pluginMain = this;
        homeServiceRegister = new HomeServiceRegister(HomeService.class, new HomeServiceImpl());
        getCommandManager().register(new HomeCommands(), "ultikits.home.command.all", this.i18n("家功能"), "home");
        getListenerManager().register(this, new HomeListPageListener());
        getConfigManager().register(this, new HomeConfig("res/config/config.yml"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
        getListenerManager().unregisterAll(this);
    }

    @Override
    public int minUltiToolsVersion() {
        return 600;
    }

    @Override
    public String pluginName() {
        return "UltiTools-Home";
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}
