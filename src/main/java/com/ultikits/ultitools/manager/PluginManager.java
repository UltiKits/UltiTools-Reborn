package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.utils.DependencyUtils;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * UltiTools plugin manager.
 * <p>
 * UltiTools模块管理器
 */
public class PluginManager {
    @Getter
    private final List<UltiToolsPlugin> pluginList = new ArrayList<>();

    private final List<Class<? extends UltiToolsPlugin>> pluginClassList = new ArrayList<>();
    private ClassLoader classLoader;

    /**
     * Initialize plugin manager. Please do not call this method manually.
     * <br>
     * 初始化插件管理器。请不要手动调用此方法。
     *
     * @throws IOException IO exception <br> IO异常
     */
    public void init(ClassLoader classLoader) throws IOException {
        this.classLoader = classLoader;
        String currentPath = System.getProperty("user.dir");
        String path = currentPath + File.separator + "plugins" + File.separator + "UltiTools" + File.separator + "plugins";
        File pluginFolder = new File(path);
        File[] plugins = pluginFolder.listFiles((file) -> file.getName().endsWith(".jar"));

        if (plugins == null) {
            return;
        }

        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Found " + plugins.length + " file(s):");

        for (File file : plugins) {
            Class<? extends UltiToolsPlugin> pluginClass = loadPluginMainClass(classLoader, file);
            if (pluginClass != null) {
                pluginClassList.add(pluginClass);
            }
        }
        int success = 0;
        if (pluginClassList.isEmpty()) {
            Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] No UltiTools plugin found.");
            return;
        }
        Bukkit.getLogger().log(Level.INFO, String.format("[UltiTools-API] %d UltiTools plugin(s) found.", pluginClassList.size()));
        for (Class<? extends UltiToolsPlugin> pluginClass : pluginClassList) {
            if (register(pluginClass)) {
                success++;
            }
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Plugin Loading completed.");
        Bukkit.getLogger().log(
                Level.INFO,
                String.format("[UltiTools-API] Succeeded loaded %d, Failed %d.", success, pluginClassList.size() - success)
        );
    }

