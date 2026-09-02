package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;

/**
 * Utility class for annotation processing operations.
 * Provides methods to find annotations on classes including inherited annotations.
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class AnnotationUtils {

    /**
     * Private constructor to prevent instantiation.
     */
    private AnnotationUtils() {
    }

    /**
     * Find an annotation on a class, searching up the inheritance hierarchy if not found directly.
     *
     * @param clazz          the class to search for the annotation
     * @param annotationType the type of annotation to find
     * @param <T>            the annotation type
     * @return the annotation if found, or null if not found
     * @deprecated This legacy lookup only walks one meta-annotation level and never merges
     *             {@code @AliasFor} overrides. Use
     *             {@link com.ultikits.ultitools.context.MergedAnnotationResolver#find} instead,
     *             which walks the whole annotation tree and applies any {@code @AliasFor}
     *             override found along the way.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public static <T extends Annotation> T findAnnotation(Class<?> clazz, Class<T> annotationType) {
        if (clazz == null) {
            return null;
        }
        T annotation = clazz.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        // Search meta-annotations (annotations on this class's annotations)
        for (Annotation ann : clazz.getAnnotations()) {
            annotation = ann.annotationType().getAnnotation(annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        return findAnnotation(clazz.getSuperclass(), annotationType);
    }
}
