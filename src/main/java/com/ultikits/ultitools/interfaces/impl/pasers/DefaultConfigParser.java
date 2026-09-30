package com.ultikits.ultitools.interfaces.impl.pasers;

import com.ultikits.ultitools.utils.BasicTypeUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Config binder serializes private fields to YAML -- see 08-GATE05-TRIAGE.md
public class DefaultConfigParser extends ConfigParser<Object> {

    /**
     * While {@link #fileForm(Object, List)} runs: receives the nested path of each {@code null} value
     * left out of a section. {@code null} otherwise, so an instance a module shares keeps no state.
     */
    private List<String> leftOutNulls;

    /** While {@link #fileForm(Object, List)} runs: the keys from the value's root to the current section. */
    private Deque<String> path;

    /**
     * Turns a raw YAML value into plain Java values: a section becomes a {@link LinkedHashMap}
     * (nested sections recursively), a sequence a {@link List}, and a scalar is returned as it is.
     * <p>
     * Since 6.3.0 (#523) a sequence keeps each element's own value - an {@code Integer} stays an
     * {@code Integer} - instead of being turned into its text. The configuration binder converts the
     * elements to the field's declared element type; before, a {@code List<Integer>} field received
     * {@code String}s and every typed lookup on it silently failed.
     *
     * @param object the value the configuration returned for an entry
     * @return the parsed value
     */
    @Override
    public Object parse(Object object) {
        if (object instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object o : (List<?>) object) {
                list.add(o instanceof ConfigurationSection ? parse(o) : o);
            }
            return list;
        } else if (BasicTypeUtil.isBasicType(object) || object instanceof String
                || !(object instanceof ConfigurationSection)) {
            // A scalar, or a value that is neither a sequence nor a section (a YAML timestamp read as a
            // Date, a map stored as data): returned as it is, for the binder to accept or report.
            return object;
        } else {
            Map<String, Object> map = new LinkedHashMap<>();
            ConfigurationSection section = (ConfigurationSection) object;
            Set<String> keys = section.getKeys(false);
            for (String key : keys) {
                Object value = section.get(key);
                if (value instanceof ConfigurationSection) {
                    value = parse(value);
                }
                map.put(key, value);
            }
            return map;
        }
    }

    /**
     * Serializes {@code object} into a {@link MemorySection}.
     * <p>
     * {@link Map} and {@link Collection} values are walked by their own entries/elements
     * rather than reflected on: a {@code Map}/{@code Collection} instance's own fields are a
     * JDK implementation detail (e.g. {@code LinkedHashMap}'s {@code table}/{@code head}/
     * {@code tail}/{@code modCount}), and reflecting on them is wrong even where {@code
     * setAccessible} succeeds - it would persist the container's internal bookkeeping instead
     * of its actual contents. On Java 16+ it additionally throws {@link
     * java.lang.reflect.InaccessibleObjectException} the first time it tries to open a
     * private {@code java.util} field (e.g. {@code LinkedHashMap#serialVersionUID}), because
     * {@code java.base} does not open {@code java.util} to an unnamed module.
     * <p>
     * Everything else falls back to the pre-existing reflective walk of the object's own
     * fields, skipping {@code static}, {@code transient}, and synthetic fields: {@code
     * static} fields (like the JDK's own {@code serialVersionUID}) are never per-instance
     * state, {@code transient} fields are the field author's own "do not persist this"
     * signal, and synthetic fields are compiler-generated bookkeeping (e.g. outer-class
     * references) with no meaningful config representation.
     *
     * @param object the object to serialize
     * @return the populated {@link MemorySection}
     */
    @Override
    public MemorySection serializeToMemorySection(Object object) {
        MemorySection memorySection = new MemoryConfiguration();
        if (object instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                put(memorySection, String.valueOf(keyForm(entry.getKey())), entry.getValue());
            }
            return memorySection;
        }
        if (object instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) object) {
                put(memorySection, String.valueOf(index++), element);
            }
            return memorySection;
        }
        for (Field field : ReflectionUtil.getFields(object.getClass())) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            Object fieldValue = ReflectionUtil.getFieldValue(object, field);
            put(memorySection, field.getName(), fieldValue);
        }
        return memorySection;
    }

    /**
     * Sets {@code key} in {@code section} to the file form of {@code value}. A {@code null} value is
     * left out - the section never holds it, as {@code MemorySection#set(key, null)} would leave it -
     * and, while {@link #fileForm(Object, List)} runs, its nested path is recorded. (6.2 stopped here
     * with a {@code NullPointerException}.)
     */
    private void put(MemorySection section, String key, Object value) {
        if (value == null) {
            if (leftOutNulls != null) {
                leftOutNulls.add(path.isEmpty() ? key : String.join(" -> ", path) + " -> " + key);
            }
            return;
        }
        if (leftOutNulls == null) {
            section.set(key, fileForm(value));
            return;
        }
        path.addLast(key);
        try {
            section.set(key, fileForm(value));
        } finally {
            path.removeLast();
        }
    }

    /**
     * The form in which the framework writes a value this parser binds (#523): an enum constant by
     * its name, a collection (a {@code List} or a {@code Set}) as a YAML list of {@linkplain #plainForm
     * plain} elements, and everything else through {@link #serialize(Object)} exactly as 6.2 writes it.
     * SnakeYAML would otherwise tag an enum with its Java class and write a {@code Set} as a tagged
     * mapping, both of which the configuration loader refuses, and the binder could not read a typed
     * collection back.
     * <p>
     * Framework-internal: {@code public} only because the configuration entity that calls it lives in
     * another package. Module code should not call it.
     *
     * @param value the value, possibly {@code null}
     * @return the value to put into the configuration
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Object fileForm(Object value) {
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Collection) {
            return plainForm(value);
        }
        return value == null ? null : serialize(value);
    }

    /**
     * {@link #fileForm(Object)}, recording in {@code leftOutNulls} the nested path of each {@code null}
     * value inside a map or an object that is left out of the file ({@code key -> key}, relative to
     * {@code value}), so that the caller can name it.
     * <p>
     * Framework-internal, like {@link #fileForm(Object)}.
     *
     * @param value        the value, possibly {@code null}
     * @param leftOutNulls receives the paths, or {@code null} to record nothing
     * @return the value to put into the configuration
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Object fileForm(Object value, List<String> leftOutNulls) {
        if (leftOutNulls == null) {
            return fileForm(value);
        }
        List<String> previousNulls = this.leftOutNulls;
        Deque<String> previousPath = this.path;
        this.leftOutNulls = leftOutNulls;
        this.path = new ArrayDeque<>();
        try {
            return fileForm(value);
        } finally {
            this.leftOutNulls = previousNulls;
            this.path = previousPath;
        }
    }

    /**
     * The form of a value inside a list, where 6.2 put every element into the file as it was: an enum
     * becomes its name and a collection a list (#523), a map a map of such values whose enum keys become
     * their names and whose other keys stay as they are - a map inside a list is plain data the file
     * keeps whole, dotted keys included - and every other element is left as it is, exactly as 6.2 left
     * it (a Bukkit {@code ConfigurationSerializable} such as an item, a section, any other object).
     *
     * @param value the value, possibly {@code null}
     * @return the plain form
     */
    private Object plainForm(Object value) {
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Collection) {
            List<Object> list = new ArrayList<>();
            for (Object element : (Collection<?>) value) {
                list.add(plainForm(element));
            }
            return list;
        }
        if (value instanceof Map) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                map.put(keyForm(entry.getKey()), plainForm(entry.getValue()));
            }
            return map;
        }
        return value;
    }

    /**
     * The one rule for a map key the framework writes (#523): an enum constant by its name, the form the
     * binder reads back ({@code Enum#toString} may differ); any other key stays the object it is. A
     * section key is its text ({@code String.valueOf}); a map inside a list keeps the key itself, so an
     * integer key is written as the integer 6.2 wrote ({@code 1: a}).
     *
     * @param key the key
     * @return the key as written
     */
    private static Object keyForm(Object key) {
        return key instanceof Enum ? ((Enum<?>) key).name() : key;
    }
}
