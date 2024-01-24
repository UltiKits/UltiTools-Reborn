package com.ultikits.ultitools.utils;

import com.google.common.reflect.ClassPath;
import org.bukkit.Bukkit;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public class PackageScanUtils {
    /**
     * @param targetAnnotation the target annotation class <br> 目标注解类
     * @param packageName      the package name <br> 包名
     * @param classLoader      the class loader <br> 类加载器
     * @return the set of classes <br> 类集合
     */
    public static Set<Class<?>> scanAnnotatedClasses(
            Class<? extends Annotation> targetAnnotation,
            String packageName,
            ClassLoader classLoader
    ) {
        Set<Class<?>> classes = new HashSet<>();
        try {
            ClassPath classPath = ClassPath.from(classLoader);
            for (ClassPath.ClassInfo classInfo : classPath.getTopLevelClassesRecursive(packageName)) {
                Class<?> c = Class.forName(classInfo.getName(), true, classLoader);
                if (c.isAnnotationPresent(targetAnnotation)) {
                    classes.add(c);
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to scan annotated classes", e);
        }
        return classes;
    }
}
