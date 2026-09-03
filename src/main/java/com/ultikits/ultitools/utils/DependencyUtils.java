package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.context.MergedAnnotationResolver;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Utility class for handling plugin dependency and package scanning operations.
 * This class helps determine which packages should be scanned for components
 * based on annotations like {@link ComponentScan} and {@link EnableAutoRegister}.
 * <p>
 * {@code @UltiToolsModule} carries both {@link ComponentScan} and {@link EnableAutoRegister} as
 * meta-annotations, and its {@code scanBasePackages()}/{@code scanBasePackageClasses()}
 * attributes declare {@code @AliasFor} onto {@link ComponentScan}'s {@code basePackages()}/
 * {@code basePackageClasses()}. Resolution therefore goes through {@link
 * MergedAnnotationResolver#find} rather than the JDK's own direct-annotation-only reflection
 * (a bare {@code Class} lookup, which only sees an annotation declared directly on the class and
 * misses that merge entirely) -- before {@code @since 6.3.0} a class annotated only {@code
 * @UltiToolsModule(scanBasePackages = {...})} silently fell through to the plugin class's own
 * package, discarding its declared value (SILENT-22).
 *
 * @author wisdomme
 * @since 6.0.0
 * @see ComponentScan
 * @see EnableAutoRegister
 */
public class DependencyUtils {


    /**
     * Get plugin packages.
     * <p>
     * Additive, not first-match: every declared source -- {@link ComponentScan#value()}, {@link
     * ComponentScan#basePackages()}, {@link ComponentScan#basePackageClasses()}, and {@link
     * EnableAutoRegister#scanPackage()} -- contributes packages, in declaration order, with
     * duplicates collapsed to their first occurrence, mirroring {@code
     * PluginManager.getPluginScanPackages}'s shape. Falls back to the plugin class's own package
     * only when no source contributes anything.
     *
     * @param plugin UltiTools plugin instance
     * @return Plugin packages
     */
    public static String[] getPluginPackages(UltiToolsPlugin plugin) {
        Class<?> pluginClass = plugin.getClass();

        Set<String> packages = new LinkedHashSet<>();

        ComponentScan componentScan = MergedAnnotationResolver.find(pluginClass, ComponentScan.class);
        if (componentScan != null) {
            Collections.addAll(packages, componentScan.value());
            Collections.addAll(packages, componentScan.basePackages());
            for (Class<?> markerClass : componentScan.basePackageClasses()) {
                // Class.getPackage() is null for an array type/primitive/void -- skip rather
                // than fold a null entry into the scan set.
                Package markerPackage = markerClass.getPackage();
                if (markerPackage != null) {
                    packages.add(markerPackage.getName());
                }
            }
        }

        EnableAutoRegister enableAutoRegister = MergedAnnotationResolver.find(pluginClass, EnableAutoRegister.class);
        if (enableAutoRegister != null && !enableAutoRegister.scanPackage().isEmpty()) {
            packages.add(enableAutoRegister.scanPackage());
        }

        if (packages.isEmpty()) {
            packages.add(pluginClass.getPackage().getName());
        }

        return packages.toArray(new String[0]);
    }


}
