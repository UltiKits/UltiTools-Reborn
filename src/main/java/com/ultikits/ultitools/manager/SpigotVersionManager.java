package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.utils.HttpDownloadUtils;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SpigotVersionManager {
    private final String serverVersion = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3].substring(1);

    /**
     * Match version wrapper.
     * <br>
     * 匹配版本包装器。
     *
     * @return Version wrapper <br> 版本包装器
     */
    @SneakyThrows
    public VersionWrapper match() {
        VersionWrapper versionWrapper = null;
        File file = new File(UltiTools.getInstance().getDataFolder(), "/versions/" + serverVersion + ".jar");
        if (!file.exists()) {
            HttpDownloadUtils.download(UltiTools.getEnv().getString("oss-url") + "/versions/" + serverVersion + ".jar", serverVersion + ".jar", UltiTools.getInstance().getDataFolder() + "/versions");
        }
        URL url = file.toURI().toURL();
        URL[] urls = new URL[1];
        urls[0] = url;
        URLClassLoader urlClassLoader = new URLClassLoader(urls, SpigotVersionManager.class.getClassLoader());
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entryEnumeration = jarFile.entries();
            while (entryEnumeration.hasMoreElements()) {
                // 获取JarEntry对象
                JarEntry entry = entryEnumeration.nextElement();
                // 获取当前JarEntry对象的路径+文件名
                if (entry.getName().contains(".class") && !entry.getName().contains("META-INF")) {
                    try {
                        Class<?> aClass = urlClassLoader.loadClass(entry.getName().replace("/", ".").replace(".class", ""));
                        if (VersionWrapper.class.isAssignableFrom(aClass)) {
                            versionWrapper = (VersionWrapper) aClass.newInstance();
                            break;
                        }
                    } catch (NoClassDefFoundError ignored) {
                    }
                }
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ignored) {
        }
        urlClassLoader.close();
        return versionWrapper;
    }
}
