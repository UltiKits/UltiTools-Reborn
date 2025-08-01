package com.ultikits.ultitools.utils;
import java.lang.annotation.Annotation;

public class AnnotationUtils {

    private AnnotationUtils() {
    }

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
