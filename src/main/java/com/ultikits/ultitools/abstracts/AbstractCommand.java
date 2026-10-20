package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public abstract class AbstractCommand implements CommandExecutor {

    /**
     * Sends the help message to the sender of the command.
     *
     * @param sender the sender of the command
     */
    protected abstract void sendHelpMessage(CommandSender sender);

    /**
     * Sends the error message to the sender of the command.
     *
     * @param sender  the sender of the command
     * @param command the command which was executed
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
     * Gets the help command. For example "warp help" -- if you want to change the help command
     * to "warp h", override this method and make it return "h".
     *
     * @return the help command
     */
    protected String getHelpCommand() {
        return "help";
    }
}
