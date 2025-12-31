package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;

/**
 * Utility class for handling plugin dependency and package scanning operations.
 * This class helps determine which packages should be scanned for components
 * based on annotations like {@link ComponentScan} and {@link EnableAutoRegister}.
 * <br>
 * 处理插件依赖和包扫描操作的实用工具类。
 * 此类帮助根据 {@link ComponentScan} 和 {@link EnableAutoRegister} 等注解
 * 确定应该扫描哪些包以查找组件。
 *
 * @author wisdomme
 * @since 6.0.0
 * @see ComponentScan
 * @see EnableAutoRegister
 */
public class DependencyUtils {


    /**
     * Get plugin packages.
     * <br>
     * 获取模块包。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Plugin packages <br> 模块包
     */
    public static String[] getPluginPackages(UltiToolsPlugin plugin) {
        Class<?> pluginClass = plugin.getClass();
        String[] packages;

        if (!pluginClass.isAnnotationPresent(ComponentScan.class)) {
            if (pluginClass.isAnnotationPresent(EnableAutoRegister.class)) {
                EnableAutoRegister enableAutoRegister = pluginClass.getAnnotation(EnableAutoRegister.class);
                if (enableAutoRegister.scanPackage().isEmpty()) {
                    packages = new String[]{pluginClass.getPackage().getName()};
                } else {
                    packages = new String[]{enableAutoRegister.scanPackage()};
                }
            } else {
                packages = new String[]{pluginClass.getPackage().getName()};
            }
        } else {
            ComponentScan componentScan = pluginClass.getAnnotation(ComponentScan.class);
            packages = (componentScan.value().length != 0) ? componentScan.value() :
                    (componentScan.basePackages().length != 0) ? componentScan.basePackages() :
                            new String[]{pluginClass.getPackage().getName()};
        }

        return packages;
    }


}
