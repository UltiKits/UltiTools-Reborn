package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public abstract class AbstractCommand implements CommandExecutor {

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
        sender.sendMessage(
                ChatColor.RED + String.format(
                        UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"),
                        command.getName(),
                        getHelpCommand()
                )
        );
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
