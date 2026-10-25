package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.config.document.ConfigDocument;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The typed entry model of one module configuration file, and the per-key check of a panel change.
 *
 * <p>An entry is one {@code @ConfigEntry} field whose declared type the frozen protocol can carry: a
 * {@code String} ({@code string}), an {@code int}/{@code long}/{@code short}/{@code byte} and their boxed
 * forms ({@code integer}), a {@code float}/{@code double} and their boxed forms ({@code number}), a
 * {@code boolean} ({@code boolean}), a {@code List<String>} ({@code string_list}) or an enum
 * ({@code enum}, whose constant names are the allowed options). Every other declared type - a map, an
 * object, a Bukkit value, a list of anything but strings - is not editable through the panel and is not
 * listed, so a panel change can only ever name a value the contract has a shape for.</p>
 *
 * <p>Values come from the file's raw bytes through {@link ConfigDocument}, never from the module's
 * in-memory state: the panel reads what the operator's file holds, and the version it then sends back
 * is the hash of exactly those bytes. An entry whose value cannot be carried inside the contract's own
 * bounds (a text longer than 8192 characters, a list longer than 500 items, an integer outside the safe
 * integer range) is omitted rather than truncated, because a truncated value is one the panel would
 * write back.</p>
 *
 * <p>Every frame the builder produces therefore stays inside the vendored
 * {@code config.entries.result} schema: bounds are applied while the entry is built, and an entry that
 * cannot satisfy them is dropped, never emitted in a shape the schema rejects.</p>
 *
 * @since 6.3.1
 */
final class RemoteConfigEntries {

    /** Contract entry types, as {@code config.entries.result} spells them. */
    static final String TYPE_STRING = "string";
    static final String TYPE_INTEGER = "integer";
    static final String TYPE_NUMBER = "number";
    static final String TYPE_BOOLEAN = "boolean";
    static final String TYPE_STRING_LIST = "string_list";
    static final String TYPE_ENUM = "enum";

    /** The largest whole number the wire can carry exactly (2^53 - 1). */
    private static final long SAFE_INTEGER = 9007199254740991L;
    private static final int KEY_MAX = 256;
    private static final int LABEL_MAX = 256;
    private static final int COMMENT_MAX = 2000;
    private static final int VALUE_TEXT_MAX = 8192;
    private static final int LIST_MAX = 500;
    private static final int LIST_ITEM_MAX = 1024;
    private static final int OPTIONS_MAX = 200;
    private static final int OPTIONS_ITEM_MAX = 256;
    /** {@code config.entries.result} allows at most this many entries. */
    static final int ENTRIES_MAX = 2000;

    private RemoteConfigEntries() {
    }

    /** The declared Java types the contract can carry. */
    enum Kind {
        STRING(TYPE_STRING),
        INTEGER(TYPE_INTEGER),
        NUMBER(TYPE_NUMBER),
        BOOLEAN(TYPE_BOOLEAN),
        STRING_LIST(TYPE_STRING_LIST),
        ENUM(TYPE_ENUM);

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** @return the entry type name {@code config.entries.result} uses */
        String wireName() {
            return wireName;
        }
    }

    /** One editable setting: its dotted key, label, type, comment line, value and constraints. */
    static final class Entry {
        private final String key;
        private final String label;
        private final Kind kind;
        private final String comment;
        private final JsonElement value;
        private final JsonObject constraints;
        private final List<String> options;

        private Entry(String key, String label, Kind kind, String comment, JsonElement value,
                JsonObject constraints, List<String> options) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.comment = comment;
            this.value = value;
            this.constraints = constraints;
            this.options = options;
        }

        String key() {
            return key;
        }

        Kind kind() {
            return kind;
        }

        /** @return the key as the YAML path it addresses */
        List<String> path() {
            return Arrays.asList(key.split("\\.", -1));
        }

