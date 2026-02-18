package com.ultikits.ultitools.abstracts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

import org.bukkit.configuration.file.YamlConfiguration;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.Configurable;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.AnnotationUtils;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract class representing a plugin module.
 * <p>
 * 插件模块抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    private Language language;
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
    private final String identifyString;
    @Getter
    @Setter
    private String resourceFolderPath;
    @Setter
    @Getter
    private SimpleContainer context;


    /**
     * Constructor for UltiToolsPlugin. For module development only.
     * <p>
     * UltiToolsPlugin的构造函数。仅用于模块开发。
     */
    protected UltiToolsPlugin() {
        YamlConfiguration pluginConfig = loadPluginConfiguration();
        
        version = pluginConfig.getString("version", "unknown");
        pluginName = pluginConfig.getString("name", "unknown");
        authors = pluginConfig.getStringList("authors");
        loadAfter = pluginConfig.getStringList("loadAfter");
        minUltiToolsVersion = pluginConfig.getInt("api-version", 0);
        mainClass = pluginConfig.getString("main", "unknown");
        identifyString = pluginConfig.getString("identify-string", null);

        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "pluginConfig" + File.separator + this.getPluginName();
        language = initializeLanguage();
        saveResources();
        try{
            initConfig();
        } catch (IOException e) {
            getLogger().error(e);
        }
    }

    /**
     * Initializes the language object
     * @return Language object
     */
    private Language initializeLanguage() {
        return createLanguageFromPath(resourceFolderPath);
    }

    /**
     * Creates a Language object from the given resource folder path
     * @param folderPath the resource folder path
     * @return Language object
     */
    private Language createLanguageFromPath(String folderPath) {
        File file = new File(folderPath + File.separator + "lang" + File.separator + this.getLanguageCode() + ".json");
        if (!file.exists()) {
            String lanPath = "lang" + File.separator + this.getLanguageCode() + ".json";
            InputStream in = getResource(lanPath);
            if (in != null) {
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in))) {
                    String result = bufferedReader.lines().collect(Collectors.joining(""));
                    return new Language(result);
                } catch (IOException e) {
                    getLogger().error("Failed to read language file", e);
                    return new Language("{}");
                }
            } else {
                return new Language("{}");
            }
        } else {
            return new Language(file);
        }
    }

    /**
     * Loads the plugin configuration from plugin.yml
     * @return YamlConfiguration object with default values if loading fails
     */
    private YamlConfiguration loadPluginConfiguration() {
        try (InputStream inputStream = getInputStream()) {
            if (inputStream == null) {
                getLogger().error("Cannot find plugin.yml in the plugin jar");
                return new YamlConfiguration();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            getLogger().error("Failed to load plugin configuration", e);
            return new YamlConfiguration();
        }
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
    @Deprecated
    public UltiToolsPlugin(String pluginName, String version, List<String> authors, List<String> loadAfter, int minUltiToolsVersion, String mainClass) {
        this(pluginName, version, authors, loadAfter, minUltiToolsVersion, mainClass,
             UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/pluginConfig/" + pluginName);
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
        this.identifyString = null; // Connector plugins don't have identify-string
        this.resourceFolderPath = resourceFolderPath;
        language = createLanguageFromPath(resourceFolderPath);
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
     * @deprecated Use {@link com.ultikits.ultitools.utils.XVersionUtils} instead.
     */
    @Deprecated
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
            for (String packageName : DependencyUtils.getPluginPackages(this)) {
                UltiTools.getInstance().getConfigManager().registerAll(
                        this, packageName, UltiTools.getJavaPluginClassLoader()
                );
            }
            return;
        }
        List<AbstractConfigEntity> allConfigs = this.getAllConfigs();
        for (AbstractConfigEntity configEntity : allConfigs) {
            UltiToolsPlugin.getConfigManager().register(this, configEntity);
        }
    }

    private InputStream getInputStream() throws IOException {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        String path = jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1);
        try {
            URL url = new java.net.URI("jar:file:" + path + "!/plugin.yml").toURL();
            JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
            return jarConnection.getInputStream();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid URL format", e);
        }
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

    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        try (JarFile jarFile = new JarFile(
                jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1)
        )) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String fileName = jarEntry.getName();
                if ((!fileName.startsWith("res") && !fileName.startsWith("lang")
                        && !fileName.startsWith("config")) || !fileName.contains(".")) {
                    continue;
                }
                try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                    if (inputStream == null) {
                        throw new IllegalArgumentException("The embedded resource '" + fileName + "' cannot be found in " + fileName);
                    }
                    File outFile = new File(resourceFolderPath, fileName);
                    try {
                        if (outFile.exists()) {
                            continue;
                        }
                        FileUtils.touch(outFile);
                        try (OutputStream out = Files.newOutputStream(outFile.toPath())) {
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = inputStream.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                        }
                    } catch (IOException ex) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().error("Failed to save resources from jar", e);
        }
    }

    private InputStream getResource(String filename) {
        try {
            ClassLoader classLoader = this.getClass().getClassLoader();
            URL resource = classLoader.getResource(filename);
            if (resource == null) {
                return null;
            }
            return resource.openStream();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Gets data operator.
     * <p>
     * 获取数据操作器。
     *
     * @param dataClazz the class of the data entity <br> 数据实体的类
     * @param <T>       the type of the data entity <br> 数据实体的类型
     * @return the data operator <br> 数据操作器
     */
    public final <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        return UltiTools.getInstance().getDataStore().getOperator(this, dataClazz);
    }

    /**
     * @return language code <br> 语言代码
     */
    public final String getLanguageCode() {
        return UltiTools.getInstance().getConfig().getString("language");
    }

    /**
     * @return the language <br> 语言
     */
    public final Language getLanguage() {
        return language;
    }

    /**
     * @param str the string to be localized <br> 要本地化的字符串
     * @return the localized string <br> 本地化后的字符串
     */
    public String i18n(String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    @Override
    public final String i18n(String code, String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    /**
     * @param plugin the plugin to be checked <br> 要检查的插件
     * @return whether the plugin is newer than the given plugin <br> 插件是否比给定的插件新
     */
    public boolean isNewerVersionThan(UltiToolsPlugin plugin) {
        if (plugin == null || plugin.getVersion() == null || this.getVersion() == null) {
            return false;
        }
        return VersionComparatorUtil.compare(this.getVersion(), plugin.getVersion()) > 0;
    }

    @Override
    public void unregisterSelf() {
        getCommandManager().unregisterAll(this);
        getListenerManager().unregisterAll(this);
    }

    @Override
    public void reloadSelf() {
        getConfigManager().reloadConfigs(this);
        // Reinitialize language in case language setting changed
        language = createLanguageFromPath(resourceFolderPath);
    }

    /**
     * @return plugin logger <br> 插件日志发送器
     */
    public PluginLogger getLogger() {
        return new PluginLogger(this.pluginName, UltiTools.getInstance().getLogger());
    }
}
