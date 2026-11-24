package com.ultikits.ultitools.command.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.ChatColor;

/** Framework replies and case normalization derived from a single walk over the typed tree. */
final class FrameworkReplies {
    @FunctionalInterface interface Redispatch {
        int run(String input, CommandSourceStack source) throws CommandSyntaxException;
    }
    private final BaseCommandExecutor executor;
    private final LiteralCommandNode<CommandSourceStack> original;
    private final Redispatch redispatch;
    private final ThreadLocal<Boolean> retrying = new ThreadLocal<>();

    FrameworkReplies(BaseCommandExecutor executor, LiteralCommandNode<CommandSourceStack> original, Redispatch redispatch) {
        this.executor = executor;
        this.original = original;
        this.redispatch = redispatch;
    }

    LiteralCommandNode<CommandSourceStack> tree() {
        return (LiteralCommandNode<CommandSourceStack>) decorate(original, true, false, null);
    }

    private CommandNode<CommandSourceStack> decorate(CommandNode<CommandSourceStack> node, boolean root, boolean greedy,
            String parentFallback) {
        var builder = node.createBuilder();
        Command<CommandSourceStack> body = node.getCommand();
        boolean noBareMapping = root && !(body instanceof CommandTreeCompiler.MappingCommand);
        builder.executes(body == null || noBareMapping ? this::reply : context -> {
            try { return body.run(context); }
            catch (CommandSyntaxException exception) { return invocationFailure(context, exception); }
        });
        String fallbackName = null;
        if (!greedy) {
            fallbackName = "__ultitools_unmatched";
            while (node.getChild(fallbackName) != null) fallbackName += "_";
        }
        for (var child : node.getChildren()) {
            builder.then(decorate(child, false, greedy(child), fallbackName));
        }
        if (fallbackName != null) {
            String name = fallbackName;
            // Forward only when this fallback consumed the cursor; ordinary children already supply suggestions.
            builder.then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(name, StringArgumentType.greedyString())
                    .suggests((context, suggestions) -> consumedFallback(context, suggestions, name)
                            ? suggestFromTree(context, suggestions) : suggestions.buildFuture()).executes(this::reply));
        }
        var decorated = builder.build();
        if (decorated instanceof LiteralCommandNode<CommandSourceStack> literal
                && node instanceof LiteralCommandNode<CommandSourceStack> originalLiteral) {
            return forwardLiteralSuggestions(literal, originalLiteral, fallbackName);
        }
        if (decorated instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            return forwardArgumentSuggestions(argument, parentFallback, fallbackName);
        }
        return decorated;
    }

    private static LiteralCommandNode<CommandSourceStack> forwardLiteralSuggestions(
            LiteralCommandNode<CommandSourceStack> node, LiteralCommandNode<CommandSourceStack> original, String fallback) {
        var copy = new LiteralCommandNode<CommandSourceStack>(node.getLiteral(), node.getCommand(), node.getRequirement(),
                node.getRedirect(), node.getRedirectModifier(), node.isFork()) {
            @Override public CompletableFuture<Suggestions> listSuggestions(CommandContext<CommandSourceStack> context,
                    SuggestionsBuilder builder) { return original.listSuggestions(context, builder); }
            @Override public Collection<? extends CommandNode<CommandSourceStack>> getRelevantNodes(StringReader input) {
                return relevant(node, input, fallback);
            }
        };
        node.getChildren().forEach(copy::addChild);
        return copy;
    }

    private static boolean consumedFallback(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String name) {
        return context.getNodes().stream().anyMatch(parsed -> parsed.getNode().getName().equals(name)
                && parsed.getRange().getStart() == builder.getStart());
    }

    private static <T> ArgumentCommandNode<CommandSourceStack, T> forwardArgumentSuggestions(
            ArgumentCommandNode<CommandSourceStack, T> node, String parentFallback, String fallback) {
        var copy = new ArgumentCommandNode<CommandSourceStack, T>(node.getName(), node.getType(), node.getCommand(), node.getRequirement(),
                node.getRedirect(), node.getRedirectModifier(), node.isFork(),
                (context, builder) -> parentFallback != null && consumedFallback(context, builder, parentFallback)
                        ? builder.buildFuture() : node.listSuggestions(context, builder)) {
            @Override public Collection<? extends CommandNode<CommandSourceStack>> getRelevantNodes(StringReader input) {
                return relevant(node, input, fallback);
            }
        };
        node.getChildren().forEach(copy::addChild);
        return copy;
    }

    private static Collection<? extends CommandNode<CommandSourceStack>> relevant(CommandNode<CommandSourceStack> node,
            StringReader input, String fallbackName) {
        if (fallbackName == null) return node.getRelevantNodes(input);
        StringReader word = new StringReader(input);
        int start = word.getCursor();
        while (word.canRead() && word.peek() != ' ') word.skip();
        String token = word.getString().substring(start, word.getCursor());
        for (var child : node.getChildren()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack> literal && literal.getLiteral().equalsIgnoreCase(token)) {
                var fallback = node.getChild(fallbackName);
                // Retain literal priority, but unlike Brigadier's exact-only selection keep the
                // framework fallback reachable when that literal is hidden or needs normalization.
                return literal.getLiteral().equals(token) ? List.of(literal, fallback) : List.of(fallback);
            }
        }
        return node.getRelevantNodes(input);
    }

    private CompletableFuture<Suggestions> suggestFromTree(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        if (!original.canUse(context.getSource()) || !CommandAccess.classVisible(executor.getClass(), context.getSource())) {
            return builder.buildFuture();
        }
        String input = builder.getInput();
        String actualLabel = input.split("\\s+", 2)[0];
        var root = new LiteralCommandNode<CommandSourceStack>(actualLabel, original.getCommand(), original.getRequirement(),
                original.getRedirect(), original.getRedirectModifier(), original.isFork());
        original.getChildren().forEach(root::addChild);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.getRoot().addChild(root);
        var parsed = dispatcher.parse(input, context.getSource());
        var suggestion = parsed.getContext().findSuggestionContext(input.length());
        StringReader cursor = new StringReader(input);
        cursor.setCursor(Math.min(suggestion.startPos, input.length()));
        var candidates = suggestion.parent.getRelevantNodes(cursor);
        if (candidates.size() == 1 && candidates.iterator().next() instanceof LiteralCommandNode<CommandSourceStack> literal
                && !literal.canUse(context.getSource())) return builder.buildFuture();
        return dispatcher.getCompletionSuggestions(parsed);
    }

    private static boolean greedy(CommandNode<CommandSourceStack> node) {
        return node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument
                && argument.getType() instanceof StringArgumentType string
                && string.getType() == StringArgumentType.StringType.GREEDY_PHRASE;
    }

    private int invocationFailure(CommandContext<CommandSourceStack> context, CommandSyntaxException failure)
            throws CommandSyntaxException {
        if (!(failure.getType() instanceof TypeParserArgumentType.ParserFailure)) return reply(context);
        if (!new CommandInvocation(executor, original.getLiteral()).refused(context)) {
            context.getSource().getSender().sendMessage(ChatColor.RED + failure.getRawMessage().getString());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (new CommandInvocation(executor, original.getLiteral()).refused(context)) return Command.SINGLE_SUCCESS;
        Walk walk = walk(context.getInput(), context.getSource());
        if (!walk.canonical().equals(context.getInput()) && !Boolean.TRUE.equals(retrying.get())) {
            retrying.set(true);
            try { return redispatch.run(walk.canonical(), context.getSource()); }
            finally { retrying.remove(); }
        }
        var sender = context.getSource().getSender();
        if (walk.parseError() != null) {
            sender.sendMessage(ChatColor.RED + walk.parseError());
        } else if (walk.depth() == 0) {
            sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("未知指令，请使用/%s %s获取帮助"),
                    original.getLiteral(), executor.helpCommand()));
            executor.handleGatedHelp(new CommandInvocation(executor, original.getLiteral()).context(context));
        } else {
            Set<String> formats = new LinkedHashSet<>();
            collectUsages(walk.deepest(), context.getSource(), formats);
            if (formats.isEmpty() && walk.deepest().getName().equalsIgnoreCase(executor.helpCommand())) {
                formats.add(executor.helpCommand());
            }
            for (String format : formats) sender.sendMessage(String.format(UltiTools.getInstance().i18n("指令正确用法"),
                    original.getLiteral(), format));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The only input traversal: literals are normalized, arguments use their own parsers. */
    private Walk walk(String input, CommandSourceStack source) {
        StringReader reader = new StringReader(input);
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        reader.skipWhitespace();
        CommandNode<CommandSourceStack> deepest = original;
        int depth = 0;
        String parseError = null;
        List<Replacement> replacements = new ArrayList<>();
        while (reader.canRead()) {
            Step step;
            try { step = next(deepest, reader, source); }
            catch (CommandSyntaxException failure) { parseError = failure.getRawMessage().getString(); break; }
            if (step == null) break;
            deepest = step.node();
            if (step.literal() != null) replacements.add(step.literal());
            reader.setCursor(step.end());
            reader.skipWhitespace();
            depth++;
        }
        StringBuilder canonical = new StringBuilder(input);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            Replacement replacement = replacements.get(i);
            canonical.replace(replacement.start(), replacement.end(), replacement.value());
        }
        return new Walk(deepest, depth, canonical.toString(), parseError);
    }

    private static Step next(CommandNode<CommandSourceStack> parent, StringReader reader, CommandSourceStack source)
            throws CommandSyntaxException {
        StringReader word = new StringReader(reader);
        int start = word.getCursor();
        while (word.canRead() && !Character.isWhitespace(word.peek())) word.skip();
        String token = word.getString().substring(start, word.getCursor());
        for (var child : parent.getChildren()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack> literal
                    && literal.canUse(source) && literal.getLiteral().equalsIgnoreCase(token)) {
                return new Step(child, word.getCursor(), new Replacement(start, word.getCursor(), literal.getLiteral()));
            }
        }
        for (var child : parent.getChildren()) {
            if (!(child instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) || !child.canUse(source)) continue;
            StringReader trial = new StringReader(reader);
            try {
                argument.getType().parse(trial, source);
                if (trial.getCursor() > start && (!trial.canRead() || Character.isWhitespace(trial.peek()))) {
                    return new Step(child, trial.getCursor(), null);
                }
            } catch (CommandSyntaxException failure) {
                if (argument.getType() instanceof TypeParserArgumentType<?>) throw failure;
                // Built-in type failures use the framework usage; no string matcher is consulted.
            }
        }
        return null;
    }

    private void collectUsages(CommandNode<CommandSourceStack> node, CommandSourceStack source, Set<String> usages) {
        if (!node.canUse(source)) return;
        if (node.getCommand() instanceof CommandTreeCompiler.MappingCommand mapping
                && CommandAccess.suggestible(executor.getClass(), mapping.method(), source)) {
            usages.add(mapping.method().getAnnotation(CmdMapping.class).format());
        }
        node.getChildren().forEach(child -> collectUsages(child, source, usages));
    }

    private record Walk(CommandNode<CommandSourceStack> deepest, int depth, String canonical, String parseError) {}
    private record Step(CommandNode<CommandSourceStack> node, int end, Replacement literal) {}
    private record Replacement(int start, int end, String value) {}
}
