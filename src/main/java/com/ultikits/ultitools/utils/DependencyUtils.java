package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import org.springframework.context.annotation.ComponentScan;

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
