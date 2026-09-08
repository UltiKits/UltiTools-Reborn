package com.ultikits.ultitools.uat.fixtures.beta;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * See {@link com.ultikits.ultitools.uat.fixtures.AlphaCommands}'s javadoc — this class
 * deliberately collides with it on {@code cls}/{@code member}/{@code format}.
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"alpha"})
public class AlphaCommands extends BaseCommandExecutor {

    @CmdMapping(format = "run")
    public void run(@CmdSender CommandSender sender) {
        sender.sendMessage("alpha run (beta package)");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("alpha help (beta package)");
    }
}
