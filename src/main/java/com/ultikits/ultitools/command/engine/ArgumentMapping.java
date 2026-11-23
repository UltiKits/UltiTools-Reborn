package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Array;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** One declared Java parameter and its node's parse/result representation. */
record ArgumentMapping(Class<?> javaType, boolean greedy, ArgumentType<?> type, NativeArgumentTypes nativeTypes) {
    static ArgumentMapping of(Class<?> type, boolean greedy, NativeArgumentTypes nativeTypes) {
        if (greedy) {
            if (!type.isArray() && !type.equals(String.class)) {
                throw new CommandCompilationException("greedy parameter type must be String or an array");
            }
            if (type.isArray()) single(type.getComponentType(), nativeTypes);
            return new ArgumentMapping(type, true, StringArgumentType.greedyString(), nativeTypes);
        }
        return new ArgumentMapping(type, false, single(type, nativeTypes), nativeTypes);
    }

    private static ArgumentType<?> single(Class<?> type, NativeArgumentTypes nativeTypes) {
        if (type.equals(String.class)) return StringArgumentType.string();
        if (type.equals(int.class) || type.equals(Integer.class)) return IntegerArgumentType.integer();
        if (type.equals(long.class) || type.equals(Long.class)) return LongArgumentType.longArg();
        if (type.equals(double.class) || type.equals(Double.class)) return DoubleArgumentType.doubleArg();
        if (type.equals(float.class) || type.equals(Float.class)) return FloatArgumentType.floatArg();
        if (type.equals(boolean.class) || type.equals(Boolean.class)) return BoolArgumentType.bool();
        if (type.equals(short.class) || type.equals(Short.class)) return IntegerArgumentType.integer(Short.MIN_VALUE, Short.MAX_VALUE);
        if (type.equals(byte.class) || type.equals(Byte.class)) return IntegerArgumentType.integer(Byte.MIN_VALUE, Byte.MAX_VALUE);
        return nativeTypes.type(type);
    }

    Object value(CommandContext<CommandSourceStack> context, String name, boolean empty)
            throws CommandSyntaxException {
        Object raw = empty ? "" : context.getArgument(name, Object.class);
        if (!greedy) return narrow(javaType, nativeTypes.resolve(javaType, raw, context.getSource()));
        String text = (String) raw;
        if (javaType.equals(String.class)) return text;
        Class<?> element = javaType.getComponentType();
        String[] tokens = text.isBlank() ? new String[0] : text.trim().split("\\s+");
        Object result = Array.newInstance(element, tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            StringReader reader = new StringReader(tokens[i]);
            Object parsed = single(element, nativeTypes).parse(reader, context.getSource());
            if (reader.canRead()) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
            Array.set(result, i, narrow(element, nativeTypes.resolve(element, parsed, context.getSource())));
        }
        return result;
    }

    private static Object narrow(Class<?> type, Object value) {
        if (type.equals(short.class) || type.equals(Short.class)) return ((Number) value).shortValue();
        if (type.equals(byte.class) || type.equals(Byte.class)) return ((Number) value).byteValue();
        return value;
    }
    String signature() { return javaType.getName() + (greedy ? ":greedy" : ":" + type.getClass().getName()); }
}
