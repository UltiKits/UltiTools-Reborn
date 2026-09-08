package com.ultikits.ultitools.uat.fixtures.inheritance;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

import org.bukkit.command.CommandSender;

/**
 * Fixture for Phase 10 plan 10-03, Task 2: a base command executor carrying its own
 * {@code @CmdExecutor} and two {@code @CmdMapping} methods -- one that
 * {@link DerivedAdminCommands} inherits unchanged, one that it overrides.
 * <p>
 * UltiChat's two executors are flat (no shared base), and real UltiEssentials command classes all
 * extend a base ({@code BaseEssentialsCommand}) that never itself carries {@code @CmdExecutor} --
 * neither exercises "a base class carrying {@code @CmdExecutor} AND a derived class carrying its
 * own {@code @CmdExecutor}", so this shape is synthetic by necessity (10-03-PLAN.md Task 2).
 *
 * @since 6.3.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"badmin"}, permission = "fixture.badmin", description = "Base admin fixture")
public class BaseAdminCommands extends BaseCommandExecutor {

    /**
     * Inherited-mapping case: {@link DerivedAdminCommands} does not redeclare this method, so it
     * must emit exactly one {@code status} row, attributed to whichever concrete class is being
     * scanned.
     */
    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        sender.sendMessage("base status");
    }

    /**
     * Overridden-mapping case: {@link DerivedAdminCommands} redeclares this method with its own
     * {@code @CmdMapping}, and the merged view must still show exactly one {@code reload} row.
     */
    @CmdMapping(format = "reload")
    public void reload(@CmdSender CommandSender sender) {
        sender.sendMessage("base reload");
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("base help");
    }

    /**
     * Deliberately generic (Phase 10 plan 10-03, Task 2): {@link DerivedAdminCommands}
     * implementing {@code Identifiable<String>} makes {@code javac} emit a synthetic bridge
     * method ({@code Object identify(Object)}) in that subclass, alongside the real, non-bridge
     * {@code String identify(String)} override -- the compiled-class shape the extractor's
     * bridge filtering must never mistake for a second command row. Nested here (rather than a
     * separate top-level fixture file, and rather than nested on {@link DerivedAdminCommands}
     * itself, which would make that class implement its own nested type -- a cyclic-inheritance
     * compile error) to keep this plan's file list exact.
     *
     * @param <T> the identified value's type
     */
    public interface Identifiable<T> {
        T identify(T value);
    }
}
