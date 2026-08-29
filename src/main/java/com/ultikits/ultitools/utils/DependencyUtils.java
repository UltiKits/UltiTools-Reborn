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
 * <br>
 * 处理插件依赖和包扫描操作的实用工具类。
 * 此类帮助根据 {@link ComponentScan} 和 {@link EnableAutoRegister} 等注解
 * 确定应该扫描哪些包以查找组件。
 * <p>
 * {@code @UltiToolsModule} 同时把 {@link ComponentScan} 与 {@link EnableAutoRegister} 作为元注解
 * 携带，其 {@code scanBasePackages()}/{@code scanBasePackageClasses()} 属性通过
 * {@code @AliasFor} 指向 {@link ComponentScan} 的 {@code basePackages()}/
 * {@code basePackageClasses()}。因此解析必须走 {@link MergedAnnotationResolver#find}，而不是
 * 只能看到类上直接注解、完全错过这层合并的 JDK 自带反射查询——在 {@code @since 6.3.0} 之前，
 * 一个只标注了 {@code @UltiToolsModule(scanBasePackages = {...})} 的类会静默落回插件类自身的
 * 包名，丢弃它声明的值（SILENT-22）。
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
     * <br>
     * 获取模块包。
     * <p>
     * 累加式而非首个匹配优先：每一个已声明的来源——{@link ComponentScan#value()}、
     * {@link ComponentScan#basePackages()}、{@link ComponentScan#basePackageClasses()} 以及
     * {@link EnableAutoRegister#scanPackage()}——都会按声明顺序贡献包名，重复项只保留首次出现的
     * 那个，与 {@code PluginManager.getPluginScanPackages} 的形状一致。只有当没有任何来源贡献
     * 任何内容时，才回退到插件类自身的包名。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Plugin packages <br> 模块包
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
