package com.ultikits.ultitools.command.engine;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import static org.mockito.Mockito.*;

/** Test-only dispatcher driver replacing calls to the removed Bukkit executor hooks. */
public final class CommandTestSupport {
    private CommandTestSupport() { /* Test utility. */ }

    public static CommandDispatcher<CommandSourceStack> dispatcher(BaseCommandExecutor executor, String label) {
        var invocation = new CommandInvocation(executor, label);
        var annotation = com.ultikits.ultitools.context.MergedAnnotationResolver.find(executor.getClass(),
                com.ultikits.ultitools.annotations.command.CmdExecutor.class);
        var compiled = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "test", List.of(label),
                annotation == null ? "" : annotation.permission(), annotation != null && annotation.requireOp(),
                invocation::run, invocation::help);
        var bindings = new CommandBindings(java.util.logging.Logger.getLogger("command-test"),
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
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source(sender))).join().getList().stream()
                .map(suggestion -> suggestion.getText()).toList();
    }

    private static CommandSourceStack source(CommandSender sender) {
        var source = mock(CommandSourceStack.class);
        lenient().when(source.getSender()).thenReturn(sender);
        return source;
    }
}
