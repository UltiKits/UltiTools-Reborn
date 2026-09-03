package com.ultikits.ultitools.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON path utility class - replaces hutool-json's putByPath/getByPath functionality
 * <p>
 * Provides dot-separated path access and mutation of values inside a nested JSON Map.
 * </p>
 *
 * <pre>
 * Usage example:
 * Map&lt;String, Object&gt; json = new LinkedHashMap&lt;&gt;();
 * JsonPathUtil.putByPath(json, "a.b.c", "value");
 * Object value = JsonPathUtil.getByPath(json, "a.b.c"); // "value"
 * </pre>
 *
 * @author UltiKits Team
 * @since 6.2.0
 */
public final class JsonPathUtil {

    private JsonPathUtil() {
        // Utility class must not be instantiated
    }

    /**
     * Gets a value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path, e.g. "a.b.c"
     * @return the value at the path, or {@code null} if the path does not exist or an
     *         intermediate node is not a Map
     */
    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> json, String path) {
        if (json == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] keys = path.split("\\.");
        Object current = json;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Gets a String value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the String value, or {@code null} if the value is absent
     */
    public static String getStr(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        return value != null ? value.toString() : null;
    }

    /**
     * Gets an Integer value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the Integer value, or {@code null} if the value is absent or cannot be converted
     */
    public static Integer getInt(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets a Long value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the Long value, or {@code null} if the value is absent or cannot be converted
     */
    public static Long getLong(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gets a Boolean value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the Boolean value, or {@code null} if the value is absent
     */
    public static Boolean getBool(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(value.toString());
    }

    /**
     * Gets a Double value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the Double value, or {@code null} if the value is absent or cannot be converted
     */
    public static Double getDouble(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets a value in a nested Map by path, automatically creating intermediate levels.
     *
     * @param json  the JSON Map object
     * @param path  the dot-separated path, e.g. "a.b.c"
     * @param value the value to set
     */
    @SuppressWarnings("unchecked")
    public static void putByPath(Map<String, Object> json, String path, Object value) {
        if (json == null || path == null || path.isEmpty()) {
            return;
        }
        String[] keys = path.split("\\.");
        Map<String, Object> current = json;
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(key, next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(keys[keys.length - 1], value);
    }

    /**
     * Removes a value in a nested Map by path.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return the removed value, or {@code null} if the path does not exist
     */
    @SuppressWarnings("unchecked")
    public static Object removeByPath(Map<String, Object> json, String path) {
        if (json == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] keys = path.split("\\.");
        Map<String, Object> current = json;
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) next;
        }
        return current.remove(keys[keys.length - 1]);
    }

    /**
     * Checks whether a path exists.
     *
     * @param json the JSON Map object
     * @param path the dot-separated path
     * @return {@code true} if the path exists and its value is not {@code null}
     */
    public static boolean containsPath(Map<String, Object> json, String path) {
        return getByPath(json, path) != null;
    }
}
