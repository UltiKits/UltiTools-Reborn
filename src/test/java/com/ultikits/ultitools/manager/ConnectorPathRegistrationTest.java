package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.command.engine.RecordingCommands;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/** Real connector -> manager -> lifecycle registrar regression; no legacy Bukkit executor cast. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "unchecked"})
class ConnectorPathRegistrationTest {
    private static final String LABEL = "connectorpathbasecmd";
    private PluginManager pluginManager;
    private final AtomicReference<LifecycleEventHandler<ReloadableRegistrarEvent<Commands>>> handler = new AtomicReference<>();

    @UltiToolsModule abstract static class ModuleFixture extends UltiToolsPlugin { /* Mock module identity. */ }
    @CmdExecutor(alias = LABEL, permission = "test.connectorpath.base")
    static class BaseCommandFixture extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("base command help"); }
    }

    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        pluginManager = new PluginManager();
        LifecycleEventManager<Plugin> lifecycle = mock(LifecycleEventManager.class);
        doAnswer(call -> { handler.set(call.getArgument(1)); return null; }).when(lifecycle)
                .registerEventHandler(eq(LifecycleEvents.COMMANDS), any(LifecycleEventHandler.class));
        CommandManager manager = new CommandManager();
        TestHelper.mockUltiToolsInstance(plugin -> {
            when(plugin.getCommandManager()).thenReturn(manager);
            when(plugin.getListenerManager()).thenReturn(mock(ListenerManager.class));
            when(plugin.getLifecycleManager()).thenReturn(lifecycle);
        });
    }

    @AfterEach void tearDown() { MockBukkitHelper.safeUnmock(); }

    @Test void aBaseCommandRegistersThroughTheConnectorAndExecutesAfterTheCommandsEvent() throws Exception {
        SimpleContainer container = new SimpleContainer();
        container.registerSingleton("command", new BaseCommandFixture());
        UltiToolsPlugin module = module(container);
        assertDoesNotThrow(() -> register(module));
        var commands = fireCommands();
        assertThat(commands.getDispatcher().getRoot().getChild(LABEL)).isNotNull();
        var sender = mock(CommandSender.class);
        when(sender.hasPermission("test.connectorpath.base")).thenReturn(true);
        var source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        assertThat(commands.getDispatcher().execute(LABEL + " help", source)).isEqualTo(1);
        verify(sender).sendMessage("base command help");
    }

    @Test void anEmptyContainerRegistersNothingWithoutThrowing() {
        assertDoesNotThrow(() -> register(module(new SimpleContainer())));
        assertThat(handler.get()).isNull();
    }

    private UltiToolsPlugin module(SimpleContainer container) {
        var module = mock(ModuleFixture.class);
        when(module.getContext()).thenReturn(container);
        when(module.getPluginName()).thenReturn("module");
        when(module.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
        return module;
    }

    private void register(UltiToolsPlugin module) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("registerBukkit", UltiToolsPlugin.class);
        method.setAccessible(true);
        method.invoke(pluginManager, module);
    }

    private RecordingCommands fireCommands() {
        assertThat(handler.get()).isNotNull();
        var commands = new RecordingCommands();
        ReloadableRegistrarEvent<Commands> event = mock(ReloadableRegistrarEvent.class);
        when(event.registrar()).thenReturn(commands);
        handler.get().run(event);
        return commands;
    }
}
