package com.ultikits.ultitools.abstracts;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Abstract class representing a tab command executor.
 * <p>
 * 支持Tab补全指令执行器的抽象类。
 *
 * @see TabExecutor
 */
public abstract class AbstractTabExecutor extends AbstractPlayerCommandExecutor implements TabCompleter {

    /**
     * @param commandSender the sender of the command <br> 指令发送者
     * @param command       the command which was executed <br> 被执行的指令
     * @param s             the alias of the command which was used <br> 被使用的指令别名
     * @param strings       the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @return a list of possible completions for the specified command string. <br> 指定指令的可能补全列表。
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
     * @param command the command which was executed <br> 被执行的指令
     * @param strings the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @param player  the player who executed the command <br> 执行指令的玩家
     * @return a list of possible completions for the specified command string. <br> 指定指令的可能补全列表。
     */
    protected abstract List<String> onPlayerTabComplete(Command command, String[] strings, Player player);
}
