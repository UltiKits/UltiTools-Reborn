package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class PluginManager {
    private static final Map<Class<? extends Registrable>, List<Registrable>> registeredService = new HashMap<>();
    private static final List<UltiToolsPlugin> pluginList = new ArrayList<>();

    public static void init() throws IOException {
        Bukkit.getLogger().log(Level.INFO, "正在加载UltiTools拓展插件...");
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
        URLClassLoader urlClassLoader = new URLClassLoader(urls, PluginManager.class.getClassLoader());
        for (File file : plugins) {
            try (JarFile jarFile = new JarFile(file)) {
                Enumeration<JarEntry> entryEnumeration = jarFile.entries();
                while (entryEnumeration.hasMoreElements()) {
                    // 获取JarEntry对象
                    JarEntry entry = entryEnumeration.nextElement();
                    // 获取当前JarEntry对象的路径+文件名
                    if (entry.getName().contains(".class")) {
                        Class<?> aClass = urlClassLoader.loadClass(entry.getName().replace("/", ".").replace(".class", ""));
                        if (IPlugin.class.isAssignableFrom(aClass)) {
                            UltiToolsPlugin plugin = (UltiToolsPlugin) aClass.newInstance();
                            pluginList.add(plugin);
                        }
                    }
                }
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ignored) {
            }
        }
        urlClassLoader.close();
        int success = 0;
        if (pluginList.size() == 0) {
            Bukkit.getLogger().log(Level.INFO, "未发现任何UltiTools拓展插件！");
            return;
        }
        Bukkit.getLogger().log(Level.INFO, String.format("发现%d个UltiTools拓展插件！", pluginList.size()));
        for (int i = 0; i < pluginList.size(); i++) {
            Bukkit.getLogger().log(Level.INFO, String.format("正在加载第%d个插件...", i + 1));
            IPlugin plugin = pluginList.get(i);
            boolean registerSelf = plugin.registerSelf();
            if (registerSelf) {
                success += 1;
                Bukkit.getLogger().log(Level.INFO, String.format("第%d个插件加载成功！", i + 1));
            } else {
                Bukkit.getLogger().log(Level.WARNING, String.format("第%d个插件加载失败！", i + 1));
            }
        }
        Bukkit.getLogger().log(Level.INFO, String.format("成功加载%d个插件！失败%d个！", success, pluginList.size() - success));
    }

    public static void close() {
        Bukkit.getLogger().log(Level.INFO, "正在注销所有插件...");
        pluginList.forEach(IPlugin::unregisterSelf);
    }

    public static synchronized boolean register(Class<? extends Registrable> api, Registrable impl) {
        if (!registeredService.containsKey(api)) {
            List<Registrable> registrables = new ArrayList<>();
            registeredService.put(api, registrables);
        }
        List<Registrable> classes = registeredService.get(api);
        if (!classes.contains(impl)) {
            classes.add(impl);
        }
        registeredService.put(api, classes);
        return true;
    }

    public static synchronized void unregister(Class<? extends Registrable> api, Registrable impl) {
        if (!registeredService.containsKey(api)) {
            List<Registrable> registrables = new ArrayList<>();
            registeredService.put(api, registrables);
            return;
        }
        List<Registrable> classes = registeredService.get(api);
        classes.remove(impl);
        registeredService.put(api, classes);
    }

    public static <T extends Registrable> T getService(Class<T> service) {
        return getService(service, 0);
    }

    public static <T extends Registrable> T getService(Class<T> service, int index) {
        List<Registrable> registrables = registeredService.get(service);
        Registrable registrable = registrables.get(index);
        if (registrable == null) {
            return null;
        }
        return service.cast(registrable);
    }

    public static <T extends Registrable> T getService(Class<T> service, String clazzName) {
        List<Registrable> registrables = registeredService.get(service);
        for (Registrable registrable : registrables){
            if (registrable.getClass().getName().equals(clazzName)){
                return service.cast(registrable);
            }
        }
        return null;
    }
}
