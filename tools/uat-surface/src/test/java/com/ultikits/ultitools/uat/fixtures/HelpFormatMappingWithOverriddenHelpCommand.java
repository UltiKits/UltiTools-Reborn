package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * Declares {@code @CmdMapping(format = "help")} AND overrides {@code getHelpCommand()} away
 * from the default {@code "help"} string -- the ambiguous case {@code CommandRowScanner} must
 * NOT exclude a row for, since it cannot know what this override actually returns without
 * executing it (Codex review of PR #427). Silently dropping a possibly-legitimate row is worse
 * than a phantom one an executor can mark blocked.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"helpoverridden"})
public class HelpFormatMappingWithOverriddenHelpCommand extends BaseCommandExecutor {

    @Override
    protected String getHelpCommand() {
        return "?";
    }

    @CmdMapping(format = "help")
    public void help(@CmdSender CommandSender sender) {
        sender.sendMessage("reachable");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
