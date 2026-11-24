package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.command.BrigadierCommand;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.CommandManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;

class LateCommandRegistrationTest {
    @Test void actualContainerRegistrationAfterTheFirstEventDefersOnlyTheNewRoot() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var lifecycle = BrigadierCommandRegistrationTest.lifecycle(plugin);
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            List<String> messages = new ArrayList<>();
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
                @Override public void flush() { /* In-memory capture. */ }
                @Override public void close() { /* No resource. */ }
            });
            var manager = new CommandManager(new CommandEngine(new StringNativeArgumentTypes(), logger));
            var module = mock(UltiToolsPlugin.class);
            var container = new SimpleContainer();
            when(module.getContext()).thenReturn(container);
            when(module.getPluginName()).thenReturn("module");
            container.registerSingleton("initial", new Raw("initial"));
            manager.registerAll(module);
            var firstEvent = BrigadierCommandRegistrationTest.fire(lifecycle);
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(firstEvent.getDispatcher().execute("initial ping", source)).isEqualTo(13);
            container.registerSingleton("late", new Raw("late"));
            // This is a real registration call after the event, not a configuration reload.
            manager.registerAll(module);
            assertThat(messages).containsExactly("Command /late from module was registered after start-up; it becomes available after /minecraft:reload or a restart.");
            assertThat(firstEvent.getDispatcher().execute("initial ping", source)).isEqualTo(13);
            assertThatThrownBy(() -> firstEvent.getDispatcher().execute("late ping", source))
                    .isInstanceOf(CommandSyntaxException.class);
            var secondEvent = BrigadierCommandRegistrationTest.fire(lifecycle);
            assertThat(secondEvent.getDispatcher().execute("late ping", source)).isEqualTo(13);
            assertThat(secondEvent.registrations).isEqualTo(2);
        }
    }

    record Raw(String label) implements BrigadierCommand {
        @Override public LiteralCommandNode<CommandSourceStack> node() {
            return LiteralArgumentBuilder.<CommandSourceStack>literal(label)
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("ping").executes(context -> 13)).build();
        }
    }
}
