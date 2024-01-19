package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.error;
import static com.ultikits.ultitools.utils.MessageUtils.info;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"setspawn"}, manualRegister = true, permission = "ultikits.tools.command.setspawn", description = "重生点功能")
public class SetSpawnCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void setSpawn(@CmdSender Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            player.sendMessage(error(BasicFunctions.getInstance().i18n("未找到世界！")));
            return;
        }
        world.setSpawnLocation(location);
        player.sendMessage(info(BasicFunctions.getInstance().i18n("已重设世界重生点！")));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "setspawn" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("设置世界重生点"));
    }
}
