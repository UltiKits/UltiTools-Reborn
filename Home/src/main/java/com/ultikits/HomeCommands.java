package com.ultikits;

import com.ultikits.abstracts.AbstractPlayerCommandExecutor;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class HomeCommands extends AbstractPlayerCommandExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        HomeService homeService = PluginManager.getService(HomeService.class);
        if (homeService == null){
            throw new RuntimeException("未找到家服务！");
        }
        switch (strings[0]) {
            case "list":
                List<HomeEntity> homeList = homeService.getHomeList(player.getUniqueId());
                List<String> homeListString = new ArrayList<>();
                homeList.forEach(home -> homeListString.add(home.getName()));
                player.sendMessage(ChatColor.YELLOW + PluginMain.getPluginMain().i18n("====家列表===="));
                player.sendMessage(homeListString.toString());
                break;
            case "create":
                boolean created = homeService.createHome(player, strings[1]);
                if (created) {
                    player.sendMessage(ChatColor.YELLOW + PluginMain.getPluginMain().i18n("已创建！"));
                } else {
                    player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("创建失败！\n你可能已经有这个家了！"));
                }
                break;
            case "del":
                homeService.deleteHome(player.getUniqueId(), strings[1]);
                break;
            case "tp":
                HomeEntity homeByName = homeService.getHomeByName(player.getUniqueId(), strings[1]);
                if (homeByName == null) {
                    player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("你没有这个家！"));
                    break;
                }
                player.teleport(homeByName.getHomeLocation());
                break;
        }
        return true;
    }
}
