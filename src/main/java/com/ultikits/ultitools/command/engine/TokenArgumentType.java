package com.ultikits.ultitools.command.engine;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/** A whitespace-delimited Unicode token, or a Brigadier double-quoted string. */
final class TokenArgumentType implements CustomArgumentType<String, String> {
    @Override public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '"') return reader.readQuotedString();
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        if (reader.getCursor() == start) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "token");
        return reader.getString().substring(start, reader.getCursor());
    }
    @Override public ArgumentType<String> getNativeType() { return StringArgumentType.string(); }
}
