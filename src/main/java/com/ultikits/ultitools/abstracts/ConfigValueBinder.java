package com.ultikits.ultitools.abstracts;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Converts the value a {@code @ConfigEntry}'s parser read from the file into the shape the field
 * declares (UltiKits/UltiTools-Reborn#523, #526).
 * <p>
 * A whole value that cannot be converted to its field's declared type is reported and left unbound,
 * so the field keeps its declared default and the module still loads (#526; see {@link #bind}).
 * <p>
 * A collection field receives elements of its declared element type and a map field receives
 * values (and keys) of its declared types, read from the field's generic signature. A numeric
 * string - which is what the pre-6.3.0 parser wrote back for every list element - converts to a
 * numeric type. An element or map entry that cannot be converted is skipped with one warning naming
 * the file, the key, the raw value and the declared type, and the rest of the value is kept - the
 * maintainer's rule of 2026-09-29. A value is never turned into text unless the declared type is
 * {@code String} (or an unparameterised element type, which keeps the pre-6.3.0 text form).
 * <p>
 * Only positions whose declared type the binder understands are checked: simple types
 * ({@code String}, the primitive wrappers, {@code Character}, enums), collections and maps. Any
 * other declared type in a nested position (for example UltiRecipe's {@code Map<String,
 * RecipeDefinition>}, whose values modules convert themselves) is passed through unchanged, exactly
 * as before.
 * <p>
 * Stateless apart from its reporting target; one instance per bound field.
 */
final class ConfigValueBinder {

    /**
     * Returned by the conversion helpers for a value that cannot be converted. Never stored in a
     * field.
     */
    static final Object UNBOUND = new Object();

    private static final List<Class<?>> INTEGRAL_TYPES = Collections.unmodifiableList(Arrays.<Class<?>>asList(
            Byte.class, Short.class, Integer.class, Long.class));

    private final String configFile;
    private final Consumer<String> reporter;

    /**
     * @param configFile the configuration file's path, named in every warning
     * @param reporter   receives one message per value that cannot be bound; a no-op reporter
     *                   makes the conversion silent (the #510 snapshot probe re-reads the file and
     *                   must not repeat the warnings {@code init} already gave)
     */
    ConfigValueBinder(String configFile, Consumer<String> reporter) {
        this.configFile = configFile;
        this.reporter = reporter;
    }

    /**
     * Converts {@code parsed}, the parser's result for {@code key}, to {@code field}'s declared
     * shape.
     * <p>
     * #526: the value's shape is compared with the declared type before anything is assigned. A
     * value that cannot be converted - a list or a scalar where a map is declared, a map or a scalar
     * where a collection is declared, text that is not a number where a number is declared, a number
     * that does not fit its field - is reported once, naming the file, the key, the declared type and
     * what the file holds, and {@link #UNBOUND} is returned so the caller keeps the field's declared
     * default. Before, {@code Field.set} threw {@code IllegalArgumentException} out of {@code init()}
     * and the module never loaded. A value that converts exactly is bound: a quoted number into a
     * number, a number or boolean into a {@code String}, a whole decimal into an integral field.
     *
     * @param field  the {@code @ConfigEntry} field
     * @param key    the entry's path, named in warnings
     * @param parsed what the entry's parser returned
     * @return the value to store in the field, or {@link #UNBOUND} to keep its declared default
     */
    Object bind(Field field, String key, Object parsed) {
        Class<?> type = field.getType();
        Object converted;
        if (parsed == null) {
            // A custom parser may return null; a reference field takes it, as before.
            converted = type.isPrimitive() ? UNBOUND : null;
        } else if (Collection.class.isAssignableFrom(type)) {
            converted = parsed instanceof Collection ? convert(parsed, field.getGenericType(), key) : UNBOUND;
        } else if (Map.class.isAssignableFrom(type)) {
            converted = parsed instanceof Map ? convert(parsed, field.getGenericType(), key) : UNBOUND;
        } else if (isSimple(type)) {
            converted = convertSimple(parsed, boxed(type));
        } else {
            converted = type.isInstance(parsed) ? parsed : UNBOUND;
        }
        if (converted == UNBOUND) {
            reporter.accept(String.format(
                    "Config file '%s': key '%s' is declared as %s but the file holds %s %s; "
                            + "the field keeps its default, the rest of the configuration loads",
                    configFile, key, typeName(field.getGenericType()), kindOf(parsed), describe(key, parsed)));
        }
        return converted;
    }

    /**
     * @param type a declared type
     * @return its name without package prefixes, for example {@code Map<String, Integer>}
     */
    static String typeName(Type type) {
        return type.getTypeName().replaceAll("\\b[a-z][A-Za-z0-9_]*\\.", "").replace('$', '.');
    }

    /**
     * @param value a parsed value
     * @return what an operator wrote, in words: {@code a list}, {@code a map}, {@code text},
     *         {@code a number}, {@code a boolean}
     */
    static String kindOf(Object value) {
        if (value == null) {
            return "an empty value";
        }
        if (value instanceof Collection) {
            return "a list";
        }
        if (value instanceof Map || value instanceof ConfigurationSection) {
            return "a map";
        }
        if (value instanceof String || value instanceof Character) {
            return "text";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        return "a " + value.getClass().getSimpleName();
    }

    /**
     * Converts {@code value} to {@code declared}, reporting and skipping any element or entry that
     * cannot be converted. Returns {@link #UNBOUND} when {@code value} itself cannot be converted,
     * after reporting it.
     */
    private Object convert(Object value, Type declared, String key) {
        Class<?> raw = rawClass(declared);
        if (Collection.class.isAssignableFrom(raw)) {
            return convertCollection(value, declared, raw, key);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return convertMap(value, declared, raw, key);
        }
        if (isSimple(raw)) {
            Object converted = convertSimple(value, boxed(raw));
            if (converted == UNBOUND) {
                reportElement(key, value, raw);
            }
            return converted;
        }
        // A type the binder does not model (Object, a module's own class): unchanged.
        return value;
    }

    private Object convertCollection(Object value, Type declared, Class<?> raw, String key) {
        if (!(value instanceof Collection)) {
            reportElement(key, value, raw);
            return UNBOUND;
        }
        Collection<Object> result = newCollection(raw);
        if (result == null) {
            reportElement(key, value, raw);
            return UNBOUND;
        }
        Type elementType = typeArgument(declared, 0);
        int index = 0;
        for (Object element : (Collection<?>) value) {
            String elementKey = key + "[" + index++ + "]";
            Object converted = convertElement(element, elementType, elementKey);
            if (converted != UNBOUND) {
                result.add(converted);
            }
        }
        return result;
    }

    private Object convertMap(Object value, Type declared, Class<?> raw, String key) {
        Map<?, ?> source;
        if (value instanceof Map) {
            source = (Map<?, ?>) value;
        } else {
            reportElement(key, value, raw);
            return UNBOUND;
        }
        Map<Object, Object> result = newMap(raw);
        if (result == null) {
            reportElement(key, value, raw);
            return UNBOUND;
        }
        Type keyType = typeArgument(declared, 0);
        Type valueType = typeArgument(declared, 1);
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String entryKey = key + "." + entry.getKey();
            Object convertedKey = entry.getKey();
            Class<?> rawKey = rawClass(keyType);
            if (isSimple(rawKey) && rawKey != String.class) {
                convertedKey = convertSimple(entry.getKey(), boxed(rawKey));
                if (convertedKey == UNBOUND) {
                    reportElement(entryKey, entry.getKey(), rawKey);
                    continue;
                }
            }
            Object convertedValue = convert(entry.getValue(), valueType, entryKey);
            if (convertedValue != UNBOUND) {
                result.put(convertedKey, convertedValue);
            }
        }
        return result;
    }

    /**
     * A list element: as {@link #convert}, except that an unparameterised element type keeps the
     * pre-6.3.0 form of a scalar element (its text) so a raw {@code List} field reads what it
     * always read.
     */
    private Object convertElement(Object element, Type elementType, String key) {
        if (element == null) {
            return null;
        }
        Object value = element instanceof ConfigurationSection
                ? ((ConfigurationSection) element).getValues(false) : element;
        Class<?> raw = rawClass(elementType);
        if (raw == Object.class) {
            return isScalar(value) ? value.toString() : value;
        }
        return convert(value, elementType, key);
    }

    /**
     * Converts a scalar to a simple target type, or returns {@link #UNBOUND}.
     *
     * @param value  the value read from the file
     * @param target a wrapper type, {@code String}, {@code Character} or an enum
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convertSimple(Object value, Class<?> target) {
        if (value == null) {
            return UNBOUND;
        }
        if (target.isInstance(value)) {
            return value;
        }
        if (target == String.class) {
            return isScalar(value) ? value.toString() : UNBOUND;
        }
        if (target == Boolean.class) {
            if (value instanceof String) {
                String text = ((String) value).trim();
                if ("true".equalsIgnoreCase(text)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return Boolean.FALSE;
                }
            }
            return UNBOUND;
        }
        if (target == Character.class) {
            if (value instanceof String && ((String) value).length() == 1) {
                return ((String) value).charAt(0);
            }
            return UNBOUND;
        }
        if (target.isEnum()) {
            return value instanceof String ? enumConstant((Class<? extends Enum>) target, (String) value) : UNBOUND;
        }
        if (Number.class.isAssignableFrom(target)) {
            return convertNumber(value, target);
        }
        return UNBOUND;
    }

    private static Object convertNumber(Object value, Class<?> target) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            return UNBOUND;
        }
        if (value instanceof Number) {
            Object widened = AbstractConfigEntity.widenToFieldType(target, value);
            if (target.isInstance(widened)) {
                return widened;
            }
        }
        if (target == Double.class) {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return UNBOUND;
            }
        }
        BigDecimal decimal = toBigDecimal(value);
        if (decimal == null) {
            return UNBOUND;
        }
        try {
            if (target == Long.class) {
                return decimal.longValueExact();
            }
            if (target == Integer.class) {
                return decimal.intValueExact();
            }
            if (target == Short.class) {
                return decimal.shortValueExact();
            }
            if (target == Byte.class) {
                return decimal.byteValueExact();
            }
        } catch (ArithmeticException e) {
            return UNBOUND;
        }
        return UNBOUND;
    }

    private static BigDecimal toBigDecimal(Object value) {
        try {
            if (value instanceof String) {
                return new BigDecimal(((String) value).trim());
            }
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            if (value instanceof BigInteger) {
                return new BigDecimal((BigInteger) value);
            }
            Class<?> type = value.getClass();
            if (INTEGRAL_TYPES.contains(type)) {
                return BigDecimal.valueOf(((Number) value).longValue());
            }
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return new BigDecimal(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<? extends Enum> type, String name) {
        String text = name.trim();
        for (Enum constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        Object match = UNBOUND;
        for (Enum constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(text)) {
                if (match != UNBOUND) {
                    return UNBOUND;
                }
                match = constant;
            }
        }
        return match;
    }

    private void reportElement(String key, Object value, Class<?> declared) {
        reporter.accept(String.format(
                "Config file '%s': key '%s' holds %s, which cannot be converted to %s; skipped, the rest of the configuration loads",
                configFile, key, describe(key, value), declared.getSimpleName()));
    }

    /**
     * Describes a value for a warning: its text, quoted, or {@code <redacted>} when the key looks
     * like it names a secret (T-17-41-01).
     */
    static String describe(String key, Object value) {
        if (AbstractConfigEntity.isSecretShapedFieldName(key)) {
            return "<redacted>";
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof ConfigurationSection) {
            return "a section";
        }
        return "'" + value + "'";
    }

    static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character;
    }

    static boolean isSimple(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Boolean.class || type == Character.class
                || type.isEnum() || (Number.class.isAssignableFrom(type) && type.getName().startsWith("java.lang."));
    }

    static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length == 1 ? rawClass(upper[0]) : Object.class;
        }
        return Object.class;
    }

    private static Type typeArgument(Type declared, int index) {
        if (declared instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) declared).getActualTypeArguments();
            if (index < arguments.length) {
                return arguments[index];
            }
        }
        return Object.class;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(Class<?> declared) {
        if (declared.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (declared.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        return (Collection<Object>) instantiate(declared);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(Class<?> declared) {
        if (declared.isAssignableFrom(LinkedHashMap.class)) {
            return new LinkedHashMap<>();
        }
        return (Map<Object, Object>) instantiate(declared);
    }

    private static Object instantiate(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
