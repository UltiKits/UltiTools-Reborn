package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class DependencyUtils {
    /**
     * Download required dependencies.
     * <br>
     * 下载必要的依赖。
     */
    public static void downloadRequiredDependencies() {
        YamlConfiguration env = UltiTools.getEnv();
        List<String> dependencies = env.getStringList("libraries");
        List<String> missingLib = new ArrayList<>();
        for (String lib : dependencies) {
            File file = new File(UltiTools.getInstance().getDataFolder() + "/lib", lib);
            if (!file.exists()) {
                missingLib.add(lib);
            }
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Missing required libraries，trying to download...");
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] If have problems in downloading，you can download full version.");
        for (int i = 0; i < missingLib.size(); i++) {
            String name = missingLib.get(i);
            File file = new File(UltiTools.getInstance().getDataFolder() + "/lib", name);
            String url = env.getString("oss-url") + env.getString("lib-path") + name;
            double i1 = (double) i / missingLib.size();
            int percentage = (int) (i1 * 100);
            printLoadingBar(percentage);
            HttpDownloadUtils.download(url, name, UltiTools.getInstance().getDataFolder() + "/lib");
            ClassLoader classLoader = UltiTools.getInstance().getClass().getClassLoader().getParent();
            loadJar(file, classLoader);
        }
    }

    /**
     * Load jar file.
     * <br>
     * 加载jar文件。
     *
     * @param jar        jar file <br> jar文件
     * @param classLoader Class loader <br> 类加载器
     */
    public static void loadJar(File jar, ClassLoader classLoader) {
        try (JarFile jarFile = new JarFile(jar)) {
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
                    classLoader.loadClass(className);
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void printLoadingBar(int percentage) {
        StringBuilder loadingBar = new StringBuilder("[");
        int progress = percentage / 10;
        for (int i = 0; i < progress; i++) {
            loadingBar.append("*");
        }
        for (int i = progress; i < 10; i++) {
            loadingBar.append("-");
        }
        loadingBar.append("] ");
        loadingBar.append(percentage);
        loadingBar.append("%");
        Bukkit.getLogger().log(Level.INFO, "[UltiTools]Downloading: " + loadingBar);
    }
}
