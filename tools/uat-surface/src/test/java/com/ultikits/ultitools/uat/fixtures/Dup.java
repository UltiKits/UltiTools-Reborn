package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A top-level fixture whose simple name deliberately matches
 * {@link DuplicateHolder.Dup}'s nested class, and whose single {@code @CmdMapping} method name
 * and format also match — so a row-id collision (same {@code kind}, {@code origin}, {@code cls},
 * {@code member}, {@code format}) is forced between two genuinely distinct fully qualified
 * classes, for {@code CommandRowScannerTest}'s collision assertion.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"dup"})
public class Dup extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("dup help");
    }
}
