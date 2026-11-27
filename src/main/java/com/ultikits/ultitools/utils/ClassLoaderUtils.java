package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;
import org.jetbrains.annotations.ApiStatus;

import java.util.regex.Pattern;

/**
 * Helpers for framework and module class loading and for loading with an explicit owner loader.
 */
public class ClassLoaderUtils {

    // Class name format validation pattern
    private static final Pattern VALID_CLASS_NAME_PATTERN = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$");

    /**
     * Validate class name format.
     *
     * <p>This is input validation, not a security boundary. Null, blank and malformed
     * names are rejected before the classloader is called.</p>
     *
     * @param className class name to validate
     * @throws SecurityException if the class name is null or does not match the expected format
     */
    private static void validateClassName(String className) throws SecurityException {
        if (className == null || !VALID_CLASS_NAME_PATTERN.matcher(className).matches()) {
            throw new SecurityException("Invalid class name format: " + className);
        }
    }
    
    /**
     * Get the shared loader for framework and module classes, not for external plugin classes.
     *
     * @return framework and module class loader
     */
    public static ClassLoader getPluginClassLoader() {
        return UltiTools.getJavaPluginClassLoader();
    }

    /**
     * Load a framework or module class with name-format validation. This method does not use an
     * external plugin's own loader: for an external plugin class, call {@code loadClass} on that
     * plugin's class loader directly.
     *
     * @param className class name
     * @return loaded class
     * @throws ClassNotFoundException if class not found
     * @throws SecurityException if the class name format is invalid
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException, SecurityException {
        return loadClass(className, getPluginClassLoader());
    }

    /**
     * Load a class by validating its name and delegating to the supplied owner loader.
     * This method does not request class initialization.
     *
     * <p>For framework use only. Modules should not call this method. It is not covered by
     * the public API compatibility policy and may change or be removed in any release.</p>
     *
     * @param className class name
     * @param owner non-null class loader to use for lookup; there is no fallback loader
     * @return loaded class
     * @throws ClassNotFoundException if the owner loader cannot find the class; the loader's exception is the cause
     * @throws NullPointerException if the name is valid and the owner loader is null
     * @throws SecurityException if the class name is null or malformed, even if the owner loader is null
     */
    @ApiStatus.Internal
    public static Class<?> loadClass(String className, ClassLoader owner) throws ClassNotFoundException, SecurityException {
        validateClassName(className);
        try {
            return owner.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to load class: " + className, e);
        }
    }
    
    /**
     * Load a framework or module class with initialization control and name-format validation.
     * This does not use an external plugin's own loader.
     *
     * @param className class name
     * @param initialize whether to initialize the class
     * @return loaded class
     * @throws ClassNotFoundException if class not found
     * @throws SecurityException if the class name format is invalid
     */
    // codacy:ignore - Reflection is required for plugin framework class loading, className is validated
    public static Class<?> loadClass(String className, boolean initialize) throws ClassNotFoundException, SecurityException {
        validateClassName(className);
        try {
            return Class.forName(className, initialize, getPluginClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to load class: " + className, e);
        }
    }
    
    /**
     * Load and validate an UltiToolsPlugin subclass using the shared module loader.
     * This method is not for external Bukkit plugin classes.
     *
     * @param className class name
     * @return loaded plugin class
     * @throws ClassNotFoundException if class not found
     * @throws SecurityException if the class name or plugin hierarchy is invalid
     */
    public static Class<?> loadPluginClass(String className) throws ClassNotFoundException, SecurityException {
        // Additionally validate that the plugin class is under the ultikits package
        if (!className.startsWith("com.ultikits.")) {
            throw new SecurityException("Plugin class must be in com.ultikits package: " + className);
        }

        Class<?> clazz = loadClass(className);

        // Validate that the class is a subclass of UltiToolsPlugin
        try {
            Class<?> pluginBaseClass = getPluginClassLoader().loadClass("com.ultikits.ultitools.abstracts.UltiToolsPlugin");
            if (!pluginBaseClass.isAssignableFrom(clazz)) {
                throw new SecurityException("Class is not a valid UltiToolsPlugin: " + className);
            }
        } catch (ClassNotFoundException e) {
            throw new SecurityException("Cannot validate plugin class hierarchy: " + className, e);
        }
        
        return clazz;
    }
    
    /**
     * Validate a class loader's parent hierarchy against the shared framework and module loader.
     * External Bukkit plugin loaders are not required to use this hierarchy.
     *
     * @param classLoader class loader to validate
     * @return true if valid, false otherwise
     */
    public static boolean validateClassLoaderHierarchy(ClassLoader classLoader) {
        ClassLoader pluginClassLoader = getPluginClassLoader();
        ClassLoader current = classLoader;

        // Walk up the parent chain to find the plugin class loader
        while (current != null) {
            if (current == pluginClassLoader) {
                return true;
            }
            current = current.getParent();
        }
        
        return false;
    }
}
