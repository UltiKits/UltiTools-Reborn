package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;

/**
 * Utility class for annotation processing operations.
 * Provides methods to find annotations on classes including inherited annotations.
 * <br>
 * 注解处理实用工具类。
 * 提供在类上查找注解的方法，包括继承的注解。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class AnnotationUtils {

    /**
     * Private constructor to prevent instantiation.
     * <br>
     * 私有构造函数，防止实例化。
     */
    private AnnotationUtils() {
    }

    /**
     * Find an annotation on a class, searching up the inheritance hierarchy if not found directly.
     * <br>
     * 在类上查找注解，如果直接找不到则向上搜索继承层次结构。
     *
     * @param clazz          the class to search for the annotation <br> 要搜索注解的类
     * @param annotationType the type of annotation to find <br> 要查找的注解类型
     * @param <T>            the annotation type <br> 注解类型
     * @return the annotation if found, or null if not found <br> 如果找到则返回注解，否则返回null
     */
    public static <T extends Annotation> T findAnnotation(Class<?> clazz, Class<T> annotationType) {
        if (clazz == null) {
            return null;
        }
        T annotation = clazz.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        return findAnnotation(clazz.getSuperclass(), annotationType);
    }
}
