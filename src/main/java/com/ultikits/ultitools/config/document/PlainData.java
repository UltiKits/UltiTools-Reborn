package com.ultikits.ultitools.config.document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

/**
 * The plain-data boundary of the config storage layer.
 * <p>
 * Plain data is exactly: {@code null}, {@link String}, {@link Boolean}, {@link Integer}, {@link Long},
 * {@link BigInteger}, {@link Double}, a {@link List} of plain data, and a {@link Map} whose keys are
 * {@link String}s and whose values are plain data. Nothing else may reach a config file: a value outside
 * this set is refused before any YAML node is built, so no Java-class tag can ever be written.
 * <p>
 * This class also defines when two plain values are "the same value" for the purpose of leaving a file's
 * text untouched: integral numbers compare by numeric value whatever their box type ({@code Integer 1}
 * equals {@code Long 1}), doubles compare with {@link Double#equals(Object)} (so {@code 1.50} read from a
 * file equals {@code 1.5}), maps compare entry by entry regardless of order, and lists element by element.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class PlainData {

    private PlainData() {
    }

    /**
     * Returns whether {@code value} is plain data, recursively.
     *
     * @param value the value to test
     * @return {@code true} when every node of {@code value} is plain data
     */
    public static boolean isPlain(Object value) {
        return firstViolation(new ArrayList<>(), value, newVisited()) == null;
    }

    /**
     * Refuses a value that is not plain data.
     *
     * @param path  the key path the value is about to be stored at, used in the message
     * @param value the value to check
     * @throws IllegalArgumentException naming the key path of the first offending node and its class
     */
    public static void requirePlain(List<String> path, Object value) {
        String violation = firstViolation(new ArrayList<>(path), value, newVisited());
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
    }

    /**
     * Formats a key path for messages: the keys in brackets, separated by a comma, so a key that itself
     * contains a {@code .} stays readable ({@code [emojis, o.O]}).
     *
     * @param path the key path
     * @return the formatted path
     */
    public static String describePath(List<String> path) {
        return path.toString();
    }

    /**
     * Compares two plain values as values, not as text. See the class description for the rules.
     *
     * @param a one plain value
     * @param b another plain value
     * @return whether writing {@code b} over {@code a} would change nothing a reader can observe
     */
    public static boolean plainEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (isIntegral(a) && isIntegral(b)) {
            return toBigInteger(a).equals(toBigInteger(b));
        }
        if (a instanceof Double && b instanceof Double) {
            return a.equals(b);
        }
        if (a instanceof List && b instanceof List) {
            List<?> left = (List<?>) a;
            List<?> right = (List<?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<?> l = left.iterator();
            Iterator<?> r = right.iterator();
            while (l.hasNext()) {
                if (!plainEquals(l.next(), r.next())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> left = (Map<?, ?>) a;
            Map<?, ?> right = (Map<?, ?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey()) || !plainEquals(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /**
     * Deep-copies a plain value into fresh {@link LinkedHashMap}s and {@link ArrayList}s, so the caller can
     * neither change the copy's source nor share one container between two places of a document.
     * Leaves that are not containers are returned as they are (they are immutable).
     *
     * @param value a plain value (or a value read from a file, which may hold non-plain leaves such as a
     *              {@code java.util.Date} for a YAML timestamp)
     * @return the copy
     */
    public static Object copy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), copy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object element : (List<?>) value) {
                result.add(copy(element));
            }
            return result;
        }
        return value;
    }

    /**
     * The number of nested collections in a plain value: 0 for a scalar, 1 for a flat list or map.
     *
     * @param value a plain value (checked by {@link #requirePlain} first, so it has no cycle)
     * @return the nesting depth
     */
    static int depth(Object value) {
        int deepest = 0;
        if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) {
                deepest = Math.max(deepest, depth(child));
            }
            return deepest + 1;
        }
        if (value instanceof List) {
            for (Object child : (List<?>) value) {
                deepest = Math.max(deepest, depth(child));
            }
            return deepest + 1;
        }
        return 0;
    }

    static boolean isIntegral(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigInteger;
    }

    private static BigInteger toBigInteger(Object value) {
        return value instanceof BigInteger ? (BigInteger) value : BigInteger.valueOf(((Number) value).longValue());
    }

    private static Set<Object> newVisited() {
        return Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    }

    private static String firstViolation(List<String> path, Object value, Set<Object> visiting) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof Double) {
            return null;
        }
        if (value instanceof List || value instanceof Map) {
            if (!visiting.add(value)) {
                return "Config value at key path " + describePath(path) + " contains itself; only plain data"
                        + " without cycles can be written to a config file";
            }
            try {
                return containerViolation(path, value, visiting);
            } finally {
                visiting.remove(value);
            }
        }
        return "Config value at key path " + describePath(path) + " is a " + value.getClass().getName()
                + "; only plain data (null, String, Boolean, Integer, Long, BigInteger, Double, List, and Map"
                + " with String keys) can be written to a config file";
    }

    private static String containerViolation(List<String> path, Object value, Set<Object> visiting) {
        if (value instanceof List) {
            int index = 0;
            for (Object element : (List<?>) value) {
                path.add("[" + index + "]");
                String violation = firstViolation(path, element, visiting);
                path.remove(path.size() - 1);
                if (violation != null) {
                    return violation;
                }
                index++;
            }
            return null;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String)) {
                return "Config map at key path " + describePath(path) + " has a key of type "
                        + (key == null ? "null" : key.getClass().getName())
                        + "; only String keys can be written to a config file";
            }
            path.add((String) key);
            String violation = firstViolation(path, entry.getValue(), visiting);
            path.remove(path.size() - 1);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }
}
