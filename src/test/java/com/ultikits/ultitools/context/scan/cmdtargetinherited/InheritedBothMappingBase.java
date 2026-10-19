package com.ultikits.ultitools.context.scan.cmdtargetinherited;

import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Fixture: an executor base that declares a {@code @CmdTarget(BOTH)} mapping. It carries no
 * component annotation, so the scanner skips it; its two subclasses in this package inherit the
 * mapping unoverridden (case 57).
 */
public abstract class InheritedBothMappingBase extends BaseCommandExecutor {

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdMapping(format = "all")
    public void all(CommandSender sender) {
        // Fixture only - never instantiated by the scan being tested.
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        // Fixture only - never instantiated by the scan being tested.
    }
}
