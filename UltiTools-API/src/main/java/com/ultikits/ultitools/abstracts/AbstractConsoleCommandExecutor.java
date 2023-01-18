package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public abstract class AbstractConsoleCommandExecutor implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (commandSender instanceof Player) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        return onConsoleCommand(commandSender, command, strings);
    }

    protected abstract boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings);
}
