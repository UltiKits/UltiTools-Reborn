package com.ultikits.commands;

import com.ultikits.PluginMain;
import com.ultikits.abstracts.AbstractPlayerCommandExecutor;
import com.ultikits.entity.HomeEntity;
import com.ultikits.gui.HomeListView;
import com.ultikits.services.HomeService;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class HomeCommands extends AbstractPlayerCommandExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        Optional<HomeService> service = PluginManager.getService(HomeService.class);
        if (!service.isPresent()){
            throw new RuntimeException("未找到家服务！");
        }
        HomeService homeService = service.get();
        switch (strings[0]) {
            case "list":
                Inventory itemStacks = HomeListView.setUp(player);
                player.openInventory(itemStacks);
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
                homeService.goHome(player, strings[1]);
                break;
        }
        return true;
    }
}
