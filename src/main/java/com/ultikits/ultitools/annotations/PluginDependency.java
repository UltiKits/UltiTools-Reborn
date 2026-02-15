package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares plugin dependencies for UltiTools plugins.
 * Used to ensure plugins are loaded in the correct order.
 * <p>
 * 声明 UltiTools 插件的依赖关系。
 * 用于确保插件按正确的顺序加载。
 * 
 * <pre>{@code
 * @UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
 * @PluginDependency(
 *     depends = {"CorePlugin", "UtilsPlugin"},
 *     softDepends = {"OptionalPlugin"}
 * )
 * public class MyPlugin extends UltiToolsPlugin {
 *     // ...
 * }
 * }</pre>
 *
 * @author wisdomme
 * @since 6.2.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginDependency {
    
    /**
     * Required plugin dependencies.
     * The plugin will fail to load if any of these dependencies are missing.
     * <br>
     * 必需的插件依赖。
     * 如果缺少任何这些依赖，插件将加载失败。
     *
     * @return array of required plugin names <br> 必需的插件名称数组
     */
    String[] depends() default {};
    
    /**
     * Optional plugin dependencies (soft dependencies).
     * The plugin will still load even if these dependencies are missing,
     * but it will be loaded after them if they exist.
     * <br>
     * 可选的插件依赖（软依赖）。
     * 即使缺少这些依赖，插件仍会加载，
     * 但如果它们存在，会在它们之后加载。
     *
     * @return array of optional plugin names <br> 可选的插件名称数组
     */
    String[] softDepends() default {};
    
    /**
     * Plugins that should be loaded after this plugin.
     * This is the inverse of depends - declares that other plugins depend on this one.
     * <br>
     * 应在此插件之后加载的插件。
     * 这与 depends 相反 - 声明其他插件依赖于此插件。
     *
     * @return array of plugin names that should load after <br> 应在之后加载的插件名称数组
     */
    String[] loadBefore() default {};
}
