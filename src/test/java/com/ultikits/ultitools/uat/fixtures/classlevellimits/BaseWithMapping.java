package com.ultikits.ultitools.uat.fixtures.classlevellimits;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A base executor with a real {@code @CmdMapping} method but no {@code @CmdCD}/{@code
 * @UsageLimit} of its own -- {@link SubclassWithClassLevelLimits} inherits this mapping
 * unchanged and carries the limits at the class level instead (Phase 10, Codex review of PR
 * #427).
 *
 * @since 6.3.0
 */
public class BaseWithMapping extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
