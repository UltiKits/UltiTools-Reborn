package com.ultikits.ultitools.config.convert.builtin;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.config.convert.ConverterRegistry.Context;
import com.ultikits.ultitools.config.document.PlainData;

/** Explicit normalization of legacy/Bukkit output containers, never a reflective object walk. */
@ApiStatus.Internal
public final class PlainNormalizer {
    private PlainNormalizer() { }

    /**
     * Converts a known output container and dispatches non-plain leaves through the module registry.
     * @param value output value
     * @param ctx located conversion context
     * @return plain data
     * @throws ConversionException when a leaf or key cannot be represented
     */
    public static Object normalize(Object value, Context ctx) throws ConversionException {
        if (value == null || PlainData.isPlain(value)) { return PlainData.copy(value); }
        if (value instanceof ConfigurationSection) {
            return normalize(((ConfigurationSection) value).getValues(false), ctx);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Context keyContext = ctx.child(String.valueOf(entry.getKey()), Object.class);
                Object key = keyContext.writeTyped(entry.getKey(), Object.class);
                if (!(key instanceof String)) { throw keyContext.failure("Map keys must convert to text", null); }
                String text = (String) key;
                if (result.containsKey(text)) { throw keyContext.failure("Map keys collide after conversion", null); }
                result.put(text, normalize(entry.getValue(), ctx.child(text, Object.class)));
            }
            return result;
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            if (value instanceof Collection<?>) {
                int index = 0;
                for (Object item : (Collection<?>) value) {
                    result.add(normalize(item, ctx.child(Integer.toString(index++), Object.class)));
                }
            } else {
                for (int index = 0; index < Array.getLength(value); index++) {
                    result.add(normalize(Array.get(value, index), ctx.child(Integer.toString(index), Object.class)));
                }
            }
            return result;
        }
        return ctx.writeTyped(value, value.getClass());
    }
}
