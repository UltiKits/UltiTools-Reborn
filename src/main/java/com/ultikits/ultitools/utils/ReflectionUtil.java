package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 反射工具类
 * <p>
 * 替代 hutool ReflectUtil / AnnotationUtil
 * 
 * @author wisdomme
 * @since 7.0.0
 */
public final class ReflectionUtil {
    
    private ReflectionUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // ==================== 字段操作 ====================
    
    /**
     * 获取类的所有字段（包括父类）
     *
     * @param clazz 类
     * @return 字段列表
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
    
    /**
     * 获取类的所有字段（包括父类）
     *
     * @param clazz 类
     * @return 字段数组
     */
    public static Field[] getFields(Class<?> clazz) {
        return getAllFields(clazz).toArray(new Field[0]);
    }
    
    /**
     * 获取字段值
     *
     * @param obj   对象
     * @param field 字段
     * @return 字段值
     */
    public static Object getFieldValue(Object obj, Field field) {
        try {
            field.setAccessible(true);
            return field.get(obj);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to get field value: " + field.getName(), e);
        }
    }
    
    /**
     * 获取字段值
     *
     * @param obj       对象
     * @param fieldName 字段名
     * @return 字段值
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new RuntimeException("Field not found: " + fieldName);
        }
        return getFieldValue(obj, field);
    }
    
    /**
     * 设置字段值
     *
     * @param obj   对象
     * @param field 字段
     * @param value 值
     */
    public static void setFieldValue(Object obj, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(obj, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set field value: " + field.getName(), e);
        }
    }
    
    /**
     * 设置字段值
     *
     * @param obj       对象
     * @param fieldName 字段名
     * @param value     值
     */
    public static void setFieldValue(Object obj, String fieldName, Object value) {
        Field field = getField(obj.getClass(), fieldName);
        if (field == null) {
            throw new RuntimeException("Field not found: " + fieldName);
        }
        setFieldValue(obj, field, value);
    }
    
    /**
     * 获取指定字段
     *
     * @param clazz     类
     * @param fieldName 字段名
     * @return 字段，找不到返回 null
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
    
    // ==================== 实例创建 ====================
    
    /**
     * 创建实例（使用无参构造器）
     *
     * @param clazz 类
     * @param <T>   类型
     * @return 实例
     */
    public static <T> T newInstance(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | 
                 InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to create instance: " + clazz.getName(), e);
        }
    }
    
    /**
     * 创建实例（使用带参数的构造器）
     *
     * @param clazz  类
     * @param params 构造器参数
     * @param <T>    类型
     * @return 实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T newInstance(Class<T> clazz, Object... params) {
        if (params == null || params.length == 0) {
            return newInstance(clazz);
        }
        
        Class<?>[] paramTypes = new Class[params.length];
        for (int i = 0; i < params.length; i++) {
            paramTypes[i] = params[i] == null ? Object.class : params[i].getClass();
        }
        
        // 尝试精确匹配
        try {
            java.lang.reflect.Constructor<T> constructor = clazz.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(params);
        } catch (NoSuchMethodException e) {
            // 尝试模糊匹配
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to create instance: " + clazz.getName(), e);
        }
        
        // 模糊匹配 - 查找参数数量相同且类型兼容的构造器
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] ctorParamTypes = constructor.getParameterTypes();
            if (ctorParamTypes.length == params.length) {
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (params[i] != null && !isAssignable(ctorParamTypes[i], params[i].getClass())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    try {
                        constructor.setAccessible(true);
                        return (T) constructor.newInstance(params);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create instance: " + clazz.getName(), e);
                    }
                }
            }
        }
        
        throw new RuntimeException("No suitable constructor found for: " + clazz.getName());
    }
    
    /**
     * 判断类型是否可赋值
     */
    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isAssignableFrom(source)) {
            return true;
        }
        // 处理基本类型
        if (target.isPrimitive()) {
            return getPrimitiveWrapper(target).isAssignableFrom(source);
        }
        if (source.isPrimitive()) {
            return target.isAssignableFrom(getPrimitiveWrapper(source));
        }
        return false;
    }
    
    /**
     * 获取基本类型对应的包装类
     */
    private static Class<?> getPrimitiveWrapper(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }
    
    /**
     * 创建实例（使用无参构造器，支持私有构造器）
     *
     * @param clazz 类
     * @param <T>   类型
     * @return 实例
     */
    public static <T> T newInstanceIfPossible(Class<T> clazz) {
        try {
            java.lang.reflect.Constructor<T> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== 注解操作 ====================
    
    /**
     * 获取类上的注解
     *
     * @param clazz           类
     * @param annotationClass 注解类型
     * @param <A>             注解类型
     * @return 注解实例，不存在返回 null
     */
    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return clazz.getAnnotation(annotationClass);
    }
    
    /**
     * 获取字段上的注解
     *
     * @param field           字段
     * @param annotationClass 注解类型
     * @param <A>             注解类型
     * @return 注解实例，不存在返回 null
     */
    public static <A extends Annotation> A getAnnotation(Field field, Class<A> annotationClass) {
        return field.getAnnotation(annotationClass);
    }
    
    /**
     * 判断类是否有指定注解
     *
     * @param clazz           类
     * @param annotationClass 注解类型
     * @return 是否有注解
     */
    public static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        return clazz.isAnnotationPresent(annotationClass);
    }
    
    /**
     * 判断字段是否有指定注解
     *
     * @param field           字段
     * @param annotationClass 注解类型
     * @return 是否有注解
     */
    public static boolean hasAnnotation(Field field, Class<? extends Annotation> annotationClass) {
        return field.isAnnotationPresent(annotationClass);
    }
    
    // ==================== 方法操作 ====================
    
    /**
     * 调用方法
     *
     * @param obj    对象
     * @param method 方法
     * @param args   参数
     * @param <T>    返回类型
     * @return 方法返回值
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object obj, java.lang.reflect.Method method, Object... args) {
        try {
            method.setAccessible(true);
            return (T) method.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke method: " + method.getName(), e);
        }
    }
    
    /**
     * 获取满足条件的方法
     *
     * @param clazz  类
     * @param filter 过滤条件
     * @return 方法数组
     */
    public static java.lang.reflect.Method[] getMethods(Class<?> clazz, java.util.function.Predicate<java.lang.reflect.Method> filter) {
        java.util.List<java.lang.reflect.Method> result = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (filter == null || filter.test(method)) {
                    result.add(method);
                }
            }
            clazz = clazz.getSuperclass();
        }
        return result.toArray(new java.lang.reflect.Method[0]);
    }
    
    /**
     * 获取类的所有方法（包括父类）
     *
     * @param clazz 类
     * @return 方法数组
     */
    public static java.lang.reflect.Method[] getMethods(Class<?> clazz) {
        return getMethods(clazz, null);
    }
}
