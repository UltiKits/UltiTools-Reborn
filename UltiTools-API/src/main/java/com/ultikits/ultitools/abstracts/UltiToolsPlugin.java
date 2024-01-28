package com.ultikits.ultitools.abstracts;

import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.io.FileUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.*;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.CommonUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Abstract class representing a plugin module.
 * <p>
 * 插件模块抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    private final Language language;
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
    @Getter
    @Setter
    private String resourceFolderPath;
    @Setter
    @Getter
    private AnnotationConfigApplicationContext context;


    /**
     * Constructor for UltiToolsPlugin. For module development only.
     * <p>
     * UltiToolsPlugin的构造函数。仅用于模块开发。
     */
    @SneakyThrows
    protected UltiToolsPlugin() {
        InputStream inputStream = getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        YamlConfiguration pluginConfig = YamlConfiguration.loadConfiguration(reader);
        version = pluginConfig.getString("version");
        pluginName = pluginConfig.getString("name");
        authors = pluginConfig.getStringList("authors");
        loadAfter = pluginConfig.getStringList("loadAfter");
        minUltiToolsVersion = pluginConfig.getInt("api-version");
        mainClass = pluginConfig.getString("main");
        inputStream.close();
        reader.close();

        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "pluginConfig" + File.separator + this.getPluginName();
        File file = new File(resourceFolderPath + File.separator + "lang" + File.separator + this.getLanguageCode() + ".json");
        if (!file.exists()) {
            String lanPath = "lang" + File.separator + this.getLanguageCode() + ".json";
            InputStream in = getResource(lanPath);
            String result = new BufferedReader(new InputStreamReader(in))
                    .lines().collect(Collectors.joining(""));
            language = new Language(result);
        } else {
            language = new Language(file);
        }
        saveResources();
        initConfig();
    }

    /**
     * Constructor for UltiToolsPlugin. For plugin connector.
     * <p>
     * UltiToolsPlugin的构造函数。用于插件连接器。
     *
     * @param pluginName          the name of the plugin <br> 插件名称
     * @param version             the version of the plugin <br> 插件版本
     * @param authors             the authors of the plugin <br> 插件作者
     * @param loadAfter           the plugins which should be loaded before this plugin <br> 在这个插件之前加载的插件
     * @param minUltiToolsVersion the minimum version of UltiTools required by this plugin <br> 这个插件所需的UltiTools最低版本
     * @param mainClass           the main class of the plugin <br> 插件的主类
     */
    @SneakyThrows
    @Deprecated
    public UltiToolsPlugin(String pluginName, String version, List<String> authors, List<String> loadAfter, int minUltiToolsVersion, String mainClass) {
        this.pluginName = pluginName;
        this.version = version;
        this.authors = authors;
        this.loadAfter = loadAfter;
        this.minUltiToolsVersion = minUltiToolsVersion;
        this.mainClass = mainClass;
        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/pluginConfig/" + this.getPluginName();
        File file = new File(resourceFolderPath + "/lang/" + this.getLanguageCode() + ".json");
        if (!file.exists()) {
            String lanPath = "lang/" + this.getLanguageCode() + ".json";
            InputStream in = getResource(lanPath);
            if (in != null) {
                String result = new BufferedReader(new InputStreamReader(in))
                        .lines().collect(Collectors.joining(""));
                language = new Language(result);
            } else {
                language = new Language("{}");
            }
        } else {
            language = new Language(file);
        }
        saveResources();
        initConfig();
    }

    /**
     * Constructor for UltiToolsPlugin. For plugin connector.
     * <p>
     * UltiToolsPlugin的构造函数。用于插件连接器。
     *
     * @param pluginName          the name of the plugin <br> 插件名称
     * @param version             the version of the plugin <br> 插件版本
     * @param authors             the authors of the plugin <br> 插件作者
     * @param loadAfter           the plugins which should be loaded before this plugin <br> 在这个插件之前加载的插件
     * @param minUltiToolsVersion the minimum version of UltiTools required by this plugin <br> 这个插件所需的UltiTools最低版本
     * @param mainClass           the main class of the plugin <br> 插件的主类
     * @param resourceFolderPath  the path to the resource folder <br> 资源文件夹的路径
     */
    public UltiToolsPlugin(String pluginName, String version, List<String> authors, List<String> loadAfter, int minUltiToolsVersion, String mainClass, String resourceFolderPath) {
        this.pluginName = pluginName;
        this.version = version;
        this.authors = authors;
        this.loadAfter = loadAfter;
        this.minUltiToolsVersion = minUltiToolsVersion;
        this.mainClass = mainClass;
        this.resourceFolderPath = resourceFolderPath;
        File file = new File(resourceFolderPath + File.separator + "lang" + File.separator + this.getLanguageCode() + ".json");
        if (!file.exists()) {
            String lanPath = "lang" + File.separator + this.getLanguageCode() + ".json";
            InputStream in = getResource(lanPath);
            if (in != null) {
                String result = new BufferedReader(new InputStreamReader(in))
                        .lines().collect(Collectors.joining(""));
                language = new Language(result);
            } else {
                language = new Language("{}");
            }
        } else {
            language = new Language(file);
        }
        saveResources();
        try {
            initConfig();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return the config manager <br> 配置管理器
     */
    public static ConfigManager getConfigManager() {
        return UltiTools.getInstance().getConfigManager();
    }

    /**
     * @return the listener manager <br> 监听器管理器
     */
    public static ListenerManager getListenerManager() {
        return UltiTools.getInstance().getListenerManager();
    }

    /**
     * @return the command manager <br> 指令管理器
     */
    public static CommandManager getCommandManager() {
        return UltiTools.getInstance().getCommandManager();
    }

    /**
     * @return the plugin manager <br> 插件管理器
     */
    public static PluginManager getPluginManager() {
        return UltiTools.getInstance().getPluginManager();
    }

    /**
     * @return the version wrapper <br> 版本包装器
     */
    public static VersionWrapper getVersionWrapper() {
        return UltiTools.getInstance().getVersionWrapper();
    }

    /**
     * Initializes the configuration entity.
     * <p>
     * 初始化配置实体。
     */
    private void initConfig() throws IOException {
        EnableAutoRegister annotation = AnnotationUtils.findAnnotation(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            for (String packageName : CommonUtils.getPluginPackages(this)) {
                UltiTools.getInstance().getConfigManager().registerAll(
                        this, packageName, this.getClass().getClassLoader()
                );
            }
        } else {
            List<AbstractConfigEntity> allConfigs = this.getAllConfigs();
            for (AbstractConfigEntity configEntity : allConfigs) {
                UltiToolsPlugin.getConfigManager().register(this, configEntity);
            }
        }
    }

    private InputStream getInputStream() throws IOException {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        String path = jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1);
        URL url = new URL("jar:file:" + path + "!/plugin.yml");
        JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
        return jarConnection.getInputStream();
    }

    protected final String getConfigFolder() {
        return this.resourceFolderPath;
    }

    protected final File getConfigFile(String path) {
        return new File(getConfigFolder() + File.separator + path);
    }

    public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
        return getConfigManager().getConfigEntity(this, configType);
    }

    public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
        return getConfigManager().getConfigEntity(this, path, configType);
    }

    public <T extends AbstractConfigEntity> List<T> getConfigs(Class<T> configType) {
        return getConfigManager().getConfigEntities(this, configType);
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
            if ((fileName.startsWith("res") || fileName.startsWith("lang") || fileName.startsWith("config")) && fileName.contains(".")) {
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
                    UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                }
            }
        }
    }

    private InputStream getResource(String filename) {
        try {
            URL resource = this.getClass().getClassLoader().getResource(filename);
            if (resource == null) {
                return null;
            }
            return resource.openStream();
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

    public boolean isNewerVersionThan(UltiToolsPlugin plugin) {
        return VersionComparator.INSTANCE.compare(this.getVersion(), plugin.getVersion()) > 0;
    }

    @Override
    public void unregisterSelf() {
        getCommandManager().unregisterAll(this);
        getListenerManager().unregisterAll(this);
    }

    @Override
    public void reloadSelf() {
        getConfigManager().reloadConfigs(this);
    }
}
