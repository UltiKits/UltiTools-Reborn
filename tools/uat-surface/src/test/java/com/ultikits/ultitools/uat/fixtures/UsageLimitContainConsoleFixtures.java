package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.UsageLimit;

import org.bukkit.command.CommandSender;

/**
 * Two {@code @UsageLimit}-mapped methods, one at the annotation's default {@code ContainConsole}
 * ({@code true}) and one that opts out ({@code false}) -- the specimen for the row shape Codex
 * flagged on the restructure head: {@code UsageLockValidator.acquireLock} checks
 * {@code ContainConsole()} to decide whether a console sender is subject to the lock at all, but
 * the generated row previously carried only {@code value()}, so both settings produced an
 * identical surface.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"consolelim"})
public class UsageLimitContainConsoleFixtures extends BaseCommandExecutor {

    @CmdMapping(format = "default")
    @UsageLimit(UsageLimit.LimitType.ALL)
    public void defaultContainConsole(@CmdSender CommandSender sender) {
    }

    @CmdMapping(format = "opt-out")
    @UsageLimit(value = UsageLimit.LimitType.ALL, ContainConsole = false)
    public void optedOutContainConsole(@CmdSender CommandSender sender) {
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("consolelim help");
    }
}
