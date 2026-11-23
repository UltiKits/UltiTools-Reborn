package com.ultikits.ultitools.command.engine;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/** Offline names accept a word; only online names are suggested. */
final class OfflinePlayerArgumentType implements CustomArgumentType<OfflinePlayer, String> {
    @Override public OfflinePlayer parse(StringReader reader) { return Bukkit.getOfflinePlayer(reader.readUnquotedString()); }
    @Override public ArgumentType<String> getNativeType() { return StringArgumentType.word(); }
    @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        Bukkit.getOnlinePlayers().stream().map(player -> player.getName())
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
