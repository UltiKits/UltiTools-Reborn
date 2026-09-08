package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A top-level fixture whose simple name deliberately matches
 * {@link com.ultikits.ultitools.uat.fixtures.beta.AlphaCommands}'s simple name, in a different
 * package, with an identically named method carrying an identically formatted
 * {@code @CmdMapping} — so a row-id collision (row identity is {@code kind}, {@code origin},
 * {@code cls} (simple name, per D-10-08), {@code member}, {@code format}) is forced between two
 * genuinely distinct fully qualified classes, for {@code SurfaceAssemblerEdgeTest}'s
 * same-simple-name assertion (Phase 10 plan 10-02, Task 2).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"alpha"})
public class AlphaCommands extends BaseCommandExecutor {

    @CmdMapping(format = "run")
    public void run(@CmdSender CommandSender sender) {
        sender.sendMessage("alpha run (top-level)");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("alpha help (top-level)");
    }
}
