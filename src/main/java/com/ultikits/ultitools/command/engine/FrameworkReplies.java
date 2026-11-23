package com.ultikits.ultitools.command.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
        return (LiteralCommandNode<CommandSourceStack>) decorate(original, true, false);
    }

    private CommandNode<CommandSourceStack> decorate(CommandNode<CommandSourceStack> node, boolean root, boolean greedy) {
        var builder = node.createBuilder();
        Command<CommandSourceStack> body = node.getCommand();
        boolean noBareMapping = root && !executor.getMappings().containsKey("");
        builder.executes(body == null || noBareMapping ? this::reply : context -> {
            try { return body.run(context); }
            catch (CommandSyntaxException exception) { return reply(context); }
        });
        boolean hasGreedy = false;
        for (var child : node.getChildren()) {
            boolean childGreedy = greedy(child);
            hasGreedy |= childGreedy;
            builder.then(decorate(child, false, childGreedy));
        }
        if (!greedy && !hasGreedy) {
            String fallbackName = "__ultitools_unmatched";
            while (node.getChild(fallbackName) != null) fallbackName += "_";
            builder.then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(fallbackName, StringArgumentType.greedyString())
                    .suggests((context, suggestions) -> suggestions.buildFuture()).executes(this::reply));
        }
        return builder.build();
    }

    private static boolean greedy(CommandNode<CommandSourceStack> node) {
        return node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument
                && argument.getType() instanceof StringArgumentType string
                && string.getType() == StringArgumentType.StringType.GREEDY_PHRASE;
    }

    private int reply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var invocation = new CommandInvocation(executor);
        if (invocation.refused(context)) return Command.SINGLE_SUCCESS;
        Walk walk = walk(context.getInput(), context.getSource());
        if (!walk.canonical().equals(context.getInput()) && !Boolean.TRUE.equals(retrying.get())) {
            retrying.set(true);
            try { return redispatch.run(walk.canonical(), context.getSource()); }
            finally { retrying.remove(); }
        }
        var sender = context.getSource().getSender();
        if (walk.depth() == 0) {
            sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("未知指令，请使用/%s %s获取帮助"),
                    original.getLiteral(), executor.helpCommand()));
        } else {
            Set<String> formats = new LinkedHashSet<>();
            collectUsages(walk.deepest(), context.getSource(), formats);
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
        List<Replacement> replacements = new ArrayList<>();
        while (reader.canRead()) {
            Step step = next(deepest, reader, source);
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
        return new Walk(deepest, depth, canonical.toString());
    }

    private static Step next(CommandNode<CommandSourceStack> parent, StringReader reader, CommandSourceStack source) {
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
            } catch (CommandSyntaxException ignored) {
                // A failed argument stops this path; no legacy string matcher is consulted.
            }
        }
        return null;
    }

    private static void collectUsages(CommandNode<CommandSourceStack> node, CommandSourceStack source, Set<String> usages) {
        if (!node.canUse(source)) return;
        if (node.getCommand() instanceof CommandTreeCompiler.MappingCommand mapping) {
            usages.add(mapping.method().getAnnotation(CmdMapping.class).format());
        }
        node.getChildren().forEach(child -> collectUsages(child, source, usages));
    }

    private record Walk(CommandNode<CommandSourceStack> deepest, int depth, String canonical) {}
    private record Step(CommandNode<CommandSourceStack> node, int end, Replacement literal) {}
    private record Replacement(int start, int end, String value) {}
}
