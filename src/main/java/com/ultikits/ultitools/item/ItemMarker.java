package com.ultikits.ultitools.item;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A persistent-data value written onto the rendered stack ({@code ItemStack#editPersistentDataContainer}).
 *
 * <p>Deeply immutable and value-equal: an array value (of any component type) is copied in the
 * constructor and on every {@link #value()} call; a {@code List} value is copied with
 * {@code List.copyOf} after its elements are frozen the same way. Equality and hash code compare array
 * contents deeply and lists element by element. A {@code PersistentDataContainer} value, an array of
 * them, or a list containing one is rejected, because a container is mutable and has no value
 * equality; store a key or a byte array instead. A value of a module-defined complex type must itself
 * be immutable with value {@code equals}; otherwise equal specs compare unequal and a GUI re-renders.
 * @param <P> primitive storage type
 * @param <C> complex value type
 * @param key persistent-data key
 * @param type value codec
 * @param value value to store
 */
public record ItemMarker<P, C>(NamespacedKey key, PersistentDataType<P, C> type, C value) {
    /**
     * Rejects nulls and container values, and freezes supported aggregate values.
     * @throws IllegalArgumentException for a container value
     */
    public ItemMarker {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        value = copy(Objects.requireNonNull(value, "value"));
    }

    /** Returns the value, defensively copying arrays and aggregate elements on every call. */
    @Override
    public C value() {
        return copy(value);
    }

    /** Compares array contents deeply and list elements by value. */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ItemMarker<?, ?> marker
                && key.equals(marker.key) && type.equals(marker.type) && equalValues(value, marker.value);
    }

    /** Returns a content-based hash matching {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        return Objects.hash(key, type, valueHash(value));
    }

    /** Returns record-style diagnostics with array contents rather than array identities. */
    @Override
    public String toString() {
        return "ItemMarker[key=" + key + ", type=" + type + ", value=" + valueText(value) + ']';
    }

    @SuppressWarnings("unchecked")
    private static <T> T copy(T value) {
        // Array component types are preserved; lists are frozen as List values.
        return (T) freeze(value);
    }

    private static Object freeze(Object value) {
        if (value instanceof PersistentDataContainer) {
            throw new IllegalArgumentException("PersistentDataContainer marker values are not immutable");
        }
        if (value instanceof List<?> list) {
            return freezeList(list);
        }
        if (value == null || !value.getClass().isArray()) {
            return value;
        }
        return freezeArray(value);
    }

    private static List<Object> freezeList(List<?> list) {
        List<Object> elements = new ArrayList<>(list.size());
        for (Object element : list) {
            elements.add(freeze(element));
        }
        return List.copyOf(elements);
    }

    private static Object freezeArray(Object value) {
        Class<?> component = value.getClass().getComponentType();
        Class<?> leaf = component;
        while (leaf.isArray()) {
            leaf = leaf.getComponentType();
        }
        if (PersistentDataContainer.class.isAssignableFrom(leaf)) {
            throw new IllegalArgumentException("PersistentDataContainer marker arrays are not immutable");
        }
        int length = Array.getLength(value);
        Object frozen = Array.newInstance(component, length);
        if (component.isPrimitive()) {
            System.arraycopy(value, 0, frozen, 0, length);
        } else {
            for (int index = 0; index < length; index++) {
                Array.set(frozen, index, freeze(Array.get(value, index)));
            }
        }
        return frozen;
    }

    private static boolean equalValues(Object first, Object second) {
        if (first == second) { // NOPMD - identity shortcut before the element-wise comparison
            return true;
        }
        if (first instanceof List<?> left && second instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!equalValues(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof Object[] left && second instanceof Object[] right) {
            if (left.length != right.length) {
                return false;
            }
            for (int index = 0; index < left.length; index++) {
                if (!equalValues(left[index], right[index])) {
                    return false;
                }
            }
            return true;
        }
        return Objects.deepEquals(first, second);
    }

    private static int valueHash(Object value) {
        int hash = 1;
        if (value instanceof List<?> list) {
            for (Object element : list) {
                hash = 31 * hash + valueHash(element);
            }
            return hash;
        }
        if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                hash = 31 * hash + valueHash(Array.get(value, index));
            }
            return hash;
        }
        return Objects.hashCode(value);
    }

    private static String valueText(Object value) {
        if (value instanceof List<?> list) {
            StringJoiner text = new StringJoiner(", ", "[", "]");
            for (Object element : list) {
                text.add(valueText(element));
            }
            return text.toString();
        }
        if (value != null && value.getClass().isArray()) {
            Object[] elements = new Object[Array.getLength(value)];
            for (int index = 0; index < elements.length; index++) {
                elements[index] = valueText(Array.get(value, index));
            }
            return Arrays.deepToString(elements);
        }
        return String.valueOf(value);
    }
}
