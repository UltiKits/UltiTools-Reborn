package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.command.engine.CommandCompilationException;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

@ExtendWith(CommandTestExtension.class)
class CommandCompilationIsolationTest {
    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
    }

    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void rejectedExecutorRollsBackOnlyItsModuleAndReportsAnActionableSevere() throws Throwable {
        CommandManager commands = new CommandManager();
        ListenerManager listeners = mock(ListenerManager.class);
        var framework = TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getCommandManager()).thenReturn(commands);
            when(plugin.getListenerManager()).thenReturn(listeners);
            when(plugin.getEventBus()).thenReturn(mock(EventBus.class));
        });
        var lifecycle = CommandTestSupport.lifecycle(framework);
        var manager = new PluginManager();
        SimpleContainer failedContext = spy(new SimpleContainer());
        failedContext.registerSingleton("first", new Earlier());
        failedContext.registerSingleton("rejected", new Rejected());
        Module failed = module("rejected-module", failedContext);
        Module good = module("good-module", new SimpleContainer());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* In-memory handler. */ }
            @Override public void close() { /* In-memory handler. */ }
        };
        Bukkit.getLogger().addHandler(capture);
        try {
            assertThat(activate(manager, failed)).isFalse();
            assertThat(activate(manager, good)).isTrue();
            commands.registerCoreCommand(new Earlier());
            assertThat(manager.getPluginList()).contains(good).doesNotContain(failed);
            assertThat(commands.getPluginByLabel("rejected")).isNull();
            assertThat(commands.getPluginByLabel("earlier")).isNull();
            verify(failedContext).close();
            verify(listeners).unregisterAll(failed);
            var registered = lifecycle.fire();
            assertThat(registered.getDispatcher().getRoot().getChild("rejected")).isNull();
            assertThat(registered.getDispatcher().getRoot().getChild("earlier")).isNotNull();
            assertThat(records.stream().filter(record -> record.getLevel() == Level.SEVERE).map(LogRecord::getMessage))
                    .singleElement().asString().contains("rejected-module", Rejected.class.getName(), "tp <", "competing", "Change");
        } finally {
            Bukkit.getLogger().removeHandler(capture);
            commands.close();
        }
    }

    @Test void frameworkCoreCompilationFailureRemainsAHardFailure() {
        var framework = TestHelper.mockUltiToolsInstance();
        CommandTestSupport.lifecycle(framework);
        assertThatThrownBy(() -> new CommandManager().registerCoreCommand(new Rejected()))
                .isInstanceOf(CommandCompilationException.class);
    }

    private static boolean activate(PluginManager manager, UltiToolsPlugin module) throws Throwable {
        var method = MethodHandles.privateLookupIn(PluginManager.class, MethodHandles.lookup()).findVirtual(
                PluginManager.class, "attemptPluginRegistration", MethodType.methodType(boolean.class, UltiToolsPlugin.class));
        return (boolean) method.invoke(manager, module);
    }

    private static Module module(String name, SimpleContainer context) throws IOException {
        Module module = mock(Module.class);
        when(module.getPluginName()).thenReturn(name);
        when(module.getContext()).thenReturn(context);
        when(module.registerSelf()).thenReturn(true);
        doCallRealMethod().when(module).unregisterSelf();
        when(module.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
        return module;
    }

    @EnableAutoRegister
    abstract static class Module extends UltiToolsPlugin { }

    @CmdExecutor(alias = "earlier") static class Earlier extends BaseCommandExecutor {
        @CmdMapping(format = "ping") public void ping() { /* Registration control. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Registration control. */ }
    }

    @CmdExecutor(alias = "rejected") static class Rejected extends BaseCommandExecutor {
        @CmdMapping(format = "tp <player>") public void player(@CmdParam("player") String player) { /* Rejected fixture. */ }
        @CmdMapping(format = "tp <x> <y> <z>") public void coordinates(@CmdParam("x") int x,
                @CmdParam("y") int y, @CmdParam("z") int z) { /* Rejected fixture. */ }
        @Override protected void handleHelp(CommandSender sender) { /* Rejected fixture. */ }
    }
}
