package com.ultikits.ultitools.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Bean 复制工具类
 * <p>
 * 替代 hutool BeanUtil.copyProperties()
 * 
 * @author wisdomme
 * @since 7.0.0
 */
public final class BeanCopyUtil {
    
    private BeanCopyUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 复制属性
     *
     * @param source 源对象
     * @param target 目标对象
     */
    public static void copyProperties(Object source, Object target) {
        copyProperties(source, target, false);
    }
    
    /**
     * 复制属性
     *
     * @param source          源对象
     * @param target          目标对象
     * @param ignoreNullValue 是否忽略 null 值
     */
    public static void copyProperties(Object source, Object target, boolean ignoreNullValue) {
        copyProperties(source, target, ignoreNullValue, (String[]) null);
    }
    
    /**
     * 复制属性，排除指定字段
     *
     * @param source         源对象
     * @param target         目标对象
     * @param ignoreFields   要排除的字段名
     */
    public static void copyProperties(Object source, Object target, String... ignoreFields) {
        copyProperties(source, target, false, ignoreFields);
    }
    
    /**
     * 复制属性
     *
     * @param source          源对象
     * @param target          目标对象
     * @param ignoreNullValue 是否忽略 null 值
     * @param ignoreFields    要排除的字段名
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

            if (value != null && !targetField.getType().isAssignableFrom(value.getClass())) {
                value = convertValue(value, targetField.getType());
                if (value == null) {
                    return;
                }
            }

            targetField.set(target, value);
        } catch (IllegalAccessException e) {
            // 忽略无法访问的字段
        }
    }
    
    /**
     * 复制到新实例
     *
     * @param source      源对象
     * @param targetClass 目标类
     * @param <T>         目标类型
     * @return 新实例
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
     * 基本类型转换
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isAssignableFrom(value.getClass())) {
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
