package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A real, currently-shipping pattern (the framework's own {@code UltiToolsCommands} declares
 * exactly this): a {@code @CmdMapping(format = "help")} method on a class that does NOT
 * override {@code getHelpCommand()}. {@code BaseCommandExecutor.onCommand} intercepts a
 * single-token {@code "help"} argument BEFORE {@code matchMethod} ever runs, dispatching to the
 * synthesized help row instead -- this mapping is never reachable (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"helpshadowed"})
public class HelpFormatMapping extends BaseCommandExecutor {

    @CmdMapping(format = "help")
    public void help(@CmdSender CommandSender sender) {
        sender.sendMessage("shadowed");
    }

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
