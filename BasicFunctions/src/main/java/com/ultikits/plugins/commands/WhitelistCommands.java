package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.data.WhiteListData;
import com.ultikits.plugins.services.WhiteListService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.warning;

public class WhitelistCommands implements TabExecutor {
    private final WhiteListService whiteListService = new WhiteListService();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (player.hasPermission("ultikits.tools.admin") || player.hasPermission("ultikits.tools.whitelist")) {
                return whiteListCommands(sender, command, args);
            } else {
                player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
                return true;
            }
        } else {
            return whiteListCommands(sender, command, args);
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("----白名单系统帮助----"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl help 白名单帮助"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl list 白名单列表"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl add [玩家名] 添加玩家到白名单"));
        sender.sendMessage(ChatColor.AQUA + BasicFunctions.getInstance().i18n("/wl remove [玩家名] 将玩家移出白名单"));
    }

    private boolean whiteListCommands(CommandSender sender, Command command, String[] args) {
        if ("wl".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                switch (args[0]) {
                    case "help":
                        sendHelpMessage(sender);
                        return true;
                    case "list":
                        List<WhiteListData> whitelist = whiteListService.getAllWhiteList();
                        sender.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("白名单列表有："));
                        for (WhiteListData each : whitelist) {
                            sender.sendMessage(String.format("- %s", each.getName()));
                        }
                        return true;
                    default:
                        return false;
                }
            } else if (args.length == 2) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[1]);
                if ("add".equalsIgnoreCase(args[0])) {
                    whiteListService.addWhiteList(String.valueOf(offlinePlayer.getUniqueId()), args[1]);
                    sender.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("已将%s加入白名单！"), args[1]));
                    return true;
                } else if ("remove".equalsIgnoreCase(args[0])) {
                    whiteListService.removeWhiteList(String.valueOf(offlinePlayer.getUniqueId()));
                    sender.sendMessage(ChatColor.RED + String.format(BasicFunctions.getInstance().i18n("已将%s移出白名单！"), args[1]));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (player.isOp() || player.hasPermission("ultikits.tools.whitelist")) {
                if (args.length == 1) {
                    List<String> tabCommands = new ArrayList<>();
                    tabCommands.add("help");
                    tabCommands.add("list");
                    tabCommands.add("add");
                    tabCommands.add("remove");
                    return tabCommands;
                } else if (args.length == 2) {
                    List<String> tabCommands = new ArrayList<>();
                    tabCommands.add(BasicFunctions.getInstance().i18n("[玩家名]"));
                    return tabCommands;
                }
            }
        }
        return null;
    }
}
