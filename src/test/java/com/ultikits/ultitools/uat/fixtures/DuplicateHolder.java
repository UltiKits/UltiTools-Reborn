package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * Holder for the nested {@link Dup} class, whose simple name deliberately collides with the
 * top-level {@link com.ultikits.ultitools.uat.fixtures.Dup} — see that class's javadoc.
 *
 * @since 6.3.0
 */
public final class DuplicateHolder {

    private DuplicateHolder() {
    }

    @CmdExecutor(alias = {"dup"})
    public static class Dup extends BaseCommandExecutor {

        @CmdMapping(format = "go")
        public void go(@CmdSender CommandSender sender) {
            sender.sendMessage("go");
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            sender.sendMessage("dup help (nested)");
        }
    }
}
