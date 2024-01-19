package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.error;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"spawn"}, manualRegister = true, permission = "ultikits.tools.command.spawn", description = "重生点功能")
public class SpawnCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "")
    public void spawn(@CmdSender Player player) {
        if (player.getLocation().getWorld() == null) {
            player.sendMessage(error(BasicFunctions.getInstance().i18n("未找到世界！")));
            return;
        }
        if (player.getLocation().getWorld().getSpawnLocation().equals(new Location(player.getLocation().getWorld(), 0, 0, 0, 0, 0))) {
            player.sendMessage(error(BasicFunctions.getInstance().i18n("这个世界没有重生点！")));
            return;
        }
        player.teleport(player.getLocation().getWorld().getSpawnLocation());
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "spawn" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("传送到世界重生点"));
    }
}
