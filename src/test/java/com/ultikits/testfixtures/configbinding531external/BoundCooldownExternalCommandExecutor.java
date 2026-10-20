package com.ultikits.testfixtures.configbinding531external;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.testutil.BindingTimingConfig;

/**
 * #531 fixture: an external plugin's executor with a config-bound {@code @CmdCD}. An external
 * plugin has no module config registry, so {@code registerExternal(...)} must refuse it before any
 * side effect rather than let the binding fail at the first dispatch.
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"fw531boundexternal"})
public class BoundCooldownExternalCommandExecutor extends BaseCommandExecutor {

    @Override
    protected void handleHelp(CommandSender sender) {
        // Test stub - not exercised
    }

    @CmdMapping(format = "go")
    @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
    public void doGo(Player player) {
        // Test stub - not exercised
    }
}
