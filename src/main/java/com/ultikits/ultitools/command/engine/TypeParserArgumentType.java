package com.ultikits.ultitools.command.engine;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;

/** Adapts a module's registered parser without a second string-dispatch implementation. */
final class TypeParserArgumentType<T> implements CustomArgumentType<T, String> {
    private final TypeParser<T> parser;
    TypeParserArgumentType(TypeParser<T> parser) { this.parser = parser; }
    @Override public T parse(StringReader reader) throws CommandSyntaxException {
        String value = new TokenArgumentType().parse(reader);
        try { return parser.parse(value); }
        catch (TypeParseException error) {
            throw new ParserFailure(error.getMessage()).createWithContext(reader);
        }
    }
    @Override public ArgumentType<String> getNativeType() { return StringArgumentType.string(); }

    /** Keeps module parser failures identifiable when a greedy array resolves its elements. */
    static final class ParserFailure extends SimpleCommandExceptionType {
        ParserFailure(String message) { super(new LiteralMessage(message)); }
    }
}
