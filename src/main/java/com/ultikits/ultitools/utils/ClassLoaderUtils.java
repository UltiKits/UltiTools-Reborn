package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;

import java.util.regex.Pattern;

/**
 * Utility class for ensuring proper class loader usage throughout the plugin.
 * All class loaders created in this plugin should use the JavaPlugin class loader as parent.
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
     * Get the JavaPlugin class loader.
     * This should be used as the parent class loader for any custom class loaders.
     *
     * @return JavaPlugin class loader
     */
    public static ClassLoader getPluginClassLoader() {
        return UltiTools.getJavaPluginClassLoader();
    }

    /**
     * Load a class using the plugin class loader with name-format validation.
     *
     * @param className class name
     * @return loaded class
     * @throws ClassNotFoundException if class not found
     * @throws SecurityException if the class name format is invalid
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException, SecurityException {
        validateClassName(className);
        try {
            return getPluginClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to load class: " + className, e);
        }
    }
    
    /**
     * Load a class using the plugin class loader with initialization control and name-format validation.
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
     * Safely load a class that extends UltiToolsPlugin.
     * This method provides additional validation for plugin classes.
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
     * Validate that a class loader has the correct parent hierarchy.
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
