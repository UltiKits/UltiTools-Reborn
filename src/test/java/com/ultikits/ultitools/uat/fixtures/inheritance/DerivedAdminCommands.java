package com.ultikits.ultitools.uat.fixtures.inheritance;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

import org.bukkit.command.CommandSender;

/**
 * Fixture for Phase 10 plan 10-03, Task 2: a derived command executor carrying its own
 * {@code @CmdExecutor} (distinct from {@link BaseAdminCommands}'s), exercising four shapes the
 * extractor's completeness control set (D-10-05, UltiChat plus one UltiEssentials sub-package)
 * cannot show on its own:
 * <ul>
 *     <li>an inherited mapping ({@code status}, not redeclared here) -- must emit exactly one row;</li>
 *     <li>an overridden mapping ({@code reload}, redeclared with its own {@code @CmdMapping}) --
 *     must still emit exactly one row, not two;</li>
 *     <li>a base class and a derived class each carrying their own {@code @CmdExecutor} -- rows
 *     must attribute to their own classes, with no cross-attribution;</li>
 *     <li>a compiler-generated bridge method -- implementing
 *     {@link BaseAdminCommands.Identifiable BaseAdminCommands.Identifiable&lt;String&gt;} makes
 *     {@code javac} emit a synthetic {@code Object identify(Object)} bridge in this class
 *     alongside the real, annotated {@code String identify(String)} override; the bridge must
 *     never produce a second row.</li>
 * </ul>
 *
 * @since 6.3.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"dadmin"}, permission = "fixture.dadmin", description = "Derived admin fixture")
public class DerivedAdminCommands extends BaseAdminCommands implements BaseAdminCommands.Identifiable<String> {

    /**
     * Overrides {@link BaseAdminCommands#reload(CommandSender)} with its own {@code @CmdMapping}
     * -- {@link com.ultikits.ultitools.utils.ReflectionUtil#getAllMethods(Class)} must collapse
     * the base declaration and this override into one slot, so exactly one row is emitted for
     * {@code reload}, not two.
     */
    @CmdMapping(format = "reload")
    @Override
    public void reload(@CmdSender CommandSender sender) {
        sender.sendMessage("derived reload");
    }

    /*
     * "status" is deliberately NOT redeclared here -- it is inherited unchanged from
     * BaseAdminCommands, so scanning this class must still emit exactly one status row,
     * attributed to DerivedAdminCommands.
     */

    /**
     * The real, non-bridge override that {@code javac} pairs with a synthetic
     * {@code Object identify(Object)} bridge in this same class. Annotated with
     * {@code @CmdMapping} specifically so a failure to filter the bridge would double this row
     * (or corrupt its member name) rather than the omission being trivially unobservable.
     */
    @CmdMapping(format = "identify <value>")
    @Override
    public String identify(String value) {
        return value;
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("derived help");
    }
}
