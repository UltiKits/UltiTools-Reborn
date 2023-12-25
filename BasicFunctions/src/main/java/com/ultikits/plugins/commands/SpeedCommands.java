package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;


public class SpeedCommands extends AbstractTabExecutor {
    List<String> speeds = Arrays.asList("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

    @Override
    protected boolean onPlayerCommand(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        if (player.hasPermission("ultikits.tools.command.speed")) {
            if (!speeds.contains(strings[0])) {
                return false;
            }
            player.setWalkSpeed(Float.parseFloat(strings[0]) / 10);
            player.setFlySpeed(Float.parseFloat(strings[0]) / 10);
            player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("行走/飞行速度已设置为%s，默认速度为2"), strings[0]));
            return true;
        }
        player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
        return true;
    }

    @Nullable
    @Override
    protected List<String> onPlayerTabComplete(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        if (!player.hasPermission("ultikits.tools.commands.speed")) {
            return null;
        }
        if (strings.length == 1) {
            return speeds;
        }
        return null;
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/speed <0-10> 设置速度")));
    }
}
