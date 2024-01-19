package com.ultikits.plugins.home.commands;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.gui.HomeListGui;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(permission = "ultikits.home.command.all", description = "家功能", alias = {"home"})
public class HomeCommands extends AbstractCommendExecutor {
    private final HomeService homeService;

    public HomeCommands(HomeService homeService) {
        this.homeService = homeService;
    }

    @CmdMapping(format = "list")
    public void openList(@CmdSender Player player) {
        HomeListGui homeListGui = new HomeListGui(player);
        homeListGui.open();
    }

    @CmdMapping(format = "create <name>")
    public void createHome(@CmdSender Player player,
                           @CmdParam(value = "name", suggest = "[name]") String name) {
        boolean created = homeService.createHome(player, name);
        if (created) {
            player.sendMessage(ChatColor.YELLOW + PluginMain.getPluginMain().i18n("已创建！"));
        } else {
            player.sendMessage(ChatColor.RED + PluginMain.getPluginMain().i18n("创建失败！\n你可能已经有这个家了！"));
        }
    }

    @CmdMapping(format = "del <name>")
    public void deleteHome(@CmdSender Player player,
                           @CmdParam(value = "name", suggest = "getHomeList") String name) {
        homeService.deleteHome(player.getUniqueId(), name);
    }

    @CmdMapping(format = "tp <name>")
    public void goHome(@CmdSender Player player,
                       @CmdParam(value = "name", suggest = "getHomeList") String name) {
        homeService.goHome(player, name);
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        String help = "=== 家功能 ===\n" +
                "/home list 打开家列表界面\n" +
                "/home create [家名字] 创建一个家\n" +
                "/home del [家名字] 删除一个家\n" +
                "/home tp [家名字] 传送到一个家\n" +
                "===========";
        sender.sendMessage(PluginMain.getPluginMain().i18n(help));
    }

    private List<String> getHomeList(Player player) {
        return homeService.getHomeNames(player.getUniqueId());
    }
}
