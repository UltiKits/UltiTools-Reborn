package com.ultikits.ultitools.config.convert.builtin;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.config.convert.ConverterRegistry.Context;

/** Bukkit-owned serialization and world identity, without inspecting server implementation fields. */
@ApiStatus.Internal
public final class BukkitConverters {
    private BukkitConverters() { }

    /** Converter for the Bukkit world-name contract. */
    public static final ConfigConverter<World> WORLD = new ConfigConverter<World>() {
        @Override public Object toPlain(World value, ConversionContext ctx) { return value.getName(); }
        @Override public World fromPlain(Object plain, ConversionContext context) throws ConversionException {
            Context ctx = (Context) context;
            if (!(plain instanceof String)) { throw ctx.failure("Expected a world name", null); }
            World world = Bukkit.getWorld((String) plain);
            if (world == null) { throw ctx.failure("Unknown world " + plain, null); }
            return world;
        }
    };

    /** Fallback after registered hierarchy and generic factories, not an ordinary hierarchy entry. */
    public static final ConfigConverter<ConfigurationSerializable> SERIALIZABLE = new ConfigConverter<ConfigurationSerializable>() {
        @Override
        public Object toPlain(ConfigurationSerializable value, ConversionContext context) throws ConversionException {
            Context ctx = (Context) context;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : value.serialize().entrySet()) {
                result.put(entry.getKey(), PlainNormalizer.normalize(entry.getValue(), ctx.child(entry.getKey(), Object.class)));
            }
            result.put(ConfigurationSerialization.SERIALIZED_TYPE_KEY, ConfigurationSerialization.getAlias(value.getClass()));
            return result;
        }

        @Override
        public ConfigurationSerializable fromPlain(Object plain, ConversionContext context) throws ConversionException {
            Context ctx = (Context) context;
            if (!(plain instanceof Map<?, ?>)) { throw ctx.failure("Expected a serialized Bukkit map", null); }
            Map<String, Object> values = hydrateValues((Map<?, ?>) plain, ctx);
            Object alias = values.get(ConfigurationSerialization.SERIALIZED_TYPE_KEY);
            widenVectorCoordinates(values, alias);
            try {
                ConfigurationSerializable result = ConfigurationSerialization.deserializeObject(values);
                if (result == null || !ConversionTypes.raw(ctx.declaredType()).isInstance(result)) {
                    throw ctx.failure("Alias " + alias + " did not produce " + ctx.declaredType().getTypeName(), null);
                }
                return result;
            } catch (RuntimeException failure) {
                throw ctx.failure("Cannot deserialize alias " + alias + " (world " + values.get("world") + ")", failure);
            }
        }
    };

    /**
     * Bukkit's {@code Vector#deserialize} (and {@code BlockVector}'s, Bukkit 1.21.11) casts each coordinate to
     * {@code Double}, so a whole number an operator wrote ({@code y: 64}) failed with a ClassCastException that Bukkit
     * logs at SEVERE, and the module ran on its declared default (17-66 review round 1 R66-W1, #609). The coordinates
     * {@code x}, {@code y} and {@code z} of a {@code Vector} (or subclass) are widened to {@code double} in this
     * in-memory copy only: the file is never touched, so the operator's text stays as written, and no other
     * serializable type is widened (several Bukkit types read their numbers as {@code Integer}).
     */
    private static void widenVectorCoordinates(Map<String, Object> values, Object alias) {
        Class<? extends ConfigurationSerializable> type = alias instanceof String
                ? ConfigurationSerialization.getClassByAlias((String) alias) : null;
        if (type == null || !Vector.class.isAssignableFrom(type)) { return; }
        for (String axis : new String[]{"x", "y", "z"}) {
            Object value = values.get(axis);
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                    || value instanceof BigInteger) {
                values.put(axis, ((Number) value).doubleValue());
            }
        }
    }

    private static Map<String, Object> hydrateValues(Map<?, ?> plain, Context ctx) throws ConversionException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : plain.entrySet()) {
            if (!(entry.getKey() instanceof String)) { throw ctx.failure("Serialized map key must be text", null); }
            String key = (String) entry.getKey();
            result.put(key, hydrate(entry.getValue(), ctx.child(key, ConfigurationSerializable.class)));
        }
        return result;
    }

    private static Object hydrate(Object plain, Context ctx) throws ConversionException {
        if (plain instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            int index = 0;
            for (Object item : (List<?>) plain) {
                result.add(hydrate(item, ctx.child(Integer.toString(index++), ConfigurationSerializable.class)));
            }
            return result;
        }
        if (!(plain instanceof Map<?, ?>)) { return plain; }
        Map<?, ?> map = (Map<?, ?>) plain;
        if (!map.containsKey(ConfigurationSerialization.SERIALIZED_TYPE_KEY)) { return hydrateValues(map, ctx); }
        Object alias = map.get(ConfigurationSerialization.SERIALIZED_TYPE_KEY);
        Class<? extends ConfigurationSerializable> factory = alias instanceof String
                ? ConfigurationSerialization.getClassByAlias((String) alias) : null;
        if (factory == null) { throw ctx.failure("Unknown Bukkit alias " + alias, null); }
        return ctx.readSerializable(map, factory);
    }
}
