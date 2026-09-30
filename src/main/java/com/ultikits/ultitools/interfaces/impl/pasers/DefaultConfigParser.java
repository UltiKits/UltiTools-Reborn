package com.ultikits.ultitools.interfaces.impl.pasers;

import com.ultikits.ultitools.utils.BasicTypeUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Config binder serializes private fields to YAML -- see 08-GATE05-TRIAGE.md
public class DefaultConfigParser extends ConfigParser<Object> implements DottedMapKeyRefusal {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(DefaultConfigParser.class.getName());


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
        DottedMapKeyRefusal.Context context = DottedMapKeyRefusal.Context.current();
        String path = context.path;
        java.util.function.BiConsumer<String, String> refused = context.refused;
        if (object instanceof Map) {
            MemorySection mapSection = new MemoryConfiguration();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                String name = keyName(entry.getKey());
                // #553: the one refusal point - this map becomes a section, where '.' is the separator.
                if (DottedMapKeyRefusal.storable(name, path, refused, LOGGER)) {
                    mapSection.set(name, fileForm(entry.getValue(), DottedMapKeyRefusal.child(path, name), refused));
                }
            }
            return mapSection;
        }
        MemorySection memorySection = new MemoryConfiguration();
        if (object instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) object) {
                memorySection.set(String.valueOf(index++), plainForm(element));
            }
            return memorySection;
        }
        for (Map.Entry<String, Object> field : fieldsOf(object).entrySet()) {
            memorySection.set(field.getKey(),
                    fileForm(field.getValue(), DottedMapKeyRefusal.child(path, field.getKey()), refused));
        }
        return memorySection;
    }

    /**
     * The form in which the framework writes a value this parser binds (#523), with no warning sink:
     * a refused dotted key is reported to this class's logger. See {@link #fileForm(Object, String,
     * java.util.function.BiConsumer)}.
     * <p>
     * Framework-internal: {@code public} only because the configuration entity lives in another
     * package. Module code should not call it.
     *
     * @param value the value, possibly {@code null}
     * @return the value to put into the configuration
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Object fileForm(Object value) {
        return fileForm(value, "", null);
    }

    /**
     * The form in which the framework writes a value this parser binds (#523): an enum constant by its
     * name, a {@code UUID} as its text, a {@code ConfigurationSerializable} whose class is registered
     * with Bukkit (a {@code Location}, an {@code ItemStack}) unchanged - Bukkit's own writer and loader
     * handle it - a collection (a {@code List} or a {@code Set}) as a YAML list of {@linkplain #plainForm
     * plain} elements, and a map or any other object through {@link #serializeToMemorySection(Object)},
     * where a dotted map key is refused (#553) and reported to {@code refused} with the map's nested
     * path. SnakeYAML would otherwise tag an enum or a {@code UUID} with its Java class, and write a
     * {@code Set} as a tagged mapping, which the loader refuses; an unregistered {@code
     * ConfigurationSerializable} is written as its fields, as before 6.3.0, because its {@code ==} class
     * tag would make the file unloadable.
     * <p>
     * Framework-internal: {@code public} only because the configuration entity lives in another
     * package. Module code should not call it.
     *
     * @param value   the value, possibly {@code null}
     * @param path    the entry's path
     * @param refused receives (nested path, refused key), or {@code null} for this class's logger
     * @return the value to put into the configuration
     * @since 6.3.0
     */
    @Override
    @ApiStatus.Internal
    public Object fileForm(Object value, String path, java.util.function.BiConsumer<String, String> refused) {
        if (value == null || value instanceof String || BasicTypeUtil.isBasicType(value)) {
            return value;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof java.util.UUID) {
            return value.toString();
        }
        if (isRegisteredSerializable(value)) {
            return value;
        }
        if (value instanceof Collection) {
            return plainForm(value);
        }
        return DottedMapKeyRefusal.Context.with(path, refused, () -> serialize(value));
    }

    /**
     * The form of a value inside a list: plain YAML data, never a configuration section - a list
     * element that is a section reads back as an empty {@code getMapList} entry. Bukkit keeps a list
     * element's map whole, so a dotted key is kept too (#553 applies to sections only). An enum becomes
     * its name, a {@code UUID} its text, a registered {@code ConfigurationSerializable} stays for Bukkit,
     * a collection a list, a map or a section a map with plain values, and any other object the plain
     * map of its fields.
     *
     * @param value the value, possibly {@code null}
     * @return the plain form
     */
    private Object plainForm(Object value) {
        if (value == null || value instanceof String || BasicTypeUtil.isBasicType(value)) {
            return value;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof java.util.UUID) {
            return value.toString();
        }
        if (isRegisteredSerializable(value)) {
            return value;
        }
        if (value instanceof Collection) {
            List<Object> list = new ArrayList<>();
            for (Object element : (Collection<?>) value) {
                list.add(plainForm(element));
            }
            return list;
        }
        Map<?, ?> entries = value instanceof Map ? (Map<?, ?>) value
                : value instanceof ConfigurationSection ? ((ConfigurationSection) value).getValues(false)
                : fieldsOf(value);
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            map.put(keyName(entry.getKey()), plainForm(entry.getValue()));
        }
        return map;
    }

    /** A map key as written: an enum by its name (the form the binder reads back), anything else as text. */
    private static String keyName(Object key) {
        return key instanceof Enum ? ((Enum<?>) key).name() : String.valueOf(key);
    }

    /**
     * The persistable fields of an object, by name, in declaration order: not {@code static}, not
     * {@code transient}, not synthetic.
     */
    private static Map<String, Object> fieldsOf(Object object) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Field field : ReflectionUtil.getFields(object.getClass())) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            fields.put(field.getName(), ReflectionUtil.getFieldValue(object, field));
        }
        return fields;
    }

    /**
     * Whether Bukkit itself can write and read back {@code value}: a {@code ConfigurationSerializable}
     * whose class is registered under the alias it is written with. Looked up on every call, because a
     * module may register its class after an earlier write.
     */
    private static boolean isRegisteredSerializable(Object value) {
        if (!(value instanceof ConfigurationSerializable)) {
            return false;
        }
        Class<? extends ConfigurationSerializable> type = ((ConfigurationSerializable) value).getClass();
        return ConfigurationSerialization.getClassByAlias(ConfigurationSerialization.getAlias(type)) == type;
    }

    /**
     * Replaces every configuration section in {@code value} by an insertion-ordered map of its plain
     * values - the form of a map inside a list, and of a map the configuration entity places into a
     * file as data, as 6.2 did for a first-boot map default.
     * <p>
     * Framework-internal: {@code public} only because the configuration entity lives in another
     * package.
     *
     * @param value a value, possibly a section
     * @return the value with every section replaced by a map
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static Object plainData(Object value) {
        if (!(value instanceof ConfigurationSection)) {
            return value;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((ConfigurationSection) value).getValues(false).entrySet()) {
            map.put(entry.getKey(), plainData(entry.getValue()));
        }
        return map;
    }
}
