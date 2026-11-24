package com.ultikits.ultitools.command.engine;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
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
import org.bukkit.command.CommandSender;

/** Framework replies implemented only through callbacks retained by Paper's node mirror. */
final class FrameworkReplies {
    private final BaseCommandExecutor executor;
    private final LiteralCommandNode<CommandSourceStack> original;
    private final Redispatch redispatch;
    private final ThreadLocal<Boolean> retrying = new ThreadLocal<>();

    @FunctionalInterface interface Redispatch {
        int run(String input, CommandSourceStack source) throws CommandSyntaxException;
    }

    FrameworkReplies(BaseCommandExecutor executor, LiteralCommandNode<CommandSourceStack> original, Redispatch redispatch) {
        this.executor = executor;
        this.original = original;
        this.redispatch = redispatch;
    }

    LiteralCommandNode<CommandSourceStack> tree() {
        return (LiteralCommandNode<CommandSourceStack>) decorate(original, true);
    }

    private CommandNode<CommandSourceStack> decorate(CommandNode<CommandSourceStack> node, boolean root) {
        Command<CommandSourceStack> body = node.getCommand();
        boolean replyOnly = body == null || (root && !(body instanceof CommandTreeCompiler.MappingCommand));
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            return decorateArgument(argument, body, replyOnly);
        }
        var builder = node.createBuilder().executes(context -> execute(context, body, replyOnly));
        node.getChildren().forEach(child -> builder.then(decorate(child, false)));
        addFallback(node, builder);
        return builder.build();
    }

    private <T> CommandNode<CommandSourceStack> decorateArgument(ArgumentCommandNode<CommandSourceStack, T> node,
            Command<CommandSourceStack> body, boolean replyOnly) {
        var builder = node.createBuilder().executes(context -> execute(context, body, replyOnly))
                .suggests(this::suggestFromTree);
        node.getChildren().forEach(child -> builder.then(decorate(child, false)));
        addFallback(node, builder);
        return builder.build();
    }

    private void addFallback(CommandNode<CommandSourceStack> node,
            ArgumentBuilder<CommandSourceStack, ?> builder) {
        if (greedy(node)) return;
        String name = "args";
        while (node.getChild(name) != null) name += "_";
        // No custom suggestions: a fallback must not create an ask-server completion node.
        builder.then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(name, StringArgumentType.greedyString())
                .executes(this::reply));
    }

    private int execute(CommandContext<CommandSourceStack> context, Command<CommandSourceStack> body, boolean replyOnly)
            throws CommandSyntaxException {
        if (replyOnly) return reply(context);
        if (!Boolean.TRUE.equals(retrying.get()) && needsRewrite(context)) return reply(context);
        try {
            return body.run(context);
        } catch (CommandSyntaxException failure) {
            return invocationFailure(context, failure);
        }
    }

    /** Ordinary successful argument paths are not parsed again. Only a competing literal or fallback needs a walk. */
    private boolean needsRewrite(CommandContext<CommandSourceStack> context) {
        CommandNode<CommandSourceStack> parent = original;
        for (var parsed : context.getNodes()) {
            if (parsed.getRange().getStart() == 0) continue;
            CommandNode<CommandSourceStack> node = parent.getChild(parsed.getNode().getName());
            if (node == null) return true;
            if (node instanceof ArgumentCommandNode<CommandSourceStack, ?>) {
                StringReader reader = new StringReader(context.getInput());
                reader.setCursor(parsed.getRange().getStart());
                if (literal(parent, token(reader)) != null) return true;
            }
            parent = node;
        }
        return false;
    }

    private CompletableFuture<Suggestions> suggestFromTree(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        if (!original.canUse(context.getSource())) return builder.buildFuture();
        String input = builder.getInput();
        String label = input.split("\\s+", 2)[0];
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var root = new LiteralCommandNode<CommandSourceStack>(label, original.getCommand(), original.getRequirement(),
                original.getRedirect(), original.getRedirectModifier(), original.isFork());
        original.getChildren().stream().filter(child -> child.canUse(context.getSource()))
                .forEach(child -> root.addChild(visibleCopy(child, context.getSource())));
        dispatcher.getRoot().addChild(root);
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, context.getSource()));
    }

    private static CommandNode<CommandSourceStack> visibleCopy(CommandNode<CommandSourceStack> node, CommandSourceStack source) {
        var copy = node.createBuilder().build();
        node.getChildren().stream().filter(child -> child.canUse(source)).forEach(child -> copy.addChild(visibleCopy(child, source)));
        return copy;
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
        if (!walk.blocked() && !walk.canonical().equals(context.getInput()) && !Boolean.TRUE.equals(retrying.get())) {
            return redispatchCanonical(context, walk);
        }
        sendReply(context, walk);
        return Command.SINGLE_SUCCESS;
    }

    private int redispatchCanonical(CommandContext<CommandSourceStack> context, Walk walk) throws CommandSyntaxException {
        retrying.set(true);
        try {
            return redispatch.run(walk.canonical(), context.getSource());
        } finally {
            retrying.remove();
        }
    }

    private void sendReply(CommandContext<CommandSourceStack> context, Walk walk) {
        var sender = context.getSource().getSender();
        if (walk.parseError() != null) {
            sender.sendMessage(ChatColor.RED + walk.parseError());
        } else if (walk.depth() == 0) {
            sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("未知指令，请使用/%s %s获取帮助"),
                    original.getLiteral(), executor.helpCommand()));
            executor.handleGatedHelp(new CommandInvocation(executor, original.getLiteral()).context(context));
        } else {
            sendUsages(context, walk, sender);
        }
    }

    private void sendUsages(CommandContext<CommandSourceStack> context, Walk walk, CommandSender sender) {
        Set<String> formats = new LinkedHashSet<>();
        if (!walk.blocked() || !collectOwnUsage(walk.deepest(), context.getSource(), formats)) {
            collectUsages(walk.deepest(), context.getSource(), formats);
        }
        if (formats.isEmpty() && walk.deepest().getName().equalsIgnoreCase(executor.helpCommand())) {
            formats.add(executor.helpCommand());
        }
        for (String format : formats) sender.sendMessage(String.format(UltiTools.getInstance().i18n("指令正确用法"),
                original.getLiteral(), format));
    }

    /** The recovery walk uses the compiled tree's argument parsers and preserves quoted/greedy argument contents. */
    private Walk walk(String input, CommandSourceStack source) {
        StringReader reader = new StringReader(input);
        StringBuilder canonical = new StringBuilder(token(reader));
        reader.skipWhitespace();
        CommandNode<CommandSourceStack> deepest = original;
        int depth = 0;
        while (reader.canRead()) {
            int start = reader.getCursor();
            Step step;
            try {
                step = next(deepest, reader, source);
            } catch (CommandSyntaxException failure) {
                return new Walk(deepest, depth, input, failure.getRawMessage().getString(), false);
            }
            if (step == null) {
                canonical.append(' ').append(input.substring(start));
                break;
            }
            if (!step.node().canUse(source)) return new Walk(deepest, depth + 1, input, null, true);
            canonical.append(' ').append(step.literal() == null ? input.substring(start, step.end()) : step.literal());
            deepest = step.node();
            reader.setCursor(step.end());
            reader.skipWhitespace();
            depth++;
        }
        return new Walk(deepest, depth, canonical.toString(), null, false);
    }

    private static Step next(CommandNode<CommandSourceStack> parent, StringReader reader, CommandSourceStack source)
            throws CommandSyntaxException {
        StringReader word = new StringReader(reader);
        LiteralCommandNode<CommandSourceStack> literal = literal(parent, token(word));
        if (literal != null) return new Step(literal, word.getCursor(), literal.getLiteral());
        for (var child : parent.getChildren()) {
            if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> argument && child.canUse(source)) {
                Step step = argument(argument, reader, source);
                if (step != null) return step;
            }
        }
        return null;
    }

    private static Step argument(ArgumentCommandNode<CommandSourceStack, ?> node, StringReader reader, CommandSourceStack source)
            throws CommandSyntaxException {
        StringReader trial = new StringReader(reader);
        try {
            node.getType().parse(trial, source);
            if (trial.getCursor() > reader.getCursor() && (!trial.canRead() || Character.isWhitespace(trial.peek()))) {
                return new Step(node, trial.getCursor(), null);
            }
        } catch (CommandSyntaxException failure) {
            if (customParser(node)) throw failure;
        }
        return null;
    }

    private static boolean customParser(ArgumentCommandNode<CommandSourceStack, ?> node) {
        return node.getType() instanceof TypeParserArgumentType<?>;
    }

    private static String token(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        return reader.getString().substring(start, reader.getCursor());
    }

    private static LiteralCommandNode<CommandSourceStack> literal(CommandNode<CommandSourceStack> parent, String token) {
        for (var child : parent.getChildren()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack> literal && literal.getLiteral().equalsIgnoreCase(token)) return literal;
        }
        return null;
    }

    private void collectUsages(CommandNode<CommandSourceStack> node, CommandSourceStack source, Set<String> usages) {
        if (!node.canUse(source)) return;
        collectOwnUsage(node, source, usages);
        node.getChildren().forEach(child -> collectUsages(child, source, usages));
    }

    private boolean collectOwnUsage(CommandNode<CommandSourceStack> node, CommandSourceStack source, Set<String> usages) {
        if (node.getCommand() instanceof CommandTreeCompiler.MappingCommand mapping
                && CommandAccess.suggestible(executor.getClass(), mapping.method(), source)) {
            usages.add(mapping.method().getAnnotation(CmdMapping.class).format());
            return true;
        }
        return false;
    }

    private record Walk(CommandNode<CommandSourceStack> deepest, int depth, String canonical, String parseError, boolean blocked) { }
    private record Step(CommandNode<CommandSourceStack> node, int end, String literal) { }
}
