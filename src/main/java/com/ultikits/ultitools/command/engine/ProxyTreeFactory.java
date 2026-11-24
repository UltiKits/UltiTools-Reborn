package com.ultikits.ultitools.command.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Every retained callback resolves its path afresh; no callback retains a module executor. */
final class ProxyTreeFactory {
    private final CommandBindings bindings;

    ProxyTreeFactory(CommandBindings bindings) {
        this.bindings = bindings;
    }

    LiteralCommandNode<CommandSourceStack> create(String label, LiteralCommandNode<CommandSourceStack> tree) {
        Map<CommandNode<CommandSourceStack>, List<String>> paths = new IdentityHashMap<>();
        index(tree, List.of(), paths);
        Map<List<String>, CommandNode<CommandSourceStack>> proxies = new LinkedHashMap<>();
        return (LiteralCommandNode<CommandSourceStack>) copy(label, tree, List.of(), paths, proxies);
    }

    private static void index(CommandNode<CommandSourceStack> node, List<String> path,
            Map<CommandNode<CommandSourceStack>, List<String>> paths) {
        paths.put(node, path);
        for (var child : node.getChildren()) index(child, childPath(path, child.getName()), paths);
    }

    private CommandNode<CommandSourceStack> copy(String label, CommandNode<CommandSourceStack> real, List<String> path,
            Map<CommandNode<CommandSourceStack>, List<String>> paths,
            Map<List<String>, CommandNode<CommandSourceStack>> proxies) {
        CommandNode<CommandSourceStack> existing = proxies.get(path);
        if (existing != null) return existing;
        Supplier<CommandNode<CommandSourceStack>> lookup = () -> bindings.node(label, path);
        CommandNode<CommandSourceStack> redirect = redirect(label, real.getRedirect(), paths, proxies);
        RedirectModifier<CommandSourceStack> modifier = real.getRedirectModifier() == null ? null : context -> {
            var current = lookup.get();
            if (current == null || current.getRedirectModifier() == null) throw unknown();
            return current.getRedirectModifier().apply(context);
        };
        Command<CommandSourceStack> execute = real.getCommand() == null ? null : context -> {
            var current = lookup.get();
            if (current == null || !current.canUse(context.getSource()) || current.getCommand() == null) throw unknown();
            return current.getCommand().run(context);
        };
        Predicate<CommandSourceStack> requires = source -> {
            var current = lookup.get();
            return current != null && current.canUse(source);
        };
        CommandNode<CommandSourceStack> proxy = real instanceof ArgumentCommandNode<CommandSourceStack, ?> argument
                ? argument(argument, execute, requires, redirect, modifier, lookup)
                : new LiteralCommandNode<>(real.getName(), execute, requires, redirect, modifier, real.isFork());
        proxies.put(path, proxy);
        for (var child : real.getChildren()) proxy.addChild(copy(label, child, childPath(path, child.getName()), paths, proxies));
        return proxy;
    }

    private CommandNode<CommandSourceStack> redirect(String label, CommandNode<CommandSourceStack> target,
            Map<CommandNode<CommandSourceStack>, List<String>> paths,
            Map<List<String>, CommandNode<CommandSourceStack>> proxies) {
        if (target == null) return null;
        List<String> internal = paths.get(target);
        return internal == null ? target : copy(label, target, internal, paths, proxies);
    }

    private static <T> ArgumentCommandNode<CommandSourceStack, T> argument(ArgumentCommandNode<CommandSourceStack, T> real,
            Command<CommandSourceStack> execute, Predicate<CommandSourceStack> requires, CommandNode<CommandSourceStack> redirect,
            RedirectModifier<CommandSourceStack> modifier, Supplier<CommandNode<CommandSourceStack>> lookup) {
        return new ArgumentCommandNode<>(real.getName(), real.getType(), execute, requires, redirect, modifier, real.isFork(),
                real.getCustomSuggestions() == null ? null : (context, builder) -> suggest(lookup, context, builder));
    }

    private static CompletableFuture<Suggestions> suggest(Supplier<CommandNode<CommandSourceStack>> lookup,
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        var current = lookup.get();
        if (current == null || !current.canUse(context.getSource())) return builder.buildFuture();
        try {
            return current.listSuggestions(context, builder);
        } catch (CommandSyntaxException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static List<String> childPath(List<String> parent, String name) {
        List<String> path = new ArrayList<>(parent);
        path.add(name);
        return List.copyOf(path);
    }

    static CommandSyntaxException unknown() {
        return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
    }
}
