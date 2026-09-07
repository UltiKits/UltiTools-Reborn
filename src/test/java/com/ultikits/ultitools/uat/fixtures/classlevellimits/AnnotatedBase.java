package com.ultikits.ultitools.uat.fixtures.classlevellimits;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

import org.bukkit.command.CommandSender;

/**
 * Carries both {@code @CmdExecutor} and a class-level {@code @CmdTarget(PLAYER)} -- the
 * ancestor {@link UnannotatedSubclass} and {@link RedeclaringSubclassWithoutTarget} test
 * against (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"annotatedbase"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class AnnotatedBase extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
