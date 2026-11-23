package com.ultikits.ultitools.command.engine;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Server-specific argument seam; tests do not need Paper's native registry. */
interface NativeArgumentTypes {
    ArgumentType<?> type(Class<?> javaType);
    default Object resolve(Class<?> javaType, Object value, CommandSourceStack source)
            throws CommandSyntaxException { return value; }
}
