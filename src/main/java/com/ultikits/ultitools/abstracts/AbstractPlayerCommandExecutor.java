package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Abstract class representing a player command executor.
 * <p>
 * 玩家指令执行器的抽象类。
 *
 * @see CommandExecutor
 */
public abstract class AbstractPlayerCommandExecutor extends AbstractCommand {
    /**
     * @param commandSender the sender of the command <br> 指令发送者
     * @param command       the command which was executed <br> 被执行的指令
     * @param s             the alias of the command which was used <br> 被使用的指令别名
     * @param strings       the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @return whether the command was executed successfully <br> 指令是否执行成功
     * @see CommandExecutor#onCommand(CommandSender, Command, String, String[])
     */
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (strings.length > 0 && "help".equals(strings[0])) {
            sendHelpMessage(commandSender);
            return true;
        }
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return false;
        }
        Player player = (Player) commandSender;
        if (!onPlayerCommand(command, strings, player)) {
            sendErrorMessage(commandSender, command);
            return false;
        }
        return true;
    }

    /**
     * Executes the given command, returning its success
     * <p>
     * 执行给定的指令，返回是否成功
     *
     * @param command the command which was executed <br> 被执行的指令
     * @param strings the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @param player  the player who executed the command <br> 执行指令的玩家
     * @return whether the command was executed successfully <br> 指令是否执行成功
     */
    protected abstract boolean onPlayerCommand(Command command, String[] strings, Player player);
}
