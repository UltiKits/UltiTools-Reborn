package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * Two {@code @CmdMapping} methods on the SAME class declaring the identical {@code format()}
 * (Codex review of PR #427). {@code BaseCommandExecutor.scanCommandMappings} keys its own
 * {@code mappings} map by {@code format()} via {@code putIfAbsent}, so only
 * {@link #onFirstReload(CommandSender)} -- the one {@code ReflectionUtil.getAllMethods} visits
 * first, declared first in this source -- is ever reachable at runtime;
 * {@link #onSecondReload(CommandSender)} occupies a format string that already resolved to a
 * different method and can never dispatch.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"dupfmt"}, permission = "uat.dupfmt", description = "Duplicate-format fixture command")
public class DuplicateFormatCommands extends BaseCommandExecutor {

    @CmdMapping(format = "reload")
    public void onFirstReload(@CmdSender CommandSender sender) {
        sender.sendMessage("first");
    }

    @CmdMapping(format = "reload")
    public void onSecondReload(@CmdSender CommandSender sender) {
        sender.sendMessage("second");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("dupfmt help");
    }
}
