package com.ultikits.ultitools.commands;

import com.ultikits.abstracts.AbstractConsoleCommandExecutor;
import com.ultikits.ultitools.UltiTools;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.IOException;

public class ReloadPluginsCommand extends AbstractConsoleCommandExecutor {
    @Override
    protected boolean onConsoleCommand(CommandSender commandSender, Command command, String[] strings) {
        try {
            UltiTools.getInstance().reloadPlugins();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}
