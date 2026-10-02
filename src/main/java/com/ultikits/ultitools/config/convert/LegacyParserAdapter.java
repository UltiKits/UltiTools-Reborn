package com.ultikits.ultitools.config.convert;

import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import com.ultikits.ultitools.config.document.PlainData;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.builtin.PlainNormalizer;
import com.ultikits.ultitools.config.convert.ConverterRegistry.Context;

/** Frozen legacy parser semantics behind a detached input and explicit plain-output boundary. */
@ApiStatus.Internal
public final class LegacyParserAdapter implements ConfigConverter<Object> {
    /**
     * The numeric wrappers in JLS 5.1.2 widening order: every conversion from an earlier entry to a
     * later one is a widening primitive conversion, and no other conversion between them is.
     */
    private static final List<Class<?>> WIDENING_ORDER = Collections.unmodifiableList(Arrays.<Class<?>>asList(
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class));

    /** Converts a {@link Number} to the wrapper at the same index of {@link #WIDENING_ORDER}. */
    private static final List<Function<Number, Object>> WIDENERS =
            Collections.unmodifiableList(Arrays.<Function<Number, Object>>asList(
                    Number::byteValue, Number::shortValue, Number::intValue, Number::longValue,
                    Number::floatValue, Number::doubleValue));


    // Stores the legacy implementation class, not a shared parser instance.
    @SuppressWarnings("removal")
    private final Class<? extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser> parserClass;

    /**
     * @param parserClass legacy parser's public no-argument implementation
     */
    // Accepts legacy implementations until their announced removal version.
    @SuppressWarnings("removal")
    public LegacyParserAdapter(Class<? extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser> parserClass) { this.parserClass = parserClass; }

    @Override
    // Invokes the frozen final legacy serialize contract on the original value.
    @SuppressWarnings({"unchecked", "removal"})
    public Object toPlain(Object value, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        return PlainNormalizer.normalize(((com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<Object>) parser(ctx)).serialize(value), ctx);
    }

    @Override
    // Invokes the frozen legacy parse contract on detached input.
    @SuppressWarnings("removal")
    public Object fromPlain(Object plain, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        Object detached = PlainData.copy(plain);
        Object parsed = parser(ctx).parse(detached instanceof Map<?, ?> ? section((Map<?, ?>) detached) : detached);
        Class<?> declared = ConverterRegistry.rawClass(ctx.declaredType());
        Class<?> target = boxed(declared);
        Object value = widenToFieldType(target, parsed);
        if (value == null ? declared.isPrimitive() : !target.isInstance(value)) {
            throw ctx.failure("Legacy parser result cannot be assigned to " + declared.getTypeName(), null);
        }
        return value;
    }

    // Creates a fresh public-noarg legacy parser for every conversion.
    @SuppressWarnings("removal")
    private com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<?> parser(Context ctx) throws ConversionException {
        try { return parserClass.getConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) {
            throw ctx.failure("Cannot construct legacy parser " + parserClass.getName(), failure);
        }
    }

    /**
     * Gives a boxed numeric field exactly the widening conversions its primitive already gets.
     * <p>
     * SnakeYAML hands back an {@code Integer} for a whole number such as {@code 1800}.
     * {@code Field.set} widens that into a {@code long} or {@code double} field, but it refuses the
     * same value for a {@code Long}, {@code Double} or {@code Float} field (measured:
     * {@code IllegalArgumentException: Can not set java.lang.Long field ... to java.lang.Integer}).
     * A boxed field therefore loaded on the first boot, when the key was missing and the field
     * default was written, and threw on every later boot and on every reload (#531, gate-1 CR-01).
     * <p>
     * Only the JLS 5.1.2 widening primitive conversions are applied, so a boxed field accepts
     * exactly what its primitive accepts: {@code Short} from {@code Byte}; {@code Integer} from
     * {@code Byte}/{@code Short}; {@code Long} from {@code Byte}/{@code Short}/{@code Integer};
     * {@code Float} from any integral value; {@code Double} from any integral value or a
     * {@code Float}. Anything else -- a narrowing conversion, a non-numeric value, a field that
     * is not a numeric wrapper -- is returned unchanged, so {@code Field.set} refuses it exactly as
     * before.
     *
     * @param fieldType the declared type of the target field
     * @param value     the parsed value
     * @return {@code value} widened to {@code fieldType}, or {@code value} itself
     */
    static Object widenToFieldType(Class<?> fieldType, Object value) {
        if (!(value instanceof Number) || fieldType.isInstance(value)) {
            return value;
        }
        int from = WIDENING_ORDER.indexOf(value.getClass());
        int to = WIDENING_ORDER.indexOf(fieldType);
        // Not a numeric wrapper pair, or a narrowing conversion: unchanged, so Field.set refuses it.
        if (from < 0 || to <= from) {
            return value;
        }
        return WIDENERS.get(to).apply((Number) value);
    }


    private static Class<?> boxed(Class<?> type) {
        if (type == byte.class) { return Byte.class; }
        if (type == short.class) { return Short.class; }
        if (type == int.class) { return Integer.class; }
        if (type == long.class) { return Long.class; }
        if (type == float.class) { return Float.class; }
        if (type == double.class) { return Double.class; }
        if (type == boolean.class) { return Boolean.class; }
        if (type == char.class) { return Character.class; }
        return type;
    }

    private static MemorySection section(Map<?, ?> plain) {
        MemoryConfiguration section = new MemoryConfiguration();
        for (Map.Entry<?, ?> entry : plain.entrySet()) {
            Object value = entry.getValue();
            section.set(String.valueOf(entry.getKey()), value instanceof Map<?, ?> ? section((Map<?, ?>) value) : value);
        }
        return section;
    }
}
