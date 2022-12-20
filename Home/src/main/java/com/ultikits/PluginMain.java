package com.ultikits;

import cn.hutool.core.lang.Console;
import com.ultikits.commands.HomeCommands;
import com.ultikits.listeners.HomeListPageListener;
import com.ultikits.services.HomeService;
import com.ultikits.services.HomeServiceImpl;
import com.ultikits.services.HomeServiceRegister;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

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
        CommandManager.registerCommand(UltiTools.getInstance(), new HomeCommands(), "", this.i18n("家功能"), "home");
        Bukkit.getServer().getPluginManager().registerEvents(new HomeListPageListener(), UltiTools.getInstance());
        return true;
    }

    @Override
    public void unregisterSelf() {
        homeServiceRegister.unload();
    }

    @Override
    public void reloadSelf() {

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
