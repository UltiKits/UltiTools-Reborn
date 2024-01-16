package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.listeners.BackListener;
import com.ultikits.ultitools.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.warning;

public class BackCommands extends AbstractPlayerCommandExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        Location location = BackListener.getPlayerLastDeathLocation(player.getUniqueId());
        if (location != null) {
            player.teleport(location);
        } else {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("无法找到死亡地点！这可能是因为插件重载或者你还没死过哩。")));
        }
        return true;
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("/back 返回死亡地点"));
    }
}
