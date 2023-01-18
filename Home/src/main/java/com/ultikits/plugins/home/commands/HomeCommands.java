package com.ultikits.plugins.home.commands;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.gui.HomeListView;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class HomeCommands extends AbstractTabExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        Optional<HomeService> service = PluginMain.getPluginManager().getService(HomeService.class);
        if (!service.isPresent()) {
            throw new RuntimeException("未找到家服务！");
        }
        HomeService homeService = service.get();
        if (strings.length == 0) {
            return false;
        }
        switch (strings[0]) {
            case "list":
                player.openInventory(HomeListView.setUp(player).getInventory());
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
            default:
                return false;
        }
        return true;
    }

    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        switch (strings.length) {
            case 1:
                return Arrays.asList("list", "create", "del", "tp");
            case 2:
                if (!strings[1].equals("create") && !strings[1].contains("list")) {
                    Optional<HomeService> service = PluginMain.getPluginManager().getService(HomeService.class);
                    if (!service.isPresent()) {
                        throw new RuntimeException("未找到家服务！");
                    }
                    return service.get().getHomeNames(player.getUniqueId());
                }
                return new ArrayList<>();
            default:
                return new ArrayList<>();
        }
    }
}
