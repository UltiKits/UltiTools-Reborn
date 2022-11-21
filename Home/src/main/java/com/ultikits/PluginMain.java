package com.ultikits;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;

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
        CommandManager.registerCommand(new HomeCommands(), "", this.i18n("家功能"), "home");
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
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
