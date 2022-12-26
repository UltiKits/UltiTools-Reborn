package com.ultikits;

import com.ultikits.commands.HomeCommands;
import com.ultikits.listeners.HomeListPageListener;
import com.ultikits.services.HomeService;
import com.ultikits.services.HomeServiceImpl;
import com.ultikits.services.HomeServiceRegister;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.util.Arrays;
import java.util.List;

public class PluginMain extends UltiToolsPlugin {
    private HomeServiceRegister homeServiceRegister;
    private static PluginMain pluginMain;

    public static PluginMain getPluginMain() {
        return pluginMain;
    }

    @Override
    public boolean registerSelf() {
        pluginMain = this;
        homeServiceRegister = new HomeServiceRegister(HomeService.class, new HomeServiceImpl());
        getCommandManager().registerCommand(new HomeCommands(), "ultikits.home.command.all", this.i18n("家功能"), "home");
        getListenerManager().registerListener(this, new HomeListPageListener());
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
        getListenerManager().unregisterAll(this);
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
