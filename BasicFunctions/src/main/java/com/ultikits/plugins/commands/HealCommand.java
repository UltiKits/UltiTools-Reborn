package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.warning;

public class HealCommand extends AbstractPlayerCommandExecutor {

    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        if (player.hasPermission("ultikits.tools.command.heal")) {
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("你已被治愈！"));
            return true;
        }
        player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
        return true;
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {

    }
}
