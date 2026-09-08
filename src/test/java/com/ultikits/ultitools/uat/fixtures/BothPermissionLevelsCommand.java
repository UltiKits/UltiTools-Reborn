package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * Declares BOTH a class-level {@code @CmdExecutor.permission()} and a method-level
 * {@code @CmdMapping.permission()} -- {@code PermissionValidator} checks the two
 * CONJUNCTIVELY (a sender needs both), never one overriding the other, exactly like
 * {@code requireOp}'s existing OR-combination (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"bothperms"}, permission = "uat.base")
public class BothPermissionLevelsCommand extends BaseCommandExecutor {

    @CmdMapping(format = "go", permission = "uat.mapping")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
