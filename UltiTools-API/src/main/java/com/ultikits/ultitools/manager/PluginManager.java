package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.utils.CommonUtils;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class PluginManager {
    @Getter
    private final List<UltiToolsPlugin> pluginList = new ArrayList<>();

    private final List<Class<? extends UltiToolsPlugin>> pluginClassList = new ArrayList<>();

    public void init() throws IOException {
        File dir = new File(UltiTools.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceAll("%20", " "));
        File pluginsFolder = new File(dir.getParentFile().getPath());
        List<URL> urls = new ArrayList<>();
        urls.add(CommonUtils.getServerJar());
        for (File file : Objects.requireNonNull(pluginsFolder.listFiles())) {
            if (file.getName().endsWith(".jar")) {
                urls.add(new URL(URLDecoder.decode(file.toURI().toASCIIString(), "UTF-8")));
            }
        }

        String currentPath = System.getProperty("user.dir");
        String path = currentPath + File.separator + "plugins" + File.separator + "UltiTools" + File.separator + "plugins";
        File pluginFolder = new File(path);
        File[] plugins = pluginFolder.listFiles((file) -> file.getName().endsWith(".jar"));

        if (plugins == null) {
            return;
        }

        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Found " + plugins.length + " file(s):");

        for (File file : plugins) {
            Bukkit.getLogger().log(Level.INFO, "  - " + file.getName());
            urls.add(new URL(URLDecoder.decode(file.toURI().toASCIIString(), "UTF-8")));
        }

        URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(new URL[0]),
                UltiTools.getInstance().getPluginClassLoader()
        );

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
            if (register(classLoader, pluginClass)) {
                success++;
            }
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Plugin Loading completed.");
        Bukkit.getLogger().log(
                Level.INFO,
                String.format("[UltiTools-API] Succeeded loaded %d, Failed %d.", success, pluginClassList.size() - success)
        );
    }

    public boolean register(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass) {
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
                    pluginClass.getClassLoader(), pluginClass, pluginName, version, authors, loadAfter, minUltiToolsVersion, mainClass
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

    public boolean register(UltiToolsPlugin plugin) {
        try {
            AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
            plugin.setContext(pluginContext);
            pluginContext.setParent(UltiTools.getInstance().getContext());
            pluginContext.registerShutdownHook();
            pluginContext.setClassLoader(plugin.getClass().getClassLoader());
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

    public void unregister(UltiToolsPlugin plugin) {
        UltiTools.getInstance().getListenerManager().unregisterAll(plugin);
        plugin.unregisterSelf();
        plugin.getContext().close();
    }

    public void close() {
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Unregistering all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            unregister(plugin);
        }
        pluginList.clear();
        pluginClassList.clear();
    }

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

    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, Object... constructorArgs) {
        AnnotationConfigApplicationContext pluginContext = new AnnotationConfigApplicationContext();
        pluginContext.setParent(UltiTools.getInstance().getContext());
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

    private void registerBukkit(UltiToolsPlugin plugin, boolean flag) {
        EnableAutoRegister annotation = AnnotationUtils.findAnnotation(plugin.getClass(), EnableAutoRegister.class);
        if (annotation == null) {
            return;
        }
        String[] packages = CommonUtils.getPluginPackages(plugin);
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
