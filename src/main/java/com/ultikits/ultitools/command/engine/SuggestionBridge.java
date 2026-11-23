package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Method;
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

    SuggestionProvider<CommandSourceStack> provider(Method method, String parameter, ArgumentType<?> nativeType) {
        String suggest = TabCompletionManager.resolveSuggestValue(method, parameter);
        return (context, builder) -> {
            String[] args = CommandInvocation.rawArgs(builder.getInput());
            String label = context.getInput().split("\\s+", 2)[0];
            if (suggest == null || suggest.isEmpty()) {
                List<String> custom = executor.suggestions(context.getSource().getSender(), label, args);
                return custom == null ? nativeType.listSuggestions(context, builder) : complete(custom, builder);
            }
            TabCompletionContext completion = TabCompletionContext.builder().sender(context.getSource().getSender())
                    .commandName(label).args(args).currentArgIndex(Math.max(0, args.length - 1))
                    .partialArg(builder.getRemaining()).matchedMethod(method).parameterName(parameter)
                    .executorInstance(executor).build();
            return complete(TabCompletionManager.getInstance().suggest(completion, suggest), builder);
        };
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
