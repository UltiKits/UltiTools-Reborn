package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares plugin dependencies for UltiTools plugins.
 * Used to ensure plugins are loaded in the correct order.
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
     *
     * @return array of required plugin names
     */
    String[] depends() default {};

    /**
     * Optional plugin dependencies (soft dependencies).
     * The plugin will still load even if these dependencies are missing,
     * but it will be loaded after them if they exist.
     *
     * @return array of optional plugin names
     */
    String[] softDepends() default {};

    /**
     * Plugins that should be loaded after this plugin.
     * This is the inverse of depends - declares that other plugins depend on this one.
     *
     * @return array of plugin names that should load after
     */
    String[] loadBefore() default {};
}
