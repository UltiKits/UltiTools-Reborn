package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.info;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"fly"}, manualRegister = true, permission = "ultikits.tools.command.fly", description = "飞行功能")
public class FlyCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void fly(@CmdSender Player player) {
        player.setAllowFlight(!player.getAllowFlight());
        player.sendMessage(info(player.getAllowFlight() ?
                BasicFunctions.getInstance().i18n("已打开飞行") :
                BasicFunctions.getInstance().i18n("已关闭飞行")));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("用法：/fly")));
    }
}
