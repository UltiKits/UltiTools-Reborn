package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Abstract class representing a console command executor.
 * <p>
 * 控制台指令执行器的抽象类。
 *
 * @see CommandExecutor
 */
public abstract class AbstractConsoleCommandExecutor implements CommandExecutor {
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
     * <p>
     * 执行给定的指令，返回是否成功
     *
     * @param commandSender the sender of the command <br> 指令发送者
     * @param command       the command which was executed <br> 被执行的指令
     * @param strings       the arguments passed to the command, split by spaces <br> 通过空格分割的指令参数
     * @return whether the command was executed successfully <br> 指令是否执行成功
     */
    protected abstract boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings);

    /**
     * Sends the help message to the sender of the command.
     * <p>
     * 发送帮助信息给指令发送者。
     *
     * @param sender the sender of the command <br> 指令发送者
     */
    protected abstract void sendHelpMessage(CommandSender sender);

    /**
     * Sends the error message to the sender of the command.
     * <p>
     * 发送错误信息给指令发送者。
     *
     * @param sender  the sender of the command <br> 指令发送者
     * @param command the command which was executed <br> 被执行的指令
     */
    protected void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    /**
     * Gets the help command. <br> For example "warp help", if you like to change the help command to "warp h", you can override this method and make this method return "h".
     * <p>
     * 获取帮助指令。<br> 例如"warp help"，如果你想把帮助指令改成"warp h"，你可以重写这个方法，让这个方法返回"h"。
     *
     * @return the help command <br> 帮助指令
     */
    protected String getHelpCommand() {
        return "help";
    }
}
