package com.ultikits.ultitools.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bean copy utility class.
 * <p>
 * Replaces hutool BeanUtil.copyProperties().
 *
 * @author wisdomme
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Copies private fields between bean instances -- see 08-GATE05-TRIAGE.md
public final class BeanCopyUtil {

    /** Primitive type to its wrapper class; see {@link #boxed(Class)}. */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER;

    static {
        Map<Class<?>, Class<?>> map = new HashMap<>();
        map.put(boolean.class, Boolean.class);
        map.put(char.class, Character.class);
        map.put(byte.class, Byte.class);
        map.put(short.class, Short.class);
        map.put(int.class, Integer.class);
        map.put(long.class, Long.class);
        map.put(float.class, Float.class);
        map.put(double.class, Double.class);
        PRIMITIVE_TO_WRAPPER = Collections.unmodifiableMap(map);
    }
    
    private BeanCopyUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Copies properties.
     *
     * @param source the source object
     * @param target the target object
     */
    public static void copyProperties(Object source, Object target) {
        copyProperties(source, target, false);
    }
    
    /**
     * Copies properties.
     *
     * @param source          the source object
     * @param target          the target object
     * @param ignoreNullValue whether to ignore null values
     */
    public static void copyProperties(Object source, Object target, boolean ignoreNullValue) {
        copyProperties(source, target, ignoreNullValue, (String[]) null);
    }
    
    /**
     * Copies properties, excluding the specified fields.
     *
     * @param source         the source object
     * @param target         the target object
     * @param ignoreFields   the field names to exclude
     */
    public static void copyProperties(Object source, Object target, String... ignoreFields) {
        copyProperties(source, target, false, ignoreFields);
    }
    
    /**
     * Copies properties.
     *
     * @param source          the source object
     * @param target          the target object
     * @param ignoreNullValue whether to ignore null values
     * @param ignoreFields    the field names to exclude
     */
    public static void copyProperties(Object source, Object target, boolean ignoreNullValue, String... ignoreFields) {
        if (source == null || target == null) {
            return;
        }

        java.util.Set<String> ignoreSet = ignoreFields == null ?
            java.util.Collections.emptySet() :
            new java.util.HashSet<>(java.util.Arrays.asList(ignoreFields));

        List<Field> sourceFields = ReflectionUtil.getAllFields(source.getClass());

        for (Field sourceField : sourceFields) {
            if (shouldSkipField(sourceField, ignoreSet)) {
                continue;
            }
            Field targetField = findWritableTargetField(target.getClass(), sourceField.getName());
            if (targetField != null) {
                copyFieldValue(source, target, sourceField, targetField, ignoreNullValue);
            }
        }
    }

    private static boolean shouldSkipField(Field field, java.util.Set<String> ignoreSet) {
        return ignoreSet.contains(field.getName()) || isStaticOrFinal(field);
    }

    private static boolean isStaticOrFinal(Field field) {
        return Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers());
    }

    private static Field findWritableTargetField(Class<?> targetClass, String fieldName) {
        Field targetField = ReflectionUtil.getField(targetClass, fieldName);
        if (targetField == null || isStaticOrFinal(targetField)) {
            return null;
        }
        return targetField;
    }

    private static void copyFieldValue(Object source, Object target, Field sourceField, Field targetField, boolean ignoreNullValue) {
        try {
            sourceField.setAccessible(true);
            targetField.setAccessible(true);

            Object value = sourceField.get(source);

            if (ignoreNullValue && value == null) {
                return;
            }

            if (value != null && !boxed(targetField.getType()).isAssignableFrom(value.getClass())) {
                value = convertValue(value, targetField.getType());
                if (value == null) {
                    return;
                }
            }

            targetField.set(target, value);
        } catch (IllegalAccessException e) {
            // Ignore a field that cannot be accessed.
        }
    }
    
    /**
     * Copies to a new instance.
     *
     * @param source      the source object
     * @param targetClass the target class
     * @param <T>         the target type
     * @return the new instance
     */
    public static <T> T copyToNewInstance(Object source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }
        
        T target = ReflectionUtil.newInstance(targetClass);
        copyProperties(source, target);
        return target;
    }
    
    /**
     * Converts a basic type.
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (boxed(targetType).isAssignableFrom(value.getClass())) {
            return value;
        }

        if (targetType == String.class) {
            return value.toString();
        }

        if (value instanceof Number) {
            return convertNumber((Number) value, targetType);
        }

        return null;
    }

    /**
     * Returns the wrapper class of a primitive type, or the type itself otherwise.
     * <p>
     * {@link Field#get} always boxes, so a {@code boolean} field reads as a {@code Boolean} and
     * {@code boolean.class.isAssignableFrom(Boolean.class)} is {@code false}. Deciding
     * assignability against the primitive type sent every {@code boolean} and {@code char} value
     * to {@link #convertValue}, which had no branch for either and dropped the write (#520). The
     * numeric primitives only survived because {@link #convertNumber} happens to cover them.
     */
    private static Class<?> boxed(Class<?> type) {
        Class<?> wrapper = PRIMITIVE_TO_WRAPPER.get(type);
        return wrapper != null ? wrapper : type;
    }

    private static Object convertNumber(Number num, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            return num.intValue();
        }
        if (targetType == Long.class || targetType == long.class) {
            return num.longValue();
        }
        if (targetType == Double.class || targetType == double.class) {
            return num.doubleValue();
        }
        if (targetType == Float.class || targetType == float.class) {
            return num.floatValue();
        }
        if (targetType == Short.class || targetType == short.class) {
            return num.shortValue();
        }
        if (targetType == Byte.class || targetType == byte.class) {
            return num.byteValue();
        }
        return null;
    }
}
