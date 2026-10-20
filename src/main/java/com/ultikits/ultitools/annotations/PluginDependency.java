package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares plugin dependencies for UltiTools plugins.
 * Used to ensure plugins are loaded in the correct order.
 * <p>
 * Only {@link #depends()} refuses a module for a missing target: a module is not loaded when a
 * {@code depends} target is missing, and that refusal propagates to every module that
 * transitively {@code depends} on it. {@link #softDepends()} and {@link #loadBefore()} only order
 * modules that are present: a name that is not installed is ignored, and a module that names a
 * module refused for a missing dependency there still loads. A dependency cycle is different:
 * the cycle members and every module that must load after one of them - through any of these
 * attributes or a {@code plugin.yml} {@code loadAfter} entry - cannot be ordered and are not
 * loaded.
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
     * The plugin will fail to load if any of these dependencies are missing or are themselves
     * refused, and every plugin that transitively depends on it is refused with it. This is the
     * only attribute along which a missing-dependency refusal propagates.
     *
     * @return array of required plugin names
     */
    String[] depends() default {};

    /**
     * Optional plugin dependencies (soft dependencies).
     * The plugin will still load even if these dependencies are missing, or are refused for a
     * missing dependency of their own, but it will be loaded after them if they load. Ordering
     * only; see the type-level note on cycles.
     *
     * @return array of optional plugin names
     */
    String[] softDepends() default {};

    /**
     * Plugins that should be loaded after this plugin.
     * Ordering only: it applies to named plugins that are present, a name that is not installed
     * is ignored, and refusing this plugin for a missing dependency does not refuse the named
     * plugins. It does not make the named plugins depend on this one; see the type-level note on
     * cycles.
     *
     * @return array of plugin names that should load after
     */
    String[] loadBefore() default {};
}
