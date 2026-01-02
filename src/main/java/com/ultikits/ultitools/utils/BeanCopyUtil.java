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
            // 跳过要排除的字段
            if (ignoreSet.contains(sourceField.getName())) {
                continue;
            }
            
            // 跳过 static 和 final 字段
            if (Modifier.isStatic(sourceField.getModifiers()) || 
                Modifier.isFinal(sourceField.getModifiers())) {
                continue;
            }
            
            // 查找目标对象中同名字段
            Field targetField = ReflectionUtil.getField(target.getClass(), sourceField.getName());
            if (targetField == null) {
                continue;
            }
            
            // 跳过 static 和 final 目标字段
            if (Modifier.isStatic(targetField.getModifiers()) || 
                Modifier.isFinal(targetField.getModifiers())) {
                continue;
            }
            
            try {
                sourceField.setAccessible(true);
                targetField.setAccessible(true);
                
                Object value = sourceField.get(source);
                
                // 忽略 null 值
                if (ignoreNullValue && value == null) {
                    continue;
                }
                
                // 类型兼容性检查
                if (value != null && !targetField.getType().isAssignableFrom(value.getClass())) {
                    // 尝试基本类型转换
                    value = convertValue(value, targetField.getType());
                    if (value == null) {
                        continue;
                    }
                }
                
                targetField.set(target, value);
            } catch (IllegalAccessException e) {
                // 忽略无法访问的字段
            }
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
        
        Class<?> sourceType = value.getClass();
        
        // 相同类型直接返回
        if (targetType.isAssignableFrom(sourceType)) {
            return value;
        }
        
        // Number 类型互转
        if (value instanceof Number) {
            Number num = (Number) value;
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
        }
        
        // String 转换
        if (targetType == String.class) {
            return value.toString();
        }
        
        return null;
    }
}
