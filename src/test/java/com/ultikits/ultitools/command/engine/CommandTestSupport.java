package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.logging.Logger;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import com.ultikits.ultitools.UltiTools;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;

/** Test-only dispatcher driver replacing calls to the removed Bukkit executor hooks. */
public final class CommandTestSupport {
    private CommandTestSupport() { /* Test utility. */ }

    public static CommandDispatcher<CommandSourceStack> dispatcher(BaseCommandExecutor executor, String label) {
        var invocation = new CommandInvocation(executor, label);
        var annotation = MergedAnnotationResolver.find(executor.getClass(),
                CmdExecutor.class);
        var compiled = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "test", List.of(label),
                annotation == null ? "" : annotation.permission(), annotation != null && annotation.requireOp(),
                invocation::run, invocation::help);
        var bindings = new CommandBindings(Logger.getLogger("command-test"),
                () -> { /* No client synchronization in this dispatcher-only test driver. */ });
        var replies = new FrameworkReplies(executor, compiled.tree(), (input, source) -> bindings.dispatch(label, input, source));
        bindings.bind(executor, "test", executor, new CompiledCommand(label, List.of(), "test", replies.tree(), compiled.signature()));
        var commands = new RecordingCommands();
        bindings.onCommands(commands);
        return commands.getDispatcher();
    }

    public static boolean dispatch(BaseCommandExecutor executor, CommandSender sender, Command command, String label, String[] args) {
        String root = command == null || command.getName() == null ? label : command.getName();
        try {
            dispatcher(executor, root).execute(root + (args.length == 0 ? "" : " " + String.join(" ", args)), source(sender));
            return true;
        } catch (CommandSyntaxException exception) {
            throw new AssertionError("Unexpected Brigadier error for a framework command", exception);
        }
    }

    public static List<String> suggest(BaseCommandExecutor executor, CommandSender sender, Command command, String label, String[] args) {
        String root = command == null || command.getName() == null ? label : command.getName();
        var dispatcher = dispatcher(executor, root);
        String input = root + " " + String.join(" ", args);
        return clientSuggestions(dispatcher, input, source(sender));
    }

    /** Models Paper's requirement-filtered client tree; this is not the full server completion dispatcher. */
    public static List<String> clientSuggestions(CommandDispatcher<CommandSourceStack> server, String input,
            CommandSourceStack source) {
        var client = new CommandDispatcher<CommandSourceStack>();
        for (var node : server.getRoot().getChildren()) {
            if (node.canUse(source)) client.getRoot().addChild(clientNode(node, source));
        }
        return client.getCompletionSuggestions(client.parse(input, source)).join().getList().stream()
                .map(suggestion -> suggestion.getText()).toList();
    }

    private static CommandNode<CommandSourceStack> clientNode(CommandNode<CommandSourceStack> node, CommandSourceStack source) {
        var copy = node.createBuilder().build();
        for (var child : node.getChildren()) {
            if (child.canUse(source)) copy.addChild(clientNode(child, source));
        }
        return copy;
    }

    /** Captures the public Paper callback; registration is delivered only by {@link Lifecycle#fire()}. */
    public static Lifecycle lifecycle(UltiTools plugin) {
        return new Lifecycle(plugin);
    }

    /** Test-only lifecycle driver with a fresh registrar for each commands event. */
    public static final class Lifecycle {
        private final AtomicReference<LifecycleEventHandler<ReloadableRegistrarEvent<Commands>>> handler =
                new AtomicReference<>();

        private Lifecycle(UltiTools plugin) {
            if (Bukkit.getPluginManager() == null) {
                when(Bukkit.getPluginManager()).thenReturn(mock(PluginManager.class));
            }
            PluginLifecycle manager = mock(PluginLifecycle.class);
            when(plugin.getLifecycleManager()).thenReturn(manager);
            doAnswer(call -> {
                handler.set(call.getArgument(1));
                return null;
            }).when(manager).registerEventHandler(eq(LifecycleEvents.COMMANDS), any());
        }

        public RecordingCommands fire() {
            return fire(null);
        }

        RecordingCommands fire(String namespace) {
            var commands = new RecordingCommands(namespace);
            CommandsEvent event = mock(CommandsEvent.class);
            when(event.registrar()).thenReturn(commands);
            assertThat(handler.get())
                    .as("the real engine must install a commands callback").isNotNull();
            handler.get().run(event);
            return commands;
        }
    }

    public interface PluginLifecycle extends LifecycleEventManager<Plugin> { }
    public interface CommandsEvent extends ReloadableRegistrarEvent<Commands> { }

    public static CommandSourceStack source(CommandSender sender) {
        var source = mock(CommandSourceStack.class);
        lenient().when(source.getSender()).thenReturn(sender);
        return source;
    }
}
