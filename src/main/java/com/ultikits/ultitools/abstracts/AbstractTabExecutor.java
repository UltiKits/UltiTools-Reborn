package com.ultikits.ultitools.abstracts;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Abstract class representing a tab command executor.
 *
 * @see TabExecutor
 */
public abstract class AbstractTabExecutor extends AbstractPlayerCommandExecutor implements TabCompleter {

    /**
     * @param commandSender the sender of the command
     * @param command       the command which was executed
     * @param s             the alias of the command which was used
     * @param strings       the arguments passed to the command, split by spaces
     * @return a list of possible completions for the specified command string.
     * @see TabExecutor#onTabComplete(CommandSender, Command, String, String[])
     */
    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        if (!(commandSender instanceof Player)) {
            return null;
        }
        Player player = (Player) commandSender;
        return onPlayerTabComplete(command, strings, player);
    }

    /**
     * @param command the command which was executed
     * @param strings the arguments passed to the command, split by spaces
     * @param player  the player who executed the command
     * @return a list of possible completions for the specified command string.
     */
    protected abstract List<String> onPlayerTabComplete(Command command, String[] strings, Player player);
}
