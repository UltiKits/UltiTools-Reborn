package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;

/**
 * Utility class for ensuring proper class loader usage throughout the plugin.
 * All class loaders created in this plugin should use the JavaPlugin class loader as parent.
 * <br>
 * 类加载器实用工具类，确保插件中正确使用类加载器。
 * 此插件中创建的所有类加载器都应使用JavaPlugin类加载器作为父类加载器。
 */
public class ClassLoaderUtils {
    
    /**
     * Get the JavaPlugin class loader.
     * This should be used as the parent class loader for any custom class loaders.
     * <br>
     * 获取JavaPlugin类加载器。
     * 这应该用作任何自定义类加载器的父类加载器。
     *
     * @return JavaPlugin class loader <br> JavaPlugin类加载器
     */
    public static ClassLoader getPluginClassLoader() {
        return UltiTools.getJavaPluginClassLoader();
    }
    
    /**
     * Load a class using the plugin class loader.
     * <br>
     * 使用插件类加载器加载类。
     *
     * @param className class name <br> 类名
     * @return loaded class <br> 加载的类
     * @throws ClassNotFoundException if class not found <br> 如果找不到类
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException {
        return getPluginClassLoader().loadClass(className);
    }
    
    /**
     * Load a class using the plugin class loader with initialization control.
     * <br>
     * 使用插件类加载器加载类，并控制初始化。
     *
     * @param className class name <br> 类名
     * @param initialize whether to initialize the class <br> 是否初始化类
     * @return loaded class <br> 加载的类
     * @throws ClassNotFoundException if class not found <br> 如果找不到类
     */
    public static Class<?> loadClass(String className, boolean initialize) throws ClassNotFoundException {
        return Class.forName(className, initialize, getPluginClassLoader());
    }
    
    /**
     * Validate that a class loader has the correct parent hierarchy.
     * <br>
     * 验证类加载器具有正确的父类层次结构。
     *
     * @param classLoader class loader to validate <br> 要验证的类加载器
     * @return true if valid, false otherwise <br> 如果有效则为true，否则为false
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
