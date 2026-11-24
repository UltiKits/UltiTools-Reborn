package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.command.BrigadierCommand;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.CommandManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;

class BrigadierCommandRegistrationTest {
    @Test void moduleBeanAliasesRegisterOnceAndUnloadingHidesTheLiveTree() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var lifecycle = lifecycle(plugin);
            var manager = new CommandManager(new CommandEngine(new StringNativeArgumentTypes(), Logger.getAnonymousLogger()));
            var container = new AliasingContainer();
            Raw raw = new Raw();
            container.registerSingleton("raw", raw);
            container.alias("same", raw);
            var module = mock(UltiToolsPlugin.class);
            when(module.getContext()).thenReturn(container);
            when(module.getPluginName()).thenReturn("module");
            manager.registerAll(module);
            assertThat(raw.nodeCalls).isEqualTo(1);
            var commands = fire(lifecycle);
            assertThat(commands.registrations).isEqualTo(1);
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(commands.getDispatcher().execute("raw ping", source)).isEqualTo(41);
            manager.unregisterAll(module);
            assertThat(commands.getDispatcher().getRoot().getChild("raw").canUse(source)).isFalse();
            assertThatThrownBy(() -> commands.getDispatcher().execute("raw ping", source))
                    .isInstanceOf(CommandSyntaxException.class);
        }
    }

    @Test void externalBeansHaveIndependentOwnershipAndRawDescriptions() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var lifecycle = lifecycle(plugin);
            var manager = new CommandManager(new CommandEngine(new StringNativeArgumentTypes(), Logger.getAnonymousLogger()));
            var container = new AliasingContainer();
            var raw = new Raw();
            container.registerSingleton("raw", raw);
            container.alias("alias", raw);
            var adapter = mock(ExternalPluginAdapter.class);
            when(adapter.getContext()).thenReturn(container);
            when(adapter.getPluginName()).thenReturn("external");
            manager.registerAllExternal(adapter);
            var commands = fire(lifecycle);
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(commands.getDispatcher().execute("other ping", source)).isEqualTo(41);
            assertThat(raw.nodeCalls).isEqualTo(1);
            manager.unregisterAllExternal("external");
            assertThat(commands.getDispatcher().getRoot().getChild("raw").canUse(source)).isFalse();
        }
    }

    static CommandTestSupport.Lifecycle lifecycle(UltiTools plugin) {
        return CommandTestSupport.lifecycle(plugin);
    }

    static RecordingCommands fire(CommandTestSupport.Lifecycle lifecycle) {
        return lifecycle.fire();
    }

    static class AliasingContainer extends SimpleContainer {
        void alias(String name, Object bean) { addSingleton(name, bean); }
    }
    static class Raw implements BrigadierCommand {
        int nodeCalls;
        @Override public LiteralCommandNode<CommandSourceStack> node() {
            nodeCalls++;
            return LiteralArgumentBuilder.<CommandSourceStack>literal("raw")
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("ping").executes(context -> 41)).build();
        }
        @Override public List<String> aliases() { return List.of("other"); }
        @Override public String description() { return "raw description"; }
    }
}
