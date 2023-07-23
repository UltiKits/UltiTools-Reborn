package com.ultikits.ultitools.abstracts;

import cn.hutool.core.io.FileUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.manager.*;
import lombok.Getter;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * 插件模块抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Localized {
    private final Language language;
    @Getter
    private final String resourceFolderPath;
    @Getter
    private final String version;
    @Getter
    private final String pluginName;
    @Getter
    private final List<String> authors;
    @Getter
    private final List<String> loadAfter;
    @Getter
    private final int minUltiToolsVersion;
    @Getter
    private final String mainClass;

    @SneakyThrows
    public UltiToolsPlugin() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        String path = jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1);
        URL url = new URL("jar:file:" + path + "!/plugin.yml");
        JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
        InputStream inputStream = jarConnection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        YamlConfiguration pluginConfig = YamlConfiguration.loadConfiguration(reader);
        version = pluginConfig.getString("version");
        pluginName = pluginConfig.getString("name");
        authors = pluginConfig.getStringList("authors");
        loadAfter = pluginConfig.getStringList("loadAfter");
        minUltiToolsVersion = pluginConfig.getInt("api-version");
        mainClass = pluginConfig.getString("mainClass");

        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/pluginConfig/" + this.getPluginName();
        File file = new File(resourceFolderPath + "/lang/" + this.getLanguageCode() + ".json");
        if (!file.exists()) {
            String lanPath = "lang/" + this.getLanguageCode() + ".json";
            InputStream in = getResource(lanPath);
            String result = new BufferedReader(new InputStreamReader(in))
                    .lines().collect(Collectors.joining(""));
            language = new Language(result);
        } else {
            language = new Language(file);
        }
        saveResources();
    }

    public static ConfigManager getConfigManager() {
        return UltiTools.getInstance().getConfigManager();
    }

    public static ListenerManager getListenerManager() {
        return UltiTools.getInstance().getListenerManager();
    }

    public static CommandManager getCommandManager() {
        return UltiTools.getInstance().getCommandManager();
    }

    public static PluginManager getPluginManager() {
        return UltiTools.getInstance().getPluginManager();
    }

    public static ViewManager getViewManager() {
        return UltiTools.getInstance().getViewManager();
    }

    public static VersionWrapper getVersionWrapper() {
        return UltiTools.getInstance().getVersionWrapper();
    }

    protected final String getConfigFolder() {
        return UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/pluginConfig/" + this.getPluginName();
    }

    protected final File getConfigFile(String path) {
        return new File(getConfigFolder() + "/" + path);
    }

    public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
        return getConfigManager().getConfigEntity(this, path, configType);
    }

    public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
        getConfigManager().getConfigEntity(this, path, configType).save();
    }

    @SneakyThrows
    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        JarFile jarFile = new JarFile(jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1));
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry jarEntry = entries.nextElement();
            String fileName = jarEntry.getName();
            if ((fileName.startsWith("res") || fileName.startsWith("lang")) && fileName.contains(".")) {
                InputStream inputStream = jarFile.getInputStream(jarEntry);
                if (inputStream == null) {
                    throw new IllegalArgumentException("The embedded resource '" + fileName + "' cannot be found in " + fileName);
                }
                File outFile = new File(resourceFolderPath, fileName);
                try {
                    if (!outFile.exists()) {
                        FileUtil.touch(outFile);
                        OutputStream out = Files.newOutputStream(outFile.toPath());
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = inputStream.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.close();
                        inputStream.close();
                    }
                } catch (IOException ex) {
                    System.out.println("Could not save " + outFile.getName() + " to " + outFile);
                }
            }
        }
    }

    private InputStream getResource(String filename) {
        try {
            return this.getClass().getClassLoader().getResource(filename).openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    public final <T extends AbstractDataEntity> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        return UltiTools.getInstance().getDataStore().getOperator(this, dataClazz);
    }

    public final String getLanguageCode() {
        return UltiTools.getInstance().getConfig().getString("language");
    }

    public final Language getLanguage() {
        return language;
    }

    public String i18n(String str) {
        return this.i18n(UltiTools.getInstance().getConfig().getString("language"), str);
    }

    @Override
    public final String i18n(String code, String str) {
        return this.getLanguage().getLocalizedText(str);
    }
}
