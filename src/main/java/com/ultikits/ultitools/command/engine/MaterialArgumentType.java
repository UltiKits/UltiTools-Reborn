package com.ultikits.ultitools.command.engine;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import net.kyori.adventure.key.Key;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/** Material keys use Paper's resource-key syntax rather than enum spelling. */
final class MaterialArgumentType implements CustomArgumentType<Material, Key> {
    @Override public Material parse(StringReader reader) throws CommandSyntaxException {
        Key key = getNativeType().parse(reader);
        Material material = Material.matchMaterial(key.asString());
        if (material == null) throw new SimpleCommandExceptionType(new LiteralMessage("Unknown material: " + key.asString())).createWithContext(reader);
        return material;
    }
    @Override public ArgumentType<Key> getNativeType() { return ArgumentTypes.key(); }
    @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        for (Material material : Material.values()) {
            if (material.isLegacy()) continue;
            String key = material.getKey().asString();
            if (key.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())
                    || material.getKey().getKey().startsWith(builder.getRemainingLowerCase())) builder.suggest(key);
        }
        return builder.buildFuture();
    }
}
