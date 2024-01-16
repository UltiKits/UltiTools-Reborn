package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.data.WhiteListData;
import com.ultikits.plugins.services.WhiteListService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"wl"}, manualRegister = true, permission = "ultikits.tools.command.whitelist", description = "白名单功能")
public class WhitelistCommands extends AbstractCommendExecutor {
    @Autowired
    private WhiteListService whiteListService;

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("----白名单系统帮助----"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl help 白名单帮助"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl list 白名单列表"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl add [玩家名] 添加玩家到白名单"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl remove [玩家名] 将玩家移出白名单"));
    }

    @CmdMapping(format = "help", requireOp = true)
    public void help(@CmdSender CommandSender sender) {
        handleHelp(sender);
    }

    @CmdMapping(format = "list", requireOp = true)
    public void list(@CmdSender CommandSender sender) {
        List<WhiteListData> whitelist = whiteListService.getAllWhiteList();
        sender.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("白名单列表有："));
        for (WhiteListData each : whitelist) {
            sender.sendMessage(String.format("- %s", each.getName()));
        }
    }

    @CmdMapping(format = "add <name>", requireOp = true)
    public void add(@CmdSender CommandSender sender, String name) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(name);
        whiteListService.addWhiteList(String.valueOf(offlinePlayer.getUniqueId()), name);
        sender.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("已将%s加入白名单！"), name));
    }

    @CmdMapping(format = "remove <name>", requireOp = true)
    public void remove(@CmdSender CommandSender sender, String name) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(name);
        whiteListService.removeWhiteList(String.valueOf(offlinePlayer.getUniqueId()));
        sender.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("已将%s移出白名单！"), name));
    }

    @Override
    protected List<String> suggest(Player player, String[] strings) {
        if (player.isOp() || player.hasPermission("ultikits.tools.whitelist")) {
            if (strings.length == 1) {
                List<String> tabCommands = new ArrayList<>();
                tabCommands.add("help");
                tabCommands.add("list");
                tabCommands.add("add");
                tabCommands.add("remove");
                return tabCommands;
            } else if (strings.length == 2) {
                List<String> tabCommands = new ArrayList<>();
                tabCommands.add(BasicFunctions.getInstance().i18n("[玩家名]"));
                return tabCommands;
            }
        }
        return null;
    }
}
