package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

public abstract class AbstractTabExecutor implements TabExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (strings.length > 0 && "help".equals(strings[0])){
            sendHelpMessage(commandSender);
            return true;
        }
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return false;
        }
        Player player = (Player) commandSender;
        if (!onPlayerCommand(command, strings, player)){
            sendErrorMessage(commandSender, command);
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        if (!(commandSender instanceof Player)) {
            return null;
        }
        Player player = (Player) commandSender;
        return onPlayerTabComplete(command, strings, player);
    }

    protected abstract boolean onPlayerCommand(Command command, String[] strings, Player player);

    protected abstract List<String> onPlayerTabComplete(Command command, String[] strings, Player player);

    protected abstract void sendHelpMessage(CommandSender sender);
    private void sendErrorMessage(CommandSender sender, Command command){
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    protected String getHelpCommand(){
        return "help";
    }
}
