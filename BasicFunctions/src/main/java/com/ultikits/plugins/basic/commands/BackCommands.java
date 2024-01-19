package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.listeners.BackListener;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.warning;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"back"}, manualRegister = true, permission = "ultikits.tools.command.back", description = "返回死亡地点")
public class BackCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void back(@CmdSender Player player) {
        Location location = BackListener.getPlayerLastDeathLocation(player.getUniqueId());
        if (location != null) {
            player.teleport(location);
        } else {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("无法找到死亡地点！这可能是因为插件重载或者你还没死过哩。")));
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("/back 返回死亡地点"));
    }
}
