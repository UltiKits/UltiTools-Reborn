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
        if (strings.length > 0 && "help".equals(strings[0])){
            sendHelpMessage(commandSender);
            return true;
        }
        if (commandSender instanceof Player) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        if (!onConsoleCommand(commandSender, command, strings)){
            sendErrorMessage(commandSender, command);
            return false;
        }
        return true;
    }

    protected abstract boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings);

    protected abstract void sendHelpMessage(CommandSender sender);
    private void sendErrorMessage(CommandSender sender, Command command){
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    protected String getHelpCommand(){
        return "help";
    }
}
