package com.ultikits.ultitools.command.engine;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.registry.RegistryKey;

/** Only this adapter asks Paper for native argument implementations. */
final class PaperNativeArgumentTypes implements NativeArgumentTypes {
    @Override public ArgumentType<?> type(Class<?> type) {
        if (type.equals(Player.class)) return ArgumentTypes.player();
        if (type.equals(OfflinePlayer.class)) return new OfflinePlayerArgumentType();
        if (type.equals(World.class)) return ArgumentTypes.world();
        if (type.equals(GameMode.class)) return ArgumentTypes.gameMode();
        return resourceOrPosition(type);
    }
    private static ArgumentType<?> resourceOrPosition(Class<?> type) {
        if (type.equals(UUID.class)) return ArgumentTypes.uuid();
        if (type.equals(Enchantment.class)) return ArgumentTypes.resource(RegistryKey.ENCHANTMENT);
        if (type.equals(Material.class)) return new MaterialArgumentType();
        if (type.equals(Location.class)) return ArgumentTypes.finePosition(true);
        throw new CommandCompilationException("No mapped argument type: " + type.getName());
    }
    @Override public Object resolve(Class<?> type, Object value, CommandSourceStack source) throws CommandSyntaxException {
        if (type.equals(Player.class)) return ((PlayerSelectorArgumentResolver) value).resolve(source).getFirst();
        if (type.equals(OfflinePlayer.class)) return Bukkit.getOfflinePlayer((String) value);
        if (type.equals(Location.class)) {
            var position = ((FinePositionResolver) value).resolve(source);
            return new Location(source.getLocation().getWorld(), position.x(), position.y(), position.z());
        }
        return value;
    }
}
