package com.ultikits.ultitools.api;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.context.SimpleContainer;

import lombok.Getter;
import lombok.Setter;

/**
 * Wraps a regular Bukkit {@link JavaPlugin} to provide the information
 * UltiTools framework needs for annotation scanning, IoC, and data storage.
 * <p>
 * 包装普通的 Bukkit {@link JavaPlugin}，为 UltiTools 框架提供注解扫描、IoC 和数据存储所需的信息。
 *
 * @since 6.2.2
 */
public class ExternalPluginAdapter {
    @Getter
    private final JavaPlugin javaPlugin;
    @Getter
    private final String pluginName;
    @Getter
    private final String version;
    @Getter
    private final List<String> authors;
    @Getter
    private final String mainClass;
    @Getter
    private final File dataFolder;
    @Getter
    private final String scanPackage;
    @Getter
    @Setter
    private SimpleContainer context;
    @Getter
    @Setter
    private boolean connected;

    public ExternalPluginAdapter(JavaPlugin javaPlugin) {
        this.javaPlugin = javaPlugin;
        this.pluginName = javaPlugin.getName();
        this.version = javaPlugin.getDescription().getVersion();
        this.authors = javaPlugin.getDescription().getAuthors();
        this.mainClass = javaPlugin.getDescription().getMain();
        this.dataFolder = javaPlugin.getDataFolder();
        this.scanPackage = extractPackage(this.mainClass);
        this.connected = false;
    }

    /**
     * Get the classloader of the wrapped JavaPlugin.
     * <p>
     * 获取被包装的 JavaPlugin 的类加载器。
     *
     * @return the plugin's classloader
     */
    public ClassLoader getPluginClassLoader() {
        return javaPlugin.getClass().getClassLoader();
    }

    /**
     * Get the logger of the wrapped JavaPlugin.
     * <p>
     * 获取被包装的 JavaPlugin 的日志记录器。
     *
     * @return the plugin's logger
     */
    public Logger getLogger() {
        return javaPlugin.getLogger();
    }

    private static String extractPackage(String mainClassName) {
        int lastDot = mainClassName.lastIndexOf('.');
        return lastDot > 0 ? mainClassName.substring(0, lastDot) : "";
    }
}
