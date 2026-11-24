package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionContext;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Bridges the existing provider registry, method providers and executor hook to Brigadier. */
final class SuggestionBridge {
    private final BaseCommandExecutor executor;
    SuggestionBridge(BaseCommandExecutor executor) { this.executor = executor; }

    SuggestionProvider<CommandSourceStack> provider(List<Method> methods, String parameter, ArgumentType<?> nativeType) {
        return (context, builder) -> {
            List<Method> allowed = methods.stream().filter(method ->
                    CommandAccess.suggestible(executor.getClass(), method, context.getSource())).toList();
            if (allowed.isEmpty()) return builder.buildFuture();
            List<CompletableFuture<Suggestions>> results = new ArrayList<>();
            for (Method method : allowed) {
                String suggest = TabCompletionManager.resolveSuggestValue(method, parameter);
                if (suggest != null && !suggest.isEmpty()) {
                    results.add(explicit(context, new SuggestionsBuilder(builder.getInput(), builder.getStart()), method, parameter, suggest));
                }
            }
            if (results.isEmpty()) return fallback(context, builder, nativeType);
            return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).thenApply(ignored ->
                    Suggestions.merge(builder.getInput(), results.stream().map(CompletableFuture::join).toList()));
        };
    }

    private CompletableFuture<Suggestions> explicit(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            Method method, String parameter, String suggest) {
        String[] args = CommandInvocation.rawArgs(builder.getInput());
        String label = context.getInput().split("\\s+", 2)[0];
        TabCompletionContext completion = TabCompletionContext.builder().sender(context.getSource().getSender())
                .commandName(label).args(args).currentArgIndex(Math.max(0, args.length - 1))
                .partialArg(builder.getRemaining()).matchedMethod(method).parameterName(parameter)
                .executorInstance(executor).build();
        return complete(TabCompletionManager.getInstance().suggest(completion, suggest), builder);
    }

    private CompletableFuture<Suggestions> fallback(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder,
            ArgumentType<?> nativeType) {
        String label = context.getInput().split("\\s+", 2)[0];
        List<String> custom = executor.suggestions(context.getSource().getSender(), label, CommandInvocation.rawArgs(builder.getInput()));
        return custom == null ? nativeType.listSuggestions(context, builder) : complete(custom, builder);
    }

    private static CompletableFuture<Suggestions> complete(List<String> suggestions, SuggestionsBuilder builder) {
        if (suggestions != null) {
            String prefix = builder.getRemainingLowerCase();
            for (String suggestion : suggestions) {
                if (suggestion != null && suggestion.toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }
}
