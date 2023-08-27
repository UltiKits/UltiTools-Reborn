package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConsoleCommandExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.IOException;

/**
 * 重载UltiTools-API的指令
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class UltiToolsCommands extends AbstractConsoleCommandExecutor {
    @Override
    protected boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings) {
        try {
            UltiTools.getInstance().reloadPlugins();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    /**
     * @param sender 命令发送者
     */
    @Override
    protected void sendHelpMessage(CommandSender sender) {
        String help = "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n================";
        sender.sendMessage(UltiTools.getInstance().i18n(help));
    }
}
