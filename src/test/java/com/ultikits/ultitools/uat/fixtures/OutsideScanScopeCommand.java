package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A valid {@code @CmdExecutor} class living OUTSIDE
 * {@code com.ultikits.ultitools.uat.fixtures.scanscope} -- when scanned alongside
 * {@code scanscope.ModuleWithDefaultScanScope} (whose default scan scope is its own package
 * only), {@code ComponentScanner} would never discover this class at runtime, so
 * {@code SurfaceAssembler} must emit no row for it either (Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"outsidescope"})
public class OutsideScanScopeCommand extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
