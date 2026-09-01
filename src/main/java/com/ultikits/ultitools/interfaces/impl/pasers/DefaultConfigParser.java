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

public class DefaultConfigParser extends ConfigParser<Object> {

    @Override
    public Object parse(Object object) {
        if (object instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) object) {
                list.add(o.toString());
            }
            return list;
        } else if (BasicTypeUtil.isBasicType(object) || object instanceof String) {
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
     * @param object the object to serialize <br> 要序列化的对象
     * @return the populated {@link MemorySection} <br> 填充好的 {@link MemorySection}
     */
    @Override
    public MemorySection serializeToMemorySection(Object object) {
        MemorySection memorySection = new MemoryConfiguration();
        if (object instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                memorySection.set(String.valueOf(entry.getKey()), serialize(entry.getValue()));
            }
            return memorySection;
        }
        if (object instanceof Collection) {
            int index = 0;
            for (Object element : (Collection<?>) object) {
                memorySection.set(String.valueOf(index++), serialize(element));
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
            memorySection.set(field.getName(), serialize(fieldValue));
        }
        return memorySection;
    }
}
