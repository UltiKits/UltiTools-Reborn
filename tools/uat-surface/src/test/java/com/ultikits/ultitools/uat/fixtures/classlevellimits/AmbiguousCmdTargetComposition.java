package com.ultikits.ultitools.uat.fixtures.classlevellimits;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

import org.bukkit.command.CommandSender;

/**
 * A class-level {@code @CmdTarget(PLAYER)} combined with a method-level
 * {@code @CmdTarget(CONSOLE)} -- a LATERAL transition, which
 * {@code CmdTargetComposition.classify} refuses (Phase 10, Codex review of PR #427).
 * {@code ComponentScanner.registerComponent} runs this check BEFORE registering the class as a
 * bean at all, so the ENTIRE class -- every command and its help output -- is never reachable,
 * not merely the one offending mapping.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"ambiguous"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class AmbiguousCmdTargetComposition extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
