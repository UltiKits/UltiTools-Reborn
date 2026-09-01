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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
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
    @Getter
    private SimpleContainer context;
    private com.ultikits.ultitools.manager.DataScope dataScope;


    /**
     * Constructor for UltiToolsPlugin. For module development only.
     * <p>
     * UltiToolsPlugin的构造函数。仅用于模块开发。
     */
    protected UltiToolsPlugin() {
        YamlConfiguration pluginConfig = loadPluginConfiguration();

        if (!pluginConfig.contains("name")) {
            // D-16: a module with no `name:` key used to silently become "unknown" and share
            // sqliteDB/unknown.db with every other name-less module (measured on-disk: 10 tables
            // from 8 modules, one of them - world_settings - holding the same logical rows as a
            // properly-named module's own .db file). Fail fast at load instead, naming the JAR, so
            // the operator sees this at startup rather than discovering it as missing data later.
            throw new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                    "Module JAR '" + resolveJarFileNameForError() + "' has no 'name:' key in its "
                            + "plugin.yml. Refusing to load rather than silently sharing "
                            + "sqliteDB/unknown.db with other unnamed modules - add a 'name:' key.");
        }

        version = pluginConfig.getString("version", "unknown");
        pluginName = pluginConfig.getString("name");
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
     * Resolves which language code to actually load, consulting {@link Localized#supported()}
     * before {@link #createLanguageFromPath(String)} picks a file (D-20/D-21/WIRE-10). Prefers
     * the configured code; if it is absent from a non-empty {@code supported()}, prefers
     * {@code "en"} when {@code supported()} contains it, otherwise the first entry in
     * {@code supported()}'s iteration order. An empty {@code supported()} is "no information" -
     * the configured code is returned unchanged and nothing is logged.
     * <p>
     * 解析实际应该加载的语言代码，在 {@link #createLanguageFromPath(String)} 选择文件之前先
     * 参考 {@link Localized#supported()}（D-20/D-21/WIRE-10）。优先使用已配置的代码；如果它不在
     * 非空的 {@code supported()} 里，且 {@code supported()} 包含 {@code "en"} 则优先回退到
     * {@code "en"}，否则回退到 {@code supported()} 迭代顺序里的第一个条目。空的
     * {@code supported()} 代表“没有信息”——已配置的代码原样返回，不记录任何日志。
     *
     * @return the language code to actually load
     */
    private String resolveLanguageCode() {
        String configured = getLanguageCode();
        List<String> supportedCodes = this.supported();
        if (supportedCodes == null || supportedCodes.isEmpty()) {
            return configured;
        }
        if (configured != null && supportedCodes.contains(configured)) {
            return configured;
        }
        String fallback = supportedCodes.contains("en") ? "en" : supportedCodes.get(0);
        getLogger().warn("Module '" + getPluginName() + "' is configured for language '" + configured
                + "' but only ships " + supportedCodes + " - falling back to '" + fallback + "'.");
        return fallback;
    }

    /**
     * Creates a Language object from the given resource folder path
     * @param folderPath the resource folder path
     * @return Language object
     */
    private Language createLanguageFromPath(String folderPath) {
        String resolvedCode = resolveLanguageCode();
        File file = new File(folderPath + File.separator + "lang" + File.separator + resolvedCode + ".json");
        if (!file.exists()) {
            String lanPath = "lang" + File.separator + resolvedCode + ".json";
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
     * @deprecated Use the seven-argument constructor and pass {@code resourceFolderPath}
     *             explicitly. This overload hard-codes it to
     *             {@code <dataFolder>/pluginConfig/<pluginName>}.
     *             <p>
     *             请改用七参数构造函数并显式传入 {@code resourceFolderPath}。
     *             此重载把它硬编码成了 {@code <dataFolder>/pluginConfig/<插件名>}。
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.0.8", forRemoval = true)
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
     * Injects the IoC container created for this plugin.
     * <p>
     * 注入为本插件创建的 IoC 容器。
     * <p>
     * Called by {@code PluginManager} while a module is being loaded, before
     * {@code registerSelf()} runs. It is not part of the module-facing API — a
     * module that calls it replaces the container the framework already wired up,
     * losing every bean that was injected into it.
     * <p>
     * 由 {@code PluginManager} 在加载模块时调用，早于 {@code registerSelf()}。它不属于
     * 面向模块的 API——模块自己调用它，等于把框架已经装配好的容器整个换掉，里面注入过的
     * bean 全部丢失。
     * <p>
     * Deliberately still public: {@code PluginManager} lives in another package, so
     * this cannot be narrowed to package-private, and deleting it outright would
     * remove a public method, which the compatibility policy forbids in a PATCH
     * release. The annotation is a signal to humans and IDEs; it enforces nothing
     * at runtime.
     * <p>
     * 刻意保持 public：{@code PluginManager} 不在同一个包，降不成 package-private；
     * 直接删掉则是移除一个 public 方法，兼容性策略不允许 PATCH 版本这么做。注解只是给人
     * 和 IDE 看的信号，运行期不做任何强制。
     *
     * @param context the container created for this plugin <br> 为本插件创建的容器
     */
    @ApiStatus.Internal
    public void setContext(SimpleContainer context) {
        this.context = context;
    }

    /**
     * Injects the {@link com.ultikits.ultitools.manager.DataScope} credential {@code
     * PluginManager} minted for this module (D-17). Called by {@code PluginManager} right after
     * minting, before {@code wireAop} runs -- the same lifecycle point {@link #setContext}
     * documents.
     * <p>
     * Deliberately still public, for the same reason {@link #setContext} is: {@code
     * PluginManager} lives in another package and cannot be narrowed to package-private, and the
     * compatibility policy forbids removing a public method in a PATCH release. The annotation is
     * a signal to humans and IDEs; it enforces nothing at runtime.
     * <p>
     * 注入 {@code PluginManager} 为本模块铸造的 {@link com.ultikits.ultitools.manager.DataScope}
     * 凭证（D-17）。由 {@code PluginManager} 在铸造后、{@code wireAop} 运行前调用——与
     * {@link #setContext} 记录的是同一个生命周期节点。
     * <p>
     * 刻意保持 public，原因与 {@link #setContext} 相同：{@code PluginManager} 不在同一个包，
     * 降不成 package-private；兼容性策略也不允许 PATCH 版本移除一个 public 方法。注解只是给人
     * 和 IDE 看的信号，运行期不做任何强制。
     *
     * @param scope the scope minted for this plugin <br> 为本插件铸造的 scope
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void setDataScope(com.ultikits.ultitools.manager.DataScope scope) {
        this.dataScope = scope;
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
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    public static VersionWrapper getVersionWrapper() {
        return UltiTools.getInstance().getVersionWrapper();
    }

    /**
     * Initializes the configuration entity.
     * <p>
     * 初始化配置实体。
     */
    private void initConfig() throws IOException {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            for (String packageName : DependencyUtils.getPluginPackages(this)) {
                UltiTools.getInstance().getConfigManager().registerAll(
                        this, packageName, UltiTools.getJavaPluginClassLoader()
                );
            }
            // D-06: diff getAllConfigs() against what package-scan auto-registration actually
            // registered. Runs only on this branch - on the config = false branch below,
            // getAllConfigs() is the sole registration path and there is nothing to diff against.
            diffGetAllConfigsOverride();
            return;
        }
        List<AbstractConfigEntity> allConfigs = this.getAllConfigs();
        for (AbstractConfigEntity configEntity : allConfigs) {
            UltiToolsPlugin.getConfigManager().register(this, configEntity);
        }
    }

    /**
     * Diffs a {@link #getAllConfigs()} override against what auto-registration already
     * registered for this module (D-06 / SILENT-18 / #336), called once right after
     * package-scan auto-registration finishes.
     * <p>
     * An empty override (the interface default - the module never wrote {@link #getAllConfigs()})
     * has nothing to compare and nothing to log. A non-empty override whose every {@code
     * configFilePath} was already registered by the package scan is pure redundancy, logged at
     * {@link Level#FINE} only. A non-empty override naming a {@code configFilePath} the scan
     * never registered is real capability loss - #336's warning-only ask cannot tell these two
     * cases apart, so this refuses the module and names every missing entity instead of guessing.
     * <p>
     * 将模块的 {@link #getAllConfigs()} 覆盖与自动注册实际已注册的内容做差集比对
     * （D-06 / SILENT-18 / #336），在包扫描自动注册结束后调用一次。
     * <p>
     * 空覆盖（接口默认值——模块根本没有重写 {@link #getAllConfigs()}）没有可比对的对象，也不记录
     * 任何日志。非空覆盖但其中每一个 {@code configFilePath} 都已被包扫描注册，纯属冗余，仅以
     * {@link Level#FINE} 级别记录一行。非空覆盖里出现了包扫描从未注册过的 {@code configFilePath}，
     * 说明真的丢失了能力——#336 仅要求警告，但警告无法区分这两种情况，因此这里拒绝加载该模块，
     * 并点名每一个丢失的实体，而不是靠猜。
     *
     * @throws ConfigurationException if the override names a {@code configFilePath} auto-registration
     *                                 never registered
     */
    private void diffGetAllConfigsOverride() {
        List<AbstractConfigEntity> override = this.getAllConfigs();
        if (override.isEmpty()) {
            return;
        }
        Set<String> overridePaths = new LinkedHashSet<>();
        for (AbstractConfigEntity entity : override) {
            overridePaths.add(entity.getConfigFilePath());
        }

        Map<String, AbstractConfigEntity> registered = UltiToolsPlugin.getConfigManager().getAllConfigEntities(this);
        Set<String> registeredPaths = registered != null ? registered.keySet() : Collections.<String>emptySet();

        List<String> missing = new ArrayList<>();
        for (String path : overridePaths) {
            if (!registeredPaths.contains(path)) {
                missing.add(path);
            }
        }

        if (missing.isEmpty()) {
            getLogger().debug("getAllConfigs() override registers " + overridePaths.size()
                    + " entit" + (overridePaths.size() == 1 ? "y" : "ies")
                    + " already found by package-scan auto-registration - the override adds nothing.");
            return;
        }

        List<String> violations = new ArrayList<>();
        for (String path : missing) {
            violations.add("getAllConfigs() registers '" + path
                    + "' but package-scan auto-registration never found it - the entity is lost");
        }
        throw ConfigurationException.validationFailed(getPluginName(), "getAllConfigs() override", violations);
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

    /**
     * Best-effort resolution of this module's own JAR file name, for the {@code name:}-missing
     * refusal message only. Never throws -- falls back to the class name if the code source is
     * unavailable (e.g. when running from unpacked classes in a test).
     * <p>
     * 尽力解析本模块自身的 JAR 文件名，仅用于 {@code name:} 缺失时的拒绝加载信息。永不抛出异常——
     * 当代码源不可用时（例如测试中从未打包的 class 运行）回退为类名。
     *
     * @return the JAR file name, or this class's name if it cannot be determined
     */
    private String resolveJarFileNameForError() {
        try {
            CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                String path = src.getLocation().getPath();
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Exception ignored) {
            // Best effort only - fall through to the class-name fallback below.
        }
        return this.getClass().getName();
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
                    // Zip Slip protection: ensure extracted file stays within resource folder
                    String canonicalDest = outFile.getCanonicalPath();
                    String canonicalBase = new File(resourceFolderPath).getCanonicalPath() + File.separator;
                    if (!canonicalDest.startsWith(canonicalBase)) {
                        getLogger().warn("Skipping jar entry with path traversal: " + fileName);
                        continue;
                    }
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
     * Gets data operator. Refuses outright if {@code dataClazz} is not registered to this module
     * (D-14) -- checked against the same {@link com.ultikits.ultitools.manager.DataScope} minted
     * for this module at load, via the same refusal {@code DataStore.getOperator(DataScope,
     * Class)} builds, so the exception type, error code, and message shape are identical
     * regardless of which entry point a caller reaches.
     * <p>
     * <strong>02-13 (CR-03):</strong> before this, {@code dataScope.owns(...)} was checked here
     * inline and this method then delegated to the deprecated {@code getOperator(UltiToolsPlugin,
     * Class)} overload directly -- so {@code DataStore.getOperator(DataScope, Class)}, the method
     * D-17/02-07 built specifically as the credential-typed supported path, had zero real callers
     * anywhere in the framework. This now routes through it, so production actually uses the path
     * it was built for. {@code dataScope} is normally non-null by the time any module calls this
     * (set by {@code PluginManager} right after minting, before {@code registerSelf()} runs); the
     * {@code null} fallback below only covers a bare instance constructed outside the normal
     * {@code PluginManager} load flow (e.g. a test), where the deprecated overload's own {@code
     * checkOwnership(...)} call still refuses correctly on its own.
     * <p>
     * 获取数据操作器。若 {@code dataClazz} 未向本模块注册则直接拒绝（D-14）——校验依据是本模块
     * 加载时铸造的同一个 {@link com.ultikits.ultitools.manager.DataScope}，构造拒绝信息的方式
     * 与 {@code DataStore.getOperator(DataScope, Class)} 完全相同，因此无论调用方走到哪一个
     * 入口，异常类型、错误码和消息形态都一致。
     * <p>
     * <strong>02-13（CR-03）：</strong>在此之前，这里内联检查 {@code dataScope.owns(...)}，
     * 然后本方法直接委托给已废弃的 {@code getOperator(UltiToolsPlugin, Class)} 重载——于是
     * {@code DataStore.getOperator(DataScope, Class)}，即 D-17/02-07 专门构建的、携带凭证的受支持
     * 路径，在整个框架里没有任何真实调用方。现在改为通过它路由，生产环境真正用上了它当初构建的
     * 目的。到任何模块调用本方法时 {@code dataScope} 通常已经非空（由 {@code PluginManager} 在铸造
     * 之后、{@code registerSelf()} 运行之前立即设置）；下面的 {@code null} 回退分支只覆盖在正常
     * {@code PluginManager} 加载流程之外直接构造出的裸实例（例如测试场景），此时已废弃重载自身的
     * {@code checkOwnership(...)} 调用依然能正确拒绝。
     *
     * @param dataClazz the class of the data entity <br> 数据实体的类
     * @param <T>       the type of the data entity <br> 数据实体的类型
     * @return the data operator <br> 数据操作器
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataClazz} is not
     *         registered to this module <br> 如果 {@code dataClazz} 未向本模块注册
     */
    public final <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        if (dataScope != null) {
            return UltiTools.getInstance().getDataStore().getOperator(dataScope, dataClazz);
        }
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
