package com.ultikits.testfixtures.wr01contractgap.ok;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * WR-01 fixture (05-REVIEW.md): the satisfied counterpart to {@code
 * com.ultikits.testfixtures.wr01contractgap.broken.UnenforceableExternalCommandExecutor} --
 * deliberately in a SEPARATE sub-package so each fixture can be scanned in isolation --
 * declares the SAME {@code @CmdCD} shape, but
 * keeps the default validator chain (which always carries {@code CooldownValidator}). Required
 * by WR-01's proof-form rule: a one-sided assertion that the broken fixture is refused cannot
 * distinguish "the external path now validates" from "the external path now refuses
 * everything" -- this fixture proves a satisfied contract still registers normally through the
 * same path.
 *
 * @since 6.3.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"wr01ok"})
public class EnforceableExternalCommandExecutor extends BaseCommandExecutor {

    @Override
    protected void handleHelp(CommandSender sender) {
        // Test stub - not exercised
    }

    @CmdMapping(format = "ping")
    @CmdCD(5)
    public void doPing(Player player) {
        // Test stub - not exercised
    }
}
