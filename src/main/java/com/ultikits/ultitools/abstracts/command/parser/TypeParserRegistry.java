package com.ultikits.ultitools.abstracts.command.parser;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry for type parsers. Supports custom parser registration and provides built-in parsers.
 * Thread-safe singleton implementation.
 * <p>
 * Built-in coverage: {@code String} (highest priority), the primitive wrapper types and their
 * primitive/array forms ({@code Boolean}, {@code Integer}, {@code Double}, {@code Float},
 * {@code Long}, {@code Short}, {@code Byte}), the core Bukkit types {@code Player},
 * {@code OfflinePlayer}, {@code Material}, {@code UUID}, and the extended Bukkit types
 * {@code World}, {@code Location}, {@code GameMode}, {@code Enchantment}. Register additional
 * types with {@link #register(TypeParser)}.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public final class TypeParserRegistry {
    
    private static volatile TypeParserRegistry instance;
    private static final Object LOCK = new Object();
    
    private final Map<Class<?>, TypeParser<?>> parsers = new ConcurrentHashMap<>();
    private final List<TypeParser<?>> orderedParsers = new ArrayList<>();
    
    private TypeParserRegistry() {
        registerBuiltInParsers();
    }
    
    /**
     * Gets the singleton instance of the registry.
     *
     * @return the registry instance
     */
    public static TypeParserRegistry getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new TypeParserRegistry();
                }
            }
        }
        return instance;
    }
    
    /**
     * Registers a type parser.
     *
     * @param parser the parser to register
     * @param <T>    the type the parser handles
     */
    public <T> void register(TypeParser<T> parser) {
        Objects.requireNonNull(parser, "Parser cannot be null");
        for (Class<?> type : parser.getSupportedTypes()) {
            parsers.put(type, parser);
        }
        orderedParsers.add(parser);
        orderedParsers.sort((p1, p2) -> Integer.compare(p2.getPriority(), p1.getPriority()));
    }
    
    /**
     * Unregisters a type parser.
     *
     * @param parser the parser to unregister
     * @param <T>    the type the parser handles
     */
    public <T> void unregister(TypeParser<T> parser) {
        for (Class<?> type : parser.getSupportedTypes()) {
            parsers.remove(type, parser);
        }
        orderedParsers.remove(parser);
    }
    
    /**
     * Gets a parser for the specified type.
     *
     * @param type the type to get a parser for
     * @param <T>  the type
     * @return the parser, or null if none found
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> TypeParser<T> getParser(Class<T> type) {
        TypeParser<?> direct = parsers.get(type);
        if (direct != null) {
            return (TypeParser<T>) direct;
        }
        // Search by supports check
        for (TypeParser<?> parser : orderedParsers) {
            if (parser.supports(type)) {
                return (TypeParser<T>) parser;
            }
        }
        return null;
    }
    
    /**
     * Parses a value to the specified type.
     *
     * @param value the string value to parse
     * @param type  the target type
     * @param <T>   the type
     * @return the parsed value
     * @throws TypeParseException if no parser found or parsing fails
     */
    public <T> T parse(String value, Class<T> type) throws TypeParseException {
        TypeParser<T> parser = getParser(type);
        if (parser == null) {
            throw new TypeParseException(value, type, "No parser registered for type: " + type.getName());
        }
        return parser.parse(value);
    }
    
    /**
     * Parses values to an array of the specified type.
     *
     * @param values the string values to parse
     * @param type   the target component type
     * @param <T>    the type
     * @return the parsed array
     * @throws TypeParseException if no parser found or parsing fails
     */
    public <T> T[] parseArray(String[] values, Class<T> type) throws TypeParseException {
        TypeParser<T> parser = getParser(type);
        if (parser == null) {
            throw new TypeParseException(String.join(",", values), type, 
                    "No parser registered for type: " + type.getName());
        }
        return parser.parseArray(values);
    }
    
    /**
     * Checks if a parser is registered for the specified type.
     *
     * @param type the type to check
     * @return true if a parser is registered
     */
    public boolean hasParser(Class<?> type) {
        return getParser(type) != null;
    }
    
    /**
     * Gets all registered parsers.
     *
     * @return unmodifiable list of parsers
     */
    public List<TypeParser<?>> getAllParsers() {
        return Collections.unmodifiableList(orderedParsers);
    }
    
    /**
     * Registers all built-in parsers.
     */
    @SuppressWarnings("deprecation")
    private void registerBuiltInParsers() {
        // String parser (highest priority)
        register(new SimpleTypeParser<>(String.class, s -> s, 100));
        
        // Primitive type parsers
        register(new SimpleTypeParser<>(Boolean.class, Boolean::parseBoolean, 
                Arrays.asList(Boolean.class, boolean.class, Boolean[].class, boolean[].class)));
        register(new SimpleTypeParser<>(Integer.class, Integer::parseInt,
                Arrays.asList(Integer.class, int.class, Integer[].class, int[].class)));
        register(new SimpleTypeParser<>(Double.class, Double::parseDouble,
                Arrays.asList(Double.class, double.class, Double[].class, double[].class)));
        register(new SimpleTypeParser<>(Float.class, Float::parseFloat,
                Arrays.asList(Float.class, float.class, Float[].class, float[].class)));
        register(new SimpleTypeParser<>(Long.class, Long::parseLong,
                Arrays.asList(Long.class, long.class, Long[].class, long[].class)));
        register(new SimpleTypeParser<>(Short.class, Short::parseShort,
                Arrays.asList(Short.class, short.class, Short[].class, short[].class)));
        register(new SimpleTypeParser<>(Byte.class, Byte::parseByte,
                Arrays.asList(Byte.class, byte.class, Byte[].class, byte[].class)));
        
        // Bukkit type parsers
        register(new SimpleTypeParser<>(Player.class, Bukkit::getPlayer,
                Arrays.asList(Player.class, Player[].class)));
        register(new SimpleTypeParser<>(OfflinePlayer.class, Bukkit::getOfflinePlayer,
                Arrays.asList(OfflinePlayer.class, OfflinePlayer[].class)));
        register(new SimpleTypeParser<>(Material.class, Material::getMaterial,
                Arrays.asList(Material.class, Material[].class)));
        register(new SimpleTypeParser<>(UUID.class, UUID::fromString,
                Arrays.asList(UUID.class, UUID[].class)));
        
        // Extended Bukkit type parsers
        register(new WorldParser());
        register(new GameModeParser());
        register(new LocationParser());
        register(new EnchantmentParser());
    }
    
    /**
     * Simple implementation of TypeParser using a parsing function.
     */
    private static class SimpleTypeParser<T> implements TypeParser<T> {
        private final Class<T> primaryType;
        private final Function<String, T> parseFunction;
        private final List<Class<?>> supportedTypes;
        private final int priority;
        
        SimpleTypeParser(Class<T> primaryType, Function<String, T> parseFunction, int priority) {
            this.primaryType = primaryType;
            this.parseFunction = parseFunction;
            this.supportedTypes = Arrays.asList(primaryType, 
                    java.lang.reflect.Array.newInstance(primaryType, 0).getClass());
            this.priority = priority;
        }
        
        SimpleTypeParser(Class<T> primaryType, Function<String, T> parseFunction, List<Class<?>> supportedTypes) {
            this.primaryType = primaryType;
            this.parseFunction = parseFunction;
            this.supportedTypes = supportedTypes;
            this.priority = 0;
        }
        
        @Override
        public Class<T> getPrimaryType() {
            return primaryType;
        }
        
        @Override
        public List<Class<?>> getSupportedTypes() {
            return supportedTypes;
        }
        
        @Override
        @Nullable
        public T parse(String value) throws TypeParseException {
            if (value == null) {
                return null;
            }
            try {
                return parseFunction.apply(value);
            } catch (Exception e) {
                throw new TypeParseException(value, primaryType, 
                        "Failed to parse '" + value + "' as " + primaryType.getSimpleName(), e);
            }
        }
        
        @Override
        public int getPriority() {
            return priority;
        }
    }
}
