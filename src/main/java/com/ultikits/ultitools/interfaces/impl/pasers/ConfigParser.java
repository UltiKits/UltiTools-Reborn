package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.ultikits.ultitools.interfaces.ObjectConfigSerializer;
import com.ultikits.ultitools.interfaces.Parser;
import com.ultikits.ultitools.utils.BasicTypeUtil;

public abstract class ConfigParser<T> implements Parser<T>, ObjectConfigSerializer<T> {

    /**
     * Converts a field value into the form written to the configuration file. A basic value and a
     * {@code String} are written as they are; an enum constant as its name; a {@link Collection} (a
     * {@code List} or a {@code Set}) as a list whose enum elements are written by name - the shapes
     * the binder reads back (#523; SnakeYAML would otherwise tag an enum with its Java class, which
     * the configuration loader then refuses, making the whole file unreadable); everything else
     * goes through {@link #serializeToMemorySection(Object)}. A list with no enum element is
     * returned as the same instance.
     *
     * @param object the field value
     * @return the value to put into the configuration
     */
    @Override
    public final Object serialize(T object) {
        if (BasicTypeUtil.isBasicType(object) || object instanceof String) {
            return object;
        } else if (object instanceof Enum) {
            return ((Enum<?>) object).name();
        } else if (object instanceof Collection) {
            return listForm((Collection<?>) object);
        } else {
            return serializeToMemorySection(object);
        }
    }

    private static Object listForm(Collection<?> collection) {
        boolean hasEnum = false;
        for (Object element : collection) {
            if (element instanceof Enum) {
                hasEnum = true;
                break;
            }
        }
        if (!hasEnum && collection instanceof List) {
            return collection;
        }
        List<Object> list = new ArrayList<>(collection.size());
        for (Object element : collection) {
            list.add(element instanceof Enum ? ((Enum<?>) element).name() : element);
        }
        return list;
    }
}