    /**
     * Register plugin.
     * <br>
     * 注册插件。
     *
     * @param pluginClass Plugin class <br> 插件类
     * @return Register result <br> 注册结果
     */
    public boolean register(Class<? extends UltiToolsPlugin> pluginClass) {
        UltiToolsPlugin plugin;
        try {
            plugin = initializePlugin(classLoader, pluginClass);
        } catch (Exception e) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s", pluginClass.getName())
            );
            return false;
        }
        boolean result = invokeRegisterSelf(plugin);
        if (result) {
            registerBukkit(plugin, true);
        }
        return result;
    }

    /**
     * Register plugin.
     * <br>
     * 注册插件。
     *
     * @param pluginClass         UltiTools plugin class <br> UltiTools模块类
     * @param pluginName          Plugin name <br> 插件名称
     * @param version             Plugin version <br> 插件版本
     * @param authors             Plugin authors <br> 插件作者
     * @param loadAfter           Load after plugins <br> 加载在此插件之后的插件
     * @param minUltiToolsVersion Min UltiTools version <br> 最低UltiTools版本
     * @param mainClass           Main class <br> 主类
     * @return Register result <br> 注册结果
     */
    public boolean register(
            Class<? extends UltiToolsPlugin> pluginClass,
            String pluginName,
            String version,
            List<String> authors,
            List<String> loadAfter,
            int minUltiToolsVersion,
            String mainClass
    ) {
        UltiToolsPlugin plugin;
        try {
            plugin = initializePlugin(
                    classLoader, pluginClass, pluginName, version, authors, loadAfter, minUltiToolsVersion, mainClass
            );
        } catch (Exception e) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s", pluginClass.getName())
            );
            return false;
        }
        boolean result = invokeRegisterSelf(plugin);
        if (result) {
            registerBukkit(plugin, false);
        }
        return result;
    }

    /**
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Register result <br> 注册结果
     */
    public boolean register(UltiToolsPlugin plugin) {
        try {
            AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
            plugin.setContext(pluginContext);
            pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
            pluginContext.registerShutdownHook();
            pluginContext.setClassLoader(classLoader);
            pluginContext.getBeanFactory().registerSingleton(plugin.getClass().getSimpleName(), plugin);
            pluginContext.refresh();
            if (plugin.getClass().isAnnotationPresent(ContextEntry.class)) {
                ContextEntry contextEntry = plugin.getClass().getAnnotation(ContextEntry.class);
                Class<?> clazz = contextEntry.value();
                pluginContext.register(clazz);
                pluginContext.refresh();
                pluginContext.getAutowireCapableBeanFactory().autowireBean(plugin);
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s", plugin.getPluginName())
            );
            return false;
        }
        boolean result = invokeRegisterSelf(plugin);
        if (result) {
            registerBukkit(plugin, false);
        }
        return result;
    }

    /**
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     */
    public void unregister(UltiToolsPlugin plugin) {
        UltiTools.getInstance().getListenerManager().unregisterAll(plugin);
        plugin.unregisterSelf();
        plugin.getContext().close();
    }

    /**
     * Unregister all plugins.
     * <br>
     * 注销所有插件。
     */
    public void close() {
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Unregistering all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            unregister(plugin);
        }
        pluginList.clear();
        pluginClassList.clear();
    }

    /**
     * Reload all plugins. This operation only reload plugin configuration.
     * <br>
     * 重载所有插件。此操作仅会重新加载插件配置。
     */
    public void reload() {
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Reloading all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            plugin.reloadSelf();
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] All plugins reloaded.");
        Bukkit.getLogger().log(
                Level.WARNING,
                "[UltiTools-API] This operation is only used for reloading plugin configuration. If (un)installing, please restart the server!"
        );
    }

    /**
     * Load module main class.
     * <br>
     * 加载模块主类。
     *
     * @param classLoader Class loader <br> 类加载器
     * @param pluginJar   Plugin jar file <br> 模块jar文件
     * @return Plugin main class <br> 模块主类
     */
    private Class<? extends UltiToolsPlugin> loadPluginMainClass(ClassLoader classLoader, File pluginJar) {
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entryEnumeration = jarFile.entries();
            while (entryEnumeration.hasMoreElements()) {
                JarEntry entry = entryEnumeration.nextElement();
                if (!entry.getName().contains(".class") || entry.getName().contains("META-INF")) {
                    continue;
                }
                String className = entry
                        .getName()
                        .replace('/', '.')
                        .replace(".class", "");
                try {
                    Class<?> aClass = classLoader.loadClass(className);
                    if (IPlugin.class.isAssignableFrom(aClass)) {
                        return aClass.asSubclass(UltiToolsPlugin.class);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }


    /**
     * Invoke registerSelf method.
     * <br>
     * 调用registerSelf方法。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Register result <br> 注册结果
     */
    private boolean invokeRegisterSelf(UltiToolsPlugin plugin) {
        for (UltiToolsPlugin plugin1 : pluginList) {
            if (!plugin1.getMainClass().equals(plugin.getMainClass())) {
                continue;
            }
            if (plugin1.isNewerVersionThan(plugin)) {
                Bukkit.getLogger().log(
                        Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！There is already a new version！", plugin.getPluginName())
                );
                plugin.getContext().close();
                return false;
            } else if (plugin.isNewerVersionThan(plugin1)) {
                plugin1.unregisterSelf();
            }
        }
        if (plugin.getMinUltiToolsVersion() > UltiTools.getPluginVersion()) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] %s load failed！UltiTools version is outdated！", plugin.getPluginName())
            );
            plugin.getContext().close();
            return false;
        }
        try {
            boolean registerSelf = plugin.registerSelf();
            if (registerSelf) {
                pluginList.add(plugin);
                Bukkit.getLogger().log(
                        Level.INFO,
                        String.format("[UltiTools-API] %s loaded！Version: %s。", plugin.getPluginName(), plugin.getVersion())
                );
            } else {
                plugin.getContext().close();
                Bukkit.getLogger().log(
                        Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！Version: %s。", plugin.getPluginName(), plugin.getVersion())
                );
            }
            return registerSelf;
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, e, String::new);
            Bukkit.getLogger().log(Level.WARNING, String.format("[UltiTools-API] %s load failed！", plugin.getPluginName()));
            return false;
        }
    }

    /**
     * Initialize module.
     * <br>
     * 初始化模块。
     *
     * @param classLoader     Class loader <br> 类加载器
     * @param pluginClass     Plugin class <br> 插件类
     * @param constructorArgs Constructor arguments <br> 构造器参数
     * @return UltiTools plugin instance <br> UltiTools模块实例
     */
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, Object... constructorArgs) {
        AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
        pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        pluginContext.registerShutdownHook();
        pluginContext.setClassLoader(classLoader);
        pluginContext.registerBean(pluginClass, constructorArgs);
        pluginContext.refresh();
        UltiToolsPlugin plugin = pluginContext.getBean(pluginClass);
        pluginContext.setDisplayName(plugin.getPluginName());
        pluginContext.setId(plugin.getPluginName());
        plugin.setContext(pluginContext);
        return plugin;
    }

    /**
     * Register bukkit commands or listeners.
     * <br>
     * 注册Bukkit命令或监听器。
     *
     * @param plugin UltiTools module instance <br> UltiTools模块实例
     * @param flag   True if register in default package  <br> 如果在默认包中注册则为true
     */
    private void registerBukkit(UltiToolsPlugin plugin, boolean flag) {
        EnableAutoRegister annotation = AnnotationUtils.findAnnotation(plugin.getClass(), EnableAutoRegister.class);
        if (annotation == null) {
            return;
        }
        String[] packages = DependencyUtils.getPluginPackages(plugin);
        for (String packageName : packages) {
            if (annotation.cmdExecutor()) {
                if (flag) {
                    UltiTools.getInstance().getCommandManager().registerAll(plugin);
                } else {
                    UltiTools.getInstance().getCommandManager().registerAll(plugin, packageName);
                }
            }
            if (annotation.eventListener()) {
                if (flag) {
                    UltiTools.getInstance().getListenerManager().registerAll(plugin);
                } else {
                    UltiTools.getInstance().getListenerManager().registerAll(plugin, packageName);
                }
            }
        }
    }
}
