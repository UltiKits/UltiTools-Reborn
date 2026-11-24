package com.ultikits.ultitools.command.engine;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import java.util.Locale;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/** Offline names accept a word; only online names are suggested. */
final class OfflinePlayerArgumentType implements CustomArgumentType<String, String> {
    @Override public String parse(StringReader reader) throws CommandSyntaxException {
        String name = reader.readUnquotedString();
        if (name.isEmpty()) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        return name;
    }
    @Override public ArgumentType<String> getNativeType() { return StringArgumentType.word(); }
    @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        Bukkit.getOnlinePlayers().stream().map(player -> player.getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
