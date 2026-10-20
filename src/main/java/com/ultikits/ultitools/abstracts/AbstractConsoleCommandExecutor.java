package com.ultikits.ultitools.abstracts;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;

/**
 * Abstract class representing a console command executor.
 *
 * @see CommandExecutor
 */
public abstract class AbstractConsoleCommandExecutor extends AbstractCommand {
    /**
     * @param commandSender the sender of the command
     * @param command       the command which was executed
     * @param s             the alias of the command which was used
     * @param strings       the arguments passed to the command, split by spaces
     * @return whether the command was executed successfully
     * @see CommandExecutor#onCommand(CommandSender, Command, String, String[])
     */
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (strings == null) {
            strings = new String[0];
        }
        if (strings.length > 0 && "help".equals(strings[0])) {
            sendHelpMessage(commandSender);
            return true;
        }
        if (commandSender instanceof Player) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只可以在后台执行这个指令！"));
            return false;
        }
        if (!onConsoleCommand(commandSender, command, strings)) {
            sendErrorMessage(commandSender, command);
            return false;
        }
        return true;
    }

    /**
     * Executes the given command, returning its success
     *
     * @param commandSender the sender of the command
     * @param command       the command which was executed
     * @param strings       the arguments passed to the command, split by spaces
     * @return whether the command was executed successfully
     */
    protected abstract boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings);

}
