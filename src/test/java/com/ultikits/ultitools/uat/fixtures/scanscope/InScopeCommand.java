package com.ultikits.ultitools.uat.fixtures.scanscope;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A valid {@code @CmdExecutor} class living in the SAME package as
 * {@link ModuleWithDefaultScanScope} -- covered by that module's default (own-package) scan
 * scope, so this command's row must be present (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"inscope"})
public class InScopeCommand extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
