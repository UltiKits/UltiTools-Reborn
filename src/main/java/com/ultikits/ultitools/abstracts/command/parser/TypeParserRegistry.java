package com.ultikits.ultitools.abstracts.command.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Module-defined parameter parsers. Built-in command types are mapped directly to Brigadier
 * and Paper argument types as of 7.0.0; this registry contains only explicitly registered parsers.
 */
public final class TypeParserRegistry {
    private static volatile TypeParserRegistry instance;
    private static final Object LOCK = new Object();
    private final Map<Class<?>, TypeParser<?>> parsers = new ConcurrentHashMap<>();
    private final List<TypeParser<?>> orderedParsers = new ArrayList<>();

    private TypeParserRegistry() { /* Custom parsers are registered by modules during initialization. */ }

    /** @return the singleton custom-parser registry */
    public static TypeParserRegistry getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) instance = new TypeParserRegistry();
            }
        }
        return instance;
    }

    /** @param parser the parser to register @param <T> its result type */
    public <T> void register(TypeParser<T> parser) {
        Objects.requireNonNull(parser, "Parser cannot be null");
        for (Class<?> type : parser.getSupportedTypes()) parsers.put(type, parser);
        orderedParsers.add(parser);
        orderedParsers.sort((p1, p2) -> Integer.compare(p2.getPriority(), p1.getPriority()));
    }

    /** @param parser the exact parser to unregister @param <T> its result type */
    public <T> void unregister(TypeParser<T> parser) {
        for (Class<?> type : parser.getSupportedTypes()) parsers.remove(type, parser);
        orderedParsers.remove(parser);
    }

    /** @param type the declared type @param <T> the result type @return its custom parser, or null */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> TypeParser<T> getParser(Class<T> type) {
        TypeParser<?> direct = parsers.get(type);
        if (direct != null) return (TypeParser<T>) direct;
        for (TypeParser<?> parser : orderedParsers) {
            if (parser.supports(type)) return (TypeParser<T>) parser;
        }
        return null;
    }

    /**
     * Parses with an explicitly registered module parser.
     * @param value the input
     * @param type the declared type
     * @param <T> the result type
     * @return the parsed result
     * @throws TypeParseException if no parser exists or it rejects the input
     */
    public <T> T parse(String value, Class<T> type) throws TypeParseException {
        TypeParser<T> parser = getParser(type);
        if (parser == null) throw new TypeParseException(value, type, "No parser registered for type: " + type.getName());
        return parser.parse(value);
    }

    /**
     * Parses an array with an explicitly registered module parser.
     * @param values the input values
     * @param type the component type
     * @param <T> the result component type
     * @return the parsed array
     * @throws TypeParseException if no parser exists or it rejects the input
     */
    public <T> T[] parseArray(String[] values, Class<T> type) throws TypeParseException {
        TypeParser<T> parser = getParser(type);
        if (parser == null) throw new TypeParseException(String.join(",", values), type, "No parser registered for type: " + type.getName());
        return parser.parseArray(values);
    }

    /** @param type the declared type @return whether a custom parser is registered */
    public boolean hasParser(Class<?> type) { return getParser(type) != null; }

    /** @return the explicitly registered parsers in priority order */
    public List<TypeParser<?>> getAllParsers() { return Collections.unmodifiableList(orderedParsers); }
}
