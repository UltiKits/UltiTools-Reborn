package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
public abstract class AbstractTabExecutor implements TabExecutor {
    /**
     * @param commandSender the sender of the command <br> 指令发送者
     * @param command       the command which was executed <br> 被执行的指令
     * @param s             the alias of the command which was used <br> 被使用的指令别名
     * @param strings       the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @return whether the command was executed successfully <br> 指令是否执行成功
     * @see TabExecutor#onCommand(CommandSender, Command, String, String[])
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
     * @return whether the command was executed successfully <br> 指令是否执行成功
     */
    protected abstract boolean onPlayerCommand(Command command, String[] strings, Player player);

    /**
     * @param command the command which was executed <br> 被执行的指令
     * @param strings the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @param player  the player who executed the command <br> 执行指令的玩家
     * @return a list of possible completions for the specified command string. <br> 指定指令的可能补全列表。
     */
    protected abstract List<String> onPlayerTabComplete(Command command, String[] strings, Player player);

    /**
     * @param sender the sender of the command <br> 指令发送者
     * @see AbstractConsoleCommandExecutor#sendHelpMessage(CommandSender)
     */
    protected abstract void sendHelpMessage(CommandSender sender);

    /**
     * @param sender  the sender of the command <br> 指令发送者
     * @param command the command which was executed <br> 被执行的指令
     * @see AbstractConsoleCommandExecutor#sendErrorMessage(CommandSender, Command)
     */
    private void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    /**
     * @return the help command <br> 帮助指令
     * @see AbstractConsoleCommandExecutor#getHelpCommand()
     */
    protected String getHelpCommand() {
        return "help";
    }
}
