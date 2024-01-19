package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.info;


@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"speed"}, manualRegister = true, permission = "ultikits.tools.command.speed", description = "速度设置功能")
public class SpeedCommands extends AbstractCommendExecutor {
    List<String> speeds = Arrays.asList("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

    @CmdMapping(format = "<speed>")
    public void setSpeeds(@CmdSender Player player, @CmdParam(value = "speed", suggest = "speedSuggest") String speed) {
        if (!speeds.contains(speed)) {
            return;
        }
        player.setWalkSpeed(Float.parseFloat(speed) / 10);
        player.setFlySpeed(Float.parseFloat(speed) / 10);
        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("行走/飞行速度已设置为%s，默认速度为2"), speed));
    }

    public List<String> speedSuggest() {
        return speeds;
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/speed <0-10> 设置速度")));
    }
}
