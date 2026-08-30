package com.ultikits.ultitools.manager.wr01fixtures.broken;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * WR-01 fixture (05-REVIEW.md): a {@link BaseCommandExecutor} that declares {@code @CmdCD} on a
 * {@code @CmdMapping} method while its OWN validator chain -- via a
 * {@link #createDefaultValidatorChain()} override, mirroring that method's own documented
 * "an override that drops CooldownValidator... IS refused at load" contract -- omits
 * {@code CooldownValidator} entirely. Deliberately given a public no-arg constructor so it can
 * be discovered by real classpath component scanning (the shape the External Plugin API's
 * {@code PluginManager.registerExternal(...)} path uses), unlike
 * {@code PluginManagerCommandContractTest}'s hand-constructed fixtures which are driven directly
 * without going through a container.
 * <p>
 * When registered through the framework's INTERNAL module-loading path
 * ({@code PluginManager.register(...)}), this class is refused at load by
 * {@code PluginManager.validateCommandExecutorContract} (SILENT-11 / D-01, D-04). WR-01's defect
 * is that {@code PluginManager.registerExternal(...)} -- the External Plugin API's own
 * registration path -- never reached that same check, so an external Bukkit plugin could load
 * this exact class successfully, with {@code @CmdCD} silently never enforced.
 *
 * @since 6.3.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"wr01broken"})
public class UnenforceableExternalCommandExecutor extends BaseCommandExecutor {

    @Override
    protected ValidatorChain createDefaultValidatorChain() {
        // Deliberately omit CooldownValidator -- reproduces WR-01's unenforceable declaration
        // without needing constructor-injection support from the component scanner.
        return ValidatorChain.builder().build();
    }

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
