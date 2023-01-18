package com.ultikits.plugins.heal;

import com.ultikits.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import static com.ultikits.utils.MessagesUtils.warning;

public class CmdExecutor extends AbstractPlayerCommandExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        if (player.hasPermission("ultikits.tools.command.heal")) {
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.sendMessage(ChatColor.YELLOW + Heal.getInstance().i18n("heal_player"));
            return true;
        }
        player.sendMessage(warning(Heal.getInstance().i18n("no_permission")));
        return true;
    }
}
