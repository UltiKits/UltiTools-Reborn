package com.ultikits.ultitools.command.engine;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

/** Deliberately independent of MockBukkit's incomplete native argument provider. */
final class StringNativeArgumentTypes implements NativeArgumentTypes {
    @Override public ArgumentType<?> type(Class<?> type) {
        if (type.equals(org.bukkit.entity.Player.class) || type.equals(org.bukkit.OfflinePlayer.class)
                || type.equals(org.bukkit.World.class) || type.equals(org.bukkit.GameMode.class)
                || type.equals(java.util.UUID.class) || type.equals(org.bukkit.Material.class)
                || type.equals(org.bukkit.enchantments.Enchantment.class) || type.equals(org.bukkit.Location.class)) {
            return StringArgumentType.string();
        }
        throw new CommandCompilationException("No mapped argument type: " + type.getName());
    }
}
