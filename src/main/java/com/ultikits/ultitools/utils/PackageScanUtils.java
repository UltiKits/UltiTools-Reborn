package com.ultikits.ultitools.utils;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import com.google.common.reflect.ClassPath;

/**
 * Utility class for package scanning and class discovery operations.
 * Provides methods to scan packages and find classes with specific annotations.
 * Uses Google Guava's ClassPath for efficient class scanning.
 * <br>
 * 包扫描和类发现操作的实用工具类。
 * 提供扫描包并查找带有特定注解的类的方法。
 * 使用 Google Guava 的 ClassPath 进行高效的类扫描。
 *
 * @author wisdomme
 * @since 6.0.0
 * @see com.google.common.reflect.ClassPath
 */
public class PackageScanUtils {
    /**
     * Scan for classes annotated with a specific annotation in a given package.
     * This method recursively scans all classes in the specified package and its sub-packages.
     * <br>
     * 扫描给定包中带有特定注解的类。
     * 此方法递归扫描指定包及其子包中的所有类。
     *
     * @param targetAnnotation the target annotation class to search for <br> 要搜索的目标注解类
     * @param packageName      the package name to scan <br> 要扫描的包名
     * @param classLoader      the class loader to use for scanning <br> 用于扫描的类加载器
     * @return a set of classes annotated with the target annotation <br> 带有目标注解的类集合
     */
    public static Set<Class<?>> scanAnnotatedClasses(
            Class<? extends Annotation> targetAnnotation,
            String packageName,
            ClassLoader classLoader
    ) {
        Set<Class<?>> classes = new HashSet<>();
        try {
            // Scan-level failures (a malformed classloader, a genuine OutOfMemoryError while
            // enumerating classpath entries) are a different condition from "one class in the
            // package is unresolvable" -- this catch stays deliberately broad, scoped to
            // building the ClassPath and enumerating its top-level classes. Do not fold the
            // narrower per-class catch below back into this one -- see that catch's own
            // comment for why they are split.
            ClassPath classPath = ClassPath.from(classLoader);
            for (ClassPath.ClassInfo classInfo : classPath.getTopLevelClassesRecursive(packageName)) {
                try {
                    // codacy:ignore - Reflection required for annotation scanning, classInfo.getName() is from ClassPath scan
                    Class<?> c = Class.forName(classInfo.getName(), true, classLoader);
                    if (c.isAnnotationPresent(targetAnnotation)) {
                        classes.add(c);
                    }
                } catch (ClassNotFoundException | LinkageError e) {
                    // Skip-and-continue: a single class in this package that references a
                    // symbol absent from the classpath (e.g. a module built against an older
                    // UltiTools-API version) must not abort discovery of every other class in
                    // the package -- mirrors ComponentScanner's own per-class catch (D-25,
                    // D-26). Deliberately narrower than the outer catch above:
                    // OutOfMemoryError/StackOverflowError are VirtualMachineError, not
                    // LinkageError, and must keep propagating rather than being swallowed
                    // here.
                    ModuleScanDiagnostics.recordSkippedClass(packageName, classInfo.getName(), e);
                    Bukkit.getLogger().log(Level.WARNING,
                            "Failed to load scanned class: " + classInfo.getName(), e);
                }
            }
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to scan annotated classes", e);
        } finally {
            // D-19: one SEVERE summary for this package's scan, if (and only if) the
            // per-class catch above recorded a skipped class. A healthy scan never invokes
            // the emitter -- see ModuleScanDiagnostics' own class javadoc.
            ModuleScanDiagnostics.emitSummary(packageName);
        }
        return classes;
    }
}
