package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.warning;

public class GMChangeCommand extends AbstractTabExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        if (player.hasPermission("ultikits.tools.command.gm.all")
                || player.hasPermission("ultikits.tools.command.gm.0")
                || player.hasPermission("ultikits.tools.command.gm.1")
                || player.hasPermission("ultikits.tools.command.gm.2")
                || player.hasPermission("ultikits.tools.command.gm.3")) {
            switch (strings[0]) {
                case "0":
                    if (player.hasPermission("ultikits.tools.command.gm.all")
                            || player.hasPermission("ultikits.tools.command.gm.0")) {
                        player.setGameMode(GameMode.SURVIVAL);
                        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("生存模式")));
                        return true;
                    }
                    player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
                    return true;
                case "1":
                    if (player.hasPermission("ultikits.tools.command.gm.all")
                            || player.hasPermission("ultikits.tools.command.gm.1")) {
                        player.setGameMode(GameMode.CREATIVE);
                        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("创造模式")));
                        return true;
                    }
                    player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
                    return true;
                case "2":
                    if (player.hasPermission("ultikits.tools.command.gm.all")
                            || player.hasPermission("ultikits.tools.command.gm.2")) {
                        player.setGameMode(GameMode.ADVENTURE);
                        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("冒险模式")));
                        return true;
                    }
                    player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
                    return true;
                case "3":
                    if (player.hasPermission("ultikits.tools.command.gm.all")
                            || player.hasPermission("ultikits.tools.command.gm.3")) {
                        player.setGameMode(GameMode.SPECTATOR);
                        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("旁观模式")));
                        return true;
                    }
                    player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
                    return true;
                default:
                    return false;
            }
        }
        player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
        return true;
    }

    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        return Arrays.asList("0", "1", "2", "3");
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "/gm <0/1/2/3>");
    }
}
