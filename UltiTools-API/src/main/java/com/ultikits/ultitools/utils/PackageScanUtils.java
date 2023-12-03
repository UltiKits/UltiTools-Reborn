package com.ultikits.ultitools.utils;

import com.google.common.reflect.ClassPath;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

public class PackageScanUtils {
    public static Set<Class<?>> scanAnnotatedClasses(
            Class<? extends Annotation> targetAnnotation,
            String packageName,
            ClassLoader classLoader
    ) {
        Set<Class<?>> classes = new HashSet<>();
        try {
            ClassPath classPath = ClassPath.from(classLoader);
            for (ClassPath.ClassInfo classInfo : classPath.getTopLevelClassesRecursive(packageName)) {
                Class<?> c = Class.forName(classInfo.getName(),true, classLoader);
                if (c.isAnnotationPresent(targetAnnotation)) {
                    classes.add(c);
                }
            }
        } catch (Exception e) {
            ExceptionUtils.catchException(e);
        }
        return classes;
    }
}
