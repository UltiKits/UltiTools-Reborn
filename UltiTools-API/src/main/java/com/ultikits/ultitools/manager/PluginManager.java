package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.interfaces.IPlugin;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class PluginManager {
    @Getter
    private final List<UltiToolsPlugin> pluginList = new ArrayList<>();

    private URLClassLoader urlClassLoader;

    public void init() throws IOException {
        Bukkit.getLogger().log(Level.INFO, "Starting to load UltiTools plugins...");
        String currentPath = System.getProperty("user.dir");
        String path = currentPath + File.separator + "plugins" + File.separator + "UltiTools" + File.separator + "plugins";
        File pluginFolder = new File(path);
        File[] plugins = pluginFolder.listFiles((file) -> file.getName().endsWith(".jar"));
        if (plugins == null) {
            return;
        }

        URL[] urls = new URL[plugins.length];

        for (int i = 0; i < plugins.length; i++) {
            urls[i] = plugins[i].toURI().toURL();
        }

        // 将jar文件组成数组，来创建一个URLClassLoader
        urlClassLoader = new URLClassLoader(urls, UltiTools.getInstance().getPluginClassLoader());
        for (File file : plugins) {
            try (JarFile jarFile = new JarFile(file)) {
                Enumeration<JarEntry> entryEnumeration = jarFile.entries();
                while (entryEnumeration.hasMoreElements()) {
                    // 获取JarEntry对象
                    JarEntry entry = entryEnumeration.nextElement();
                    // 获取当前JarEntry对象的路径+文件名
                    if (!entry.getName().contains(".class") || entry.getName().contains("META-INF")) {
                        continue;
                    }
                    try {
                        Class<?> aClass = urlClassLoader.loadClass(entry.getName().replace("/", ".").replace(".class", ""));
                        if (!IPlugin.class.isAssignableFrom(aClass)) {
                            continue;
                        }
                        UltiToolsPlugin plugin = (UltiToolsPlugin) aClass.getDeclaredConstructor().newInstance();
                        if (plugin.getPluginName() != null) {
                            pluginList.add(plugin);
                        }
                    } catch (NoClassDefFoundError | InvocationTargetException | NoSuchMethodException ignored) {
                    }
                }
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ignored) {
            }
        }
        int success = 0;
        if (pluginList.isEmpty()) {
            Bukkit.getLogger().log(Level.INFO, "No UltiTools plugins found.");
            return;
        }
        Bukkit.getLogger().log(Level.INFO, String.format("%d UltiTools plugins found.", pluginList.size()));
        for (int i = 0; i < pluginList.size(); i++) {
            Bukkit.getLogger().log(Level.INFO, String.format("Now loading plugin %d", i + 1));
            UltiToolsPlugin plugin = pluginList.get(i);
            if (plugin.getMinUltiToolsVersion() > UltiTools.getPluginVersion()) {
                Bukkit.getLogger().log(Level.WARNING, String.format("%s load failed！UltiTools version is outdated！", plugin.getPluginName()));
                continue;
            }
            try {
                EnableAutoRegister annotation = plugin.getClass().getAnnotation(EnableAutoRegister.class);
                if (annotation != null && annotation.config()) {
                    UltiTools.getInstance().getConfigManager().registerAll(
                            plugin,
                            annotation.scanPackage().isEmpty() ?
                                    plugin.getClass().getPackage().getName() :
                                    annotation.scanPackage(),
                            urlClassLoader
                    );
                } else {
                    List<AbstractConfigEntity> allConfigs = plugin.getAllConfigs();
                    for (AbstractConfigEntity configEntity : allConfigs) {
                        UltiToolsPlugin.getConfigManager().register(plugin, configEntity);
                    }
                }
                initPluginContext(plugin);
                boolean registerSelf = plugin.registerSelf();
                initAutoRegister(plugin);
                if (registerSelf) {
                    success += 1;
                    Bukkit.getLogger().log(Level.INFO, String.format("%s loaded！Version: %s。", plugin.getPluginName(), plugin.getVersion()));
                } else {
                    plugin.getContext().close();
                    Bukkit.getLogger().log(Level.WARNING, String.format("%s load failed！Version: %s。", plugin.getPluginName(), plugin.getVersion()));
                }
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, e, String::new);
                Bukkit.getLogger().log(Level.WARNING, String.format("%s load failed！", plugin.getPluginName()));
            }
        }
        Bukkit.getLogger().log(Level.INFO, String.format("Successfully loaded %d plugins! Failed %d!", success, pluginList.size() - success));
    }

    public void close() {
        Bukkit.getLogger().log(Level.INFO, "Unregistering all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            UltiTools.getInstance().getListenerManager().unregisterAll(plugin);
            plugin.unregisterSelf();
            plugin.getContext().close();
        }
        pluginList.clear();
        try {
            urlClassLoader.close();
        } catch (IOException ignored) {
        }
    }

    public void reload() {
        Bukkit.getLogger().log(Level.INFO, "Reloading all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            plugin.reloadSelf();
        }
        Bukkit.getLogger().log(Level.INFO, "All plugins reloaded.");
        Bukkit.getLogger().log(Level.WARNING, "This operation is only used for reloading plugin configuration. If (un)installing, please restart the server!");
    }

    private void initPluginContext(UltiToolsPlugin plugin) {
        plugin.getContext().setParent(UltiTools.getInstance().getContext());
        plugin.getContext().registerShutdownHook();
        plugin.getContext().setClassLoader(urlClassLoader);
        if (plugin.getClass().isAnnotationPresent(ContextEntry.class)) {
            Class<?> contextEntry = plugin.getClass().getAnnotation(ContextEntry.class).value();
            plugin.getContext().register(contextEntry);
            plugin.getContext().refresh();
            plugin.getContext().getAutowireCapableBeanFactory().autowireBean(plugin);
        }
    }

    private void initAutoRegister(UltiToolsPlugin plugin) {
        if (!plugin.getClass().isAnnotationPresent(EnableAutoRegister.class)) {
            return;
        }
        EnableAutoRegister annotation = plugin.getClass().getAnnotation(EnableAutoRegister.class);
        if (annotation.cmdExecutor()) {
            UltiTools.getInstance().getCommandManager().registerAll(
                    plugin,
                    annotation.scanPackage().isEmpty() ?
                            plugin.getClass().getPackage().getName() :
                            annotation.scanPackage()
            );
        }
        if (annotation.eventListener()) {
            UltiTools.getInstance().getListenerManager().registerAll(
                    plugin,
                    annotation.scanPackage().isEmpty() ?
                            plugin.getClass().getPackage().getName() :
                            annotation.scanPackage()
            );
        }
    }
}