        /** @return the {@code config.entries.result} entry object; callers add it to the array */
        JsonObject toWire() {
            JsonObject wire = new JsonObject();
            wire.addProperty("key", key);
            wire.addProperty("label", label);
            if (comment == null) {
                wire.add("comment", JsonNull.INSTANCE);
            } else {
                wire.addProperty("comment", comment);
            }
            wire.addProperty("type", kind.wireName());
            wire.add("value", value);
            if (constraints != null) {
                wire.add("constraints", constraints);
            }
            return wire;
        }
    }

    /**
     * Maps one field's declared type to the contract entry type.
     *
     * @param field the {@code @ConfigEntry} field
     * @return the kind, or null when the type is not editable through the panel
     */
    static Kind kindOf(Field field) {
        Class<?> raw = field.getType();
        if (raw == String.class) {
            return Kind.STRING;
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class) {
            return Kind.INTEGER;
        }
        if (raw == float.class || raw == Float.class || raw == double.class || raw == Double.class) {
            return Kind.NUMBER;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Kind.BOOLEAN;
        }
        if (raw == List.class && stringList(field)) {
            return Kind.STRING_LIST;
        }
        if (raw.isEnum()) {
            return Kind.ENUM;
        }
        return null;
    }

    private static boolean stringList(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType)) {
            return false;
        }
        Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] == String.class;
    }

    /**
     * The dotted key of one {@code @ConfigEntry} field: its {@code path()} split at every dot, or the
     * field name when no path is declared - the same rule the entity applies when it binds the field.
     *
     * @param field the annotated field
     * @return the dotted key
     */
    static String keyOf(Field field) {
        ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
        String declared = annotation == null ? "" : annotation.path();
        return declared.isEmpty() ? field.getName() : declared;
    }

    /** @param key a dotted key @return whether the contract's key pattern accepts it */
    static boolean usableKey(String key) {
        if (key == null || key.isEmpty() || key.length() > KEY_MAX) {
            return false;
        }
        String[] parts = key.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c <= 0x1F) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Builds the editable entries of one file as it is on disk.
     *
     * @param entity   the module's config entity that registered the file
     * @param document the document loaded from the file's current bytes
     * @return the entries the panel may show, in declaration order, bounded by the contract
     */
    static List<Entry> describe(AbstractConfigEntity entity, ConfigDocument document) {
        JsonObject comments = entity.getComments();
        List<Entry> entries = new ArrayList<>();
        for (Field field : ReflectionUtil.getFields(entity.getClass())) {
            if (field.getAnnotation(ConfigEntry.class) == null) {
                continue;
            }
            Kind kind = kindOf(field);
            if (kind == null) {
                continue;
            }
            String key = keyOf(field);
            if (!usableKey(key)) {
                continue;
            }
            List<String> options = kind == Kind.ENUM ? options(field) : Collections.<String>emptyList();
            if (kind == Kind.ENUM && options.isEmpty()) {
                continue;
            }
            JsonElement value = valueOf(document.get(Arrays.asList(key.split("\\.", -1))), kind, options);
            if (value == null) {
                continue;
            }
            String comment = commentOf(comments, key);
            String label = label(key);
            entries.add(new Entry(key, label, kind, comment, value, constraints(field, kind, options), options));
            if (entries.size() >= ENTRIES_MAX) {
                break;
            }
        }
        return entries;
    }

    private static String label(String key) {
        int dot = key.lastIndexOf('.');
        String label = dot < 0 ? key : key.substring(dot + 1);
        if (label.length() > LABEL_MAX) {
            label = label.substring(0, LABEL_MAX);
        }
        return label.isEmpty() ? key : label;
    }

    private static String commentOf(JsonObject comments, String key) {
        if (comments == null || !comments.has(key)) {
            return null;
        }
        JsonElement element = comments.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String text = element.getAsString();
        if (text.isEmpty()) {
            return null;
        }
        return text.codePointCount(0, text.length()) > COMMENT_MAX ? null : text;
    }

    private static List<String> options(Field field) {
        List<String> names = new ArrayList<>();
        Object[] constants = field.getType().getEnumConstants();
        if (constants == null) {
            return names;
        }
        for (Object constant : constants) {
            String name = ((Enum<?>) constant).name();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > OPTIONS_ITEM_MAX) {
                return Collections.emptyList();
            }
            names.add(name);
        }
        return names.size() > OPTIONS_MAX ? Collections.<String>emptyList() : names;
    }

    /**
     * Converts one plain value read from the file to the entry's wire value.
     *
     * @param plain   the plain value, or null when the file does not hold the key
     * @param kind    the entry kind
     * @param options the enum constant names, empty for every other kind
     * @return the wire value, or null when the value cannot be carried inside the contract's bounds
     */
    private static JsonElement valueOf(Object plain, Kind kind, List<String> options) {
        if (plain == null) {
            return JsonNull.INSTANCE;
        }
        switch (kind) {
            case STRING:
                if (!(plain instanceof String)) {
                    return null;
                }
                String text = (String) plain;
                return text.codePointCount(0, text.length()) > VALUE_TEXT_MAX ? null : new JsonPrimitive(text);
            case INTEGER:
                Long integral = integral(plain);
                return integral == null ? null : new JsonPrimitive(integral);
            case NUMBER:
                Double number = number(plain);
                return number == null ? null : new JsonPrimitive(number);
            case BOOLEAN:
                return plain instanceof Boolean ? new JsonPrimitive((Boolean) plain) : null;
            case STRING_LIST:
                return list(plain);
            case ENUM:
                return plain instanceof String && options.contains(plain) ? new JsonPrimitive((String) plain) : null;
            default:
                return null;
        }
    }

    private static Long integral(Object plain) {
        if (!(plain instanceof Number)) {
            return null;
        }
        if (plain instanceof Integer || plain instanceof Long || plain instanceof Short || plain instanceof Byte
                || plain instanceof BigInteger) {
            BigInteger value = plain instanceof BigInteger
                    ? (BigInteger) plain : BigInteger.valueOf(((Number) plain).longValue());
            if (value.bitLength() > 62) {
                return null;
            }
            long exact = value.longValue();
            return exact < -SAFE_INTEGER || exact > SAFE_INTEGER ? null : exact;
        }
        return null;
    }

    private static Double number(Object plain) {
        if (!(plain instanceof Number)) {
            return null;
        }
        double value = ((Number) plain).doubleValue();
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    private static JsonElement list(Object plain) {
        if (!(plain instanceof List)) {
            return null;
        }
        List<?> values = (List<?>) plain;
        if (values.size() > LIST_MAX) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (Object value : values) {
            if (!(value instanceof String)) {
                return null;
            }
            String item = (String) value;
            if (item.codePointCount(0, item.length()) > LIST_ITEM_MAX) {
                return null;
            }
            array.add(item);
        }
        return array;
    }

    private static JsonObject constraints(Field field, Kind kind, List<String> options) {
        if (kind == Kind.ENUM) {
            JsonArray allowed = new JsonArray();
            for (String option : options) {
                allowed.add(option);
            }
            JsonObject constraints = new JsonObject();
            constraints.add("options", allowed);
            return constraints;
        }
        Range range = field.getAnnotation(Range.class);
        if (range == null || (kind != Kind.INTEGER && kind != Kind.NUMBER)) {
            return null;
        }
        JsonObject constraints = new JsonObject();
        if (kind == Kind.INTEGER) {
            Long min = bound(range.min(), true);
            Long max = bound(range.max(), false);
            if (min != null) {
                constraints.addProperty("min", min);
            }
            if (max != null) {
                constraints.addProperty("max", max);
            }
        } else {
            if (usableNumberBound(range.min())) {
                constraints.addProperty("min", range.min());
            }
            if (usableNumberBound(range.max())) {
                constraints.addProperty("max", range.max());
            }
        }
        return constraints.size() == 0 ? null : constraints;
    }

    /** The default {@code @Range} bound is the whole double range, which means "no bound". */
    private static boolean usableNumberBound(double bound) {
        return Double.isFinite(bound) && bound != -Double.MAX_VALUE && bound != Double.MAX_VALUE;
    }

    private static Long bound(double value, boolean min) {
        if (min ? value == -Double.MAX_VALUE : value == Double.MAX_VALUE) {
            return null;
        }
        if (!Double.isFinite(value) || value != Math.rint(value) || value < -SAFE_INTEGER || value > SAFE_INTEGER) {
            return null;
        }
        return (long) value;
    }

    /**
     * Checks one panel change against its entry: the JSON value's shape for that entry type and, for a
     * bounded number, the declared range. Constraints the entity declares for a whole field (a size, a
     * pattern, the module's own converter) are checked by the entity itself, not here.
     *
     * @param value the JSON value the panel sent
     * @param entry the entry the change names, or null when the key is not an editable entry
     * @return null when the value may be written, otherwise a short reason for the key's result row
     */
    static String check(JsonElement value, Entry entry) {
        if (entry == null) {
            return "not an editable setting of this file";
        }
        switch (entry.kind) {
            case STRING:
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? null : "expected a text value";
            case INTEGER:
                return checkInteger(value, entry);
            case NUMBER:
                return checkNumber(value, entry);
            case BOOLEAN:
                return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? null : "expected true or false";
            case STRING_LIST:
                return checkList(value);
            case ENUM:
                return checkEnum(value, entry);
            default:
                return "not an editable setting";
        }
    }

    private static String checkInteger(JsonElement value, Entry entry) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return "expected a whole number";
        }
        BigDecimal decimal;
        try {
            decimal = value.getAsBigDecimal();
        } catch (NumberFormatException ex) {
            return "expected a whole number";
        }
        long number;
        try {
            number = decimal.longValueExact();
        } catch (ArithmeticException ex) {
            return "expected a whole number";
        }
        if (number < -SAFE_INTEGER || number > SAFE_INTEGER) {
            return "the number is too large to edit from the panel";
        }
        return withinRange(entry.constraints, number) ? null : rangeMessage(entry.constraints);
    }

    private static String checkNumber(JsonElement value, Entry entry) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return "expected a number";
        }
        double number = value.getAsDouble();
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            return "expected a finite number";
        }
        return withinRange(entry.constraints, number) ? null : rangeMessage(entry.constraints);
    }

    private static boolean withinRange(JsonObject constraints, double number) {
        if (constraints == null) {
            return true;
        }
        if (constraints.has("min") && number < constraints.get("min").getAsDouble()) {
            return false;
        }
        return !constraints.has("max") || number <= constraints.get("max").getAsDouble();
    }

    private static String rangeMessage(JsonObject constraints) {
        if (constraints == null) {
            return "out of range";
        }
        String min = constraints.has("min") ? constraints.get("min").getAsString() : null;
        String max = constraints.has("max") ? constraints.get("max").getAsString() : null;
        if (min != null && max != null) {
            return "must be between " + min + " and " + max;
        }
        return min != null ? "must be at least " + min : "must be at most " + max;
    }

    private static String checkList(JsonElement value) {
        if (!value.isJsonArray()) {
            return "expected a list of texts";
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > LIST_MAX) {
            return "the list may hold at most " + LIST_MAX + " items";
        }
        for (JsonElement item : array) {
            if (item == null || item.isJsonNull() || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                return "expected a list of texts";
            }
            String text = item.getAsString();
            if (text.codePointCount(0, text.length()) > LIST_ITEM_MAX) {
                return "a list item may hold at most " + LIST_ITEM_MAX + " characters";
            }
        }
        return null;
    }

    private static String checkEnum(JsonElement value, Entry entry) {
        String expected = "expected one of: " + String.join(", ", entry.options);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return expected;
        }
        return entry.options.contains(value.getAsString()) ? null : expected;
    }

    /**
     * Converts a checked JSON value to the plain value the configuration document stores.
     *
     * @param value the JSON value
     * @param kind  the entry kind
     * @return the plain value
     */
    static Object plain(JsonElement value, Kind kind) {
        switch (kind) {
            case INTEGER:
                return Long.valueOf(value.getAsLong());
            case NUMBER:
                return Double.valueOf(value.getAsDouble());
            case BOOLEAN:
                return Boolean.valueOf(value.getAsBoolean());
            case STRING_LIST:
                List<String> items = new ArrayList<>();
                for (JsonElement item : value.getAsJsonArray()) {
                    items.add(item.getAsString());
                }
                return items;
            case STRING:
            case ENUM:
            default:
                return value.getAsString();
        }
    }

    /**
     * Finds the entry a dotted key names.
     *
     * @param entries the described entries
     * @param key     the change's dotted key
     * @return the entry, or null when that key is not an editable entry of this file
     */
    static Entry find(List<Entry> entries, String key) {
        for (Entry entry : entries) {
            if (entry.key.equals(key)) {
                return entry;
            }
        }
        return null;
    }

}
