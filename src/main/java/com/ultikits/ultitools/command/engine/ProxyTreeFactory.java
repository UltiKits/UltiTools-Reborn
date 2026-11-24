package com.ultikits.ultitools.command.engine;

import java.util.ArrayList;
import java.util.Collection;
import com.mojang.brigadier.StringReader;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Every callback resolves its path afresh; no callback retains a module executor. */
final class ProxyTreeFactory {
    private final CommandBindings bindings;
    ProxyTreeFactory(CommandBindings bindings) { this.bindings = bindings; }

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
        Supplier<CommandNode<CommandSourceStack>> lookup = () -> bindings.node(label, path);
        var redirect = redirect(real.getRedirect(), paths, proxies);
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
        java.util.function.Predicate<CommandSourceStack> requires = source -> {
            var current = lookup.get();
            return current != null && current.canUse(source);
        };
        CommandNode<CommandSourceStack> proxy;
        if (real instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            proxy = new ArgumentCommandNode<CommandSourceStack, Object>(real.getName(), cast(argument.getType()), execute,
                    requires, null, modifier, real.isFork(), (context, builder) -> suggest(lookup, context, builder)) {
                @Override public CommandNode<CommandSourceStack> getRedirect() { return redirect.get(); }
                @Override public Collection<? extends CommandNode<CommandSourceStack>> getRelevantNodes(StringReader input) {
                    return relevant(lookup, this, input);
                }
            };
        } else {
            proxy = new LiteralCommandNode<CommandSourceStack>(real.getName(), execute, requires, null, modifier, real.isFork()) {
                @Override public CommandNode<CommandSourceStack> getRedirect() { return redirect.get(); }
                @Override public Collection<? extends CommandNode<CommandSourceStack>> getRelevantNodes(StringReader input) {
                    return relevant(lookup, this, input);
                }
                @Override public CompletableFuture<Suggestions> listSuggestions(CommandContext<CommandSourceStack> context,
                        SuggestionsBuilder builder) {
                    return suggest(lookup, context, builder);
                }
            };
        }
        proxies.put(path, proxy);
        for (var child : real.getChildren()) {
            proxy.addChild(copy(label, child, childPath(path, child.getName()), paths, proxies));
        }
        return proxy;
    }

    private static Supplier<CommandNode<CommandSourceStack>> redirect(CommandNode<CommandSourceStack> target,
            Map<CommandNode<CommandSourceStack>, List<String>> paths,
            Map<List<String>, CommandNode<CommandSourceStack>> proxies) {
        if (target == null) return () -> null;
        List<String> internal = paths.get(target);
        return internal == null ? () -> target : () -> proxies.get(internal);
    }

    private static Collection<? extends CommandNode<CommandSourceStack>> relevant(
            Supplier<CommandNode<CommandSourceStack>> lookup, CommandNode<CommandSourceStack> proxy, StringReader input) {
        var current = lookup.get();
        if (current == null) return List.of();
        List<CommandNode<CommandSourceStack>> selected = new ArrayList<>();
        for (var candidate : current.getRelevantNodes(input)) {
            var child = proxy.getChild(candidate.getName());
            if (child != null) selected.add(child);
        }
        return selected;
    }

    private static CompletableFuture<Suggestions> suggest(Supplier<CommandNode<CommandSourceStack>> lookup,
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        var current = lookup.get();
        if (current == null || !current.canUse(context.getSource())) return builder.buildFuture();
        try { return current.listSuggestions(context, builder); }
        catch (CommandSyntaxException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private static List<String> childPath(List<String> parent, String name) {
        List<String> path = new ArrayList<>(parent);
        path.add(name);
        return List.copyOf(path);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentType<Object> cast(ArgumentType<?> type) { return (ArgumentType<Object>) type; }

    static CommandSyntaxException unknown() {
        return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
    }
}
