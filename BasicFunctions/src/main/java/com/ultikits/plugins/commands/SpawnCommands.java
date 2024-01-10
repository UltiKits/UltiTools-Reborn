package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.error;
import static com.ultikits.ultitools.utils.MessageUtils.info;

public class SpawnCommands extends AbstractPlayerCommandExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        switch (command.getName()) {
            case "spawn":
                if (strings.length == 0) {
                    if (player.getLocation().getWorld() == null) {
                        player.sendMessage(error(BasicFunctions.getInstance().i18n("未找到世界！")));
                        return false;
                    }
                    if (player.getLocation().getWorld().getSpawnLocation().equals(new Location(player.getLocation().getWorld(), 0, 0, 0, 0, 0))) {
                        player.sendMessage(error(BasicFunctions.getInstance().i18n("这个世界没有重生点！")));
                        return false;
                    }
                    player.teleport(player.getLocation().getWorld().getSpawnLocation());
                    return true;
                }
            case "setspawn":
                if (strings.length == 0 && player.hasPermission("ultikits.tools.command.setspawn")) {
                    Location location = player.getLocation();
                    World world = location.getWorld();
                    if (world == null) {
                        player.sendMessage(error(BasicFunctions.getInstance().i18n("未找到世界！")));
                        return false;
                    }
                    world.setSpawnLocation(location);
                    player.sendMessage(info(BasicFunctions.getInstance().i18n("已重设世界重生点！")));
                    return true;
                }
            default:
                return false;
        }
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "spawn" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("传送到世界重生点"));
        sender.sendMessage(ChatColor.RED + "setspawn" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("设置世界重生点"));
    }
}
