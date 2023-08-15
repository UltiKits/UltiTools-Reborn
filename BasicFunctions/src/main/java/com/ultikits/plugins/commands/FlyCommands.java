package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;

public class FlyCommands extends AbstractPlayerCommandExecutor {

    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        if (!player.hasPermission("ultikits.tools.command.fly")) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("你没有权限使用此指令！")));
            return true;
        }
        player.setAllowFlight(!player.getAllowFlight());
        player.sendMessage(info(player.getAllowFlight() ?
                BasicFunctions.getInstance().i18n("已打开飞行") :
                BasicFunctions.getInstance().i18n("已关闭飞行")));
        return true;
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("用法：/fly")));
    }
}
