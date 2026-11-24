package com.ultikits.ultitools.command.engine;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.ultikits.ultitools.command.BrigadierCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Describes hand-written shapes without capturing their command callbacks in a signature. */
final class HandWrittenCommands {
    private HandWrittenCommands() { /* Static tree compiler. */ }

    static CompiledCommand compile(BrigadierCommand command) {
        var root = command.node();
        var aliases = List.copyOf(command.aliases());
        Map<CommandNode<CommandSourceStack>, String> paths = new IdentityHashMap<>();
        index(root, "", paths);
        StringBuilder signature = new StringBuilder(command.getClass().getName()).append('|').append(aliases);
        append(root, signature, paths);
        return new CompiledCommand(root.getLiteral(), aliases, command.description(), root, signature.toString());
    }

    private static void index(CommandNode<CommandSourceStack> node, String path,
            Map<CommandNode<CommandSourceStack>, String> paths) {
        paths.put(node, path);
        for (var child : node.getChildren()) index(child, path + '/' + child.getName(), paths);
    }

    private static void append(CommandNode<CommandSourceStack> node, StringBuilder signature,
            Map<CommandNode<CommandSourceStack>, String> paths) {
        signature.append('(').append(node.getName()).append(':').append(node.getClass().getName())
                .append(':').append(node.getCommand() != null).append(':').append(node.isFork());
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            signature.append(':').append(argument.getType().getClass().getName());
        }
        var redirect = node.getRedirect();
        if (redirect != null) {
            String path = paths.get(redirect);
            signature.append("->").append(path == null
                    ? "external:" + redirect.getName() + ':' + System.identityHashCode(redirect) : path);
        }
        node.getChildren().forEach(child -> append(child, signature, paths));
        signature.append(')');
    }
}
