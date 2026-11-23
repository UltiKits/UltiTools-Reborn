package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/** Container-driven guards that remain effective without Bukkit's executor interface. */
class PluginManagerCommandDiscoveryTest {
    @BeforeEach void setUp() { MockBukkitHelper.ensureCleanState(); MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void missingCooldownValidatorIsStillRefused() {
        rejects(new MissingCooldown(), false, "CmdCD");
    }
    @Test void missingUsageLockValidatorIsStillRefused() {
        rejects(new MissingLock(), false, "UsageLimit");
    }
    @Test void unknownNamedCompleterIsStillRefused() {
        rejects(new UnknownCompleter(), false, "@cmd1-not-registered");
    }
    @Test void externalConfigBindingIsStillRefused() {
        rejects(new BoundExternal(), true, "external plugins");
    }

    private static void rejects(BaseCommandExecutor executor, boolean external, String reason) {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("executor", executor);
        assertThat(container.getBeanNamesForType(BaseCommandExecutor.class)).containsExactly("executor");
        assertThat(container.getBean("executor", BaseCommandExecutor.class)).isSameAs(executor);
        assertThatThrownBy(() -> {
            if (external) PluginManager.refuseConfigBindingsOutsideModules(container);
            else PluginManager.validateCommandExecutorContracts(container);
        }).isInstanceOf(PluginModuleException.class).hasMessageContaining(reason);
    }

    @CmdExecutor(alias = "probe-cooldown") static class MissingCooldown extends BaseCommandExecutor {
        MissingCooldown() { super(ValidatorChain.builder().build()); }
        @Override protected void handleHelp(CommandSender sender) { /* Load-time validation only. */ }
        @CmdMapping(format = "ping") @CmdCD(5)
        public void ping() { /* Load-time validation only. */ }
    }
    @CmdExecutor(alias = "probe-lock") static class MissingLock extends BaseCommandExecutor {
        MissingLock() { super(ValidatorChain.builder().build()); }
        @Override protected void handleHelp(CommandSender sender) { /* Load-time validation only. */ }
        @CmdMapping(format = "ping") @UsageLimit(UsageLimit.LimitType.SENDER)
        public void ping() { /* Load-time validation only. */ }
    }
    @CmdExecutor(alias = "probe-completer") static class UnknownCompleter extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Load-time validation only. */ }
        @CmdMapping(format = "ping <value>")
        public void ping(@CmdParam(value = "value", suggest = "@cmd1-not-registered") String value) {
            // Load-time validation only.
        }
    }
    @CmdExecutor(alias = "probe-external") static class BoundExternal extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Load-time validation only. */ }
        @CmdMapping(format = "ping") @CmdCD(key = "cooldown")
        public void ping() { /* Load-time validation only. */ }
    }
}
