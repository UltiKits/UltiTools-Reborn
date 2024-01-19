package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"heal", "h"}, manualRegister = true, permission = "ultikits.tools.command.heal", description = "指令治愈功能")
public class HealCommand extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void heal(@CmdSender Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.sendMessage(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("你已被治愈！"));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("§c/heal §7治愈自己"));
    }
}
