package com.ultikits.ultitools.interfaces.impl.pasers;

import com.ultikits.ultitools.utils.BasicTypeUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Config binder serializes private fields to YAML -- see 08-GATE05-TRIAGE.md
public class DefaultConfigParser extends ConfigParser<Object> {

    /**
     * Path separator of the section a {@link Map} is serialized into (#553). A map key is data, not a
     * path, so the section must not split it: with the default {@code '.'} a key {@code my.rule} was
     * written as {@code my: {rule: ...}} and read back as {@code my}. NUL cannot appear in a YAML key
     * an operator writes, so no real key is ever split by it.
     */
    private static final char MAP_KEY_SEPARATOR = '\u0000';

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
     * A map's keys are written as they are, never split into a path (#553): {@code my.rule} stays one
     * key {@code my.rule}, where the default path separator would have written {@code my: {rule: ...}}.
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
        if (object instanceof Map) {
            MemoryConfiguration mapSection = new MemoryConfiguration();
            mapSection.options().pathSeparator(MAP_KEY_SEPARATOR);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                Object key = entry.getKey();
                // An enum key by its name, the form the binder reads back (Enum#toString may differ).
                String name = key instanceof Enum ? ((Enum<?>) key).name() : String.valueOf(key);
                mapSection.set(checkWritableKey(name), fileForm(entry.getValue()));
            }
            return mapSection;
        }
        MemorySection memorySection = new MemoryConfiguration();
        if (object instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) object) {
                memorySection.set(String.valueOf(index++), fileForm(element));
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
            memorySection.set(field.getName(), fileForm(fieldValue));
        }
        return memorySection;
    }

    /**
     * Refuses a map key the configuration loader cannot read back (#553). A key is written whole, but
     * the loader still reads a {@code '.'} in a key as a path separator, so a key with an empty segment
     * - empty, or starting or ending with a dot, or holding two dots in a row - would make the whole
     * file unreadable on the next start. Refusing it here keeps the file as it was, as the save did
     * before 6.3.0 for such a key.
     *
     * @param key the map key
     * @return {@code key}
     * @throws IllegalArgumentException naming the key, if it has an empty path segment
     */
    static String checkWritableKey(String key) {
        if (key.isEmpty() || key.startsWith(".") || key.endsWith(".") || key.contains("..")) {
            throw new IllegalArgumentException("Cannot write map key '" + key + "': the configuration loader"
                    + " reads a '.' in a key as a path separator, and this key has an empty segment");
        }
        return key;
    }

    /**
     * The form a nested value is written in: an enum constant by its name and a collection as a list
     * of such forms (#523) - SnakeYAML would otherwise tag an enum with its Java class, which the
     * loader refuses - and everything else through {@link #serialize(Object)}.
     *
     * @param value a nested value, possibly {@code null}
     * @return the value to put into the section
     */
    private Object fileForm(Object value) {
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Collection) {
            List<Object> list = new ArrayList<>();
            for (Object element : (Collection<?>) value) {
                list.add(fileForm(element));
            }
            return list;
        }
        return value == null ? null : serialize(value);
    }
}
