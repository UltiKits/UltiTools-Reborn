package com.ultikits.ultitools.uat.fixtures.scanscope.declaredpackage;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;

import org.bukkit.command.CommandSender;

/**
 * A valid {@code @CmdExecutor} class living in the package
 * {@link com.ultikits.ultitools.uat.fixtures.scanscope.ModuleWithExplicitScanScope} explicitly
 * names via {@code scanBasePackages()} -- covered by that declared scope even though it is not
 * the module class's own package (Phase 10, Codex review of PR #427).
 *
 * @since 6.3.0
 */
@CmdExecutor(alias = {"declaredpkg"})
public class DeclaredPackageCommand extends BaseCommandExecutor {

    @CmdMapping(format = "go")
    public void go(@CmdSender CommandSender sender) {
        sender.sendMessage("go");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("help");
    }
}
