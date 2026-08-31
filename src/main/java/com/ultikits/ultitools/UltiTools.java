package com.ultikits.ultitools;

import static com.ultikits.ultitools.utils.CommonUtils.getUltiToolsUUID;
import static com.ultikits.ultitools.utils.PluginInitiationUtils.stopWebsocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.commands.CloudLoginCommand;
import com.ultikits.ultitools.commands.PluginInstallCommands;
import com.ultikits.ultitools.commands.UltiToolsCommands;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.interfaces.impl.DefaultVersionWrapper;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.ultikits.ultitools.listeners.PlayerJoinListener;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.listeners.UpdateJoinListener;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.utils.ApiRateLimiter;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.Metrics;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.SecurityPolicy;

import lombok.Getter;
import lombok.Setter;
import net.milkbowl.vault.economy.Economy;

/**
 * UltiTools plugin main class.
 * <p>
 * UltiTools插件主类。
 *
 * @author wisdommen, qianmo
 * @version 6.0.7
 */
public final class UltiTools extends JavaPlugin implements Localized {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^([0-9]+\\.[0-9]+\\.[0-9]+)(?:-[0-9A-Za-z]+)*$");
    // Deliberately java.util.logging, not Bukkit.getLogger() — this backs a static, test-seam
    // method (collectModuleJarUrls) that must be callable from a plain JUnit test with no live
    // Bukkit server (see the WIRE-11 test-seam decision recorded in 04-03-PLAN.md).
    // 刻意使用 java.util.logging 而非 Bukkit.getLogger() —— 这是静态测试缝合方法
    // （collectModuleJarUrls）的日志通道，该方法必须能在没有真实 Bukkit 服务器的纯 JUnit
    // 测试中直接调用（参见 04-03-PLAN.md 记录的 WIRE-11 测试缝合决策）。
    private static final Logger MODULE_SCAN_LOGGER = Logger.getLogger(UltiTools.class.getName());
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Getter
    private DependenceManagers dependenceManagers;
    private URLClassLoader ultiToolsClassLoader;
    /**
     * @deprecated Use {@link com.ultikits.ultitools.utils.XVersionUtils} instead.
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    private VersionWrapper versionWrapper;

    /**
     * 手写而不是用 Lombok 的 {@code @Getter}。Lombok 会把 {@code @Deprecated} 复制到生成的
     * accessor 上，但<b>丢掉 {@code since} 与 {@code forRemoval} 两个元素</b>，编译产物里只剩一个
     * 裸 {@code @Deprecated}。而 javac 的 {@code -Xlint:removal} 自 JDK 9 起默认开启、
     * {@code -Xlint:deprecation} 默认关闭，所以下游用默认参数编译时收不到点名的移除警告，
     * 只会看到一句不含 API 名的笼统提示。字段上标了 {@code forRemoval} 不解决问题 ——
     * 对外的入口是这个 getter，标注必须落在它身上。改回 {@code @Getter} 会让
     * COMPATIBILITY.md 的移除清单对这一项失真。
     *
     * <p>Hand-written rather than Lombok's {@code @Getter}: Lombok copies
     * {@code @Deprecated} onto the generated accessor but drops the {@code since}
     * and {@code forRemoval} elements, so downstream compiling with default flags
     * never sees the named {@code [removal]} warning for this API.
     *
     * @return the version wrapper <br> 版本适配器
     * @deprecated Use {@link com.ultikits.ultitools.utils.XVersionUtils} instead.
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    public VersionWrapper getVersionWrapper() {
        return versionWrapper;
    }

    @Getter
    private Language language;
    @Getter
    private PluginManager pluginManager;
    @Getter
    private ConfigManager configManager;
    @Getter
    @Setter
    private DataStore dataStore;
    @Getter
    private ServerMonitorManager serverMonitorManager;
    @Getter
    private CommandExecutionManager commandExecutionManager;
    @Getter
    private FileOperationManager fileOperationManager;
    @Getter
    private LogStreamManager logStreamManager;
    @Getter
    private PlayerEventManager playerEventManager;
    @Getter
    private ServerPropertiesManager serverPropertiesManager;
    @Getter
    private UpdateManager updateManager;
    @Getter
    private EventBus eventBus;
    @Getter
    private ErrorReportCollector errorReportCollector;
    @Getter
    private RemoteActionLog remoteActionLog;

    /**
     * Returns the instance of the UltiTools.
     * <p>
     * 获取UltiTools的实例。
     *
     * @return the instance of the UltiTools <br> UltiTools的实例
     */
    public static UltiTools getInstance() {
        return ultiTools;
    }

    /**
     * Gets the version of UltiTools.
     * <p>
     * 获取UltiTools的版本。
     *
     * @return the version of the UltiTools <br> UltiTools的版本
     */
    public static int getPluginVersion() {
        String versionString = getEnv().getString("version");
        return parsePluginVersion(versionString);
    }

    static int parsePluginVersion(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin version is missing");
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionString.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid plugin version: " + versionString);
        }
        try {
            long parsed = Long.parseLong(matcher.group(1).replace(".", ""));
            if (parsed > Integer.MAX_VALUE) {
                throw new NumberFormatException("Version exceeds supported range");
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid plugin version: " + versionString, e);
        }
    }

    /**
     * Retrieves the YAML configuration object containing environment variables.
     * <p>
     * 获取包含环境变量的YAML配置对象。
     *
     * @return the YAML configuration object <br> YAML配置对象
     */
    public static YamlConfiguration getEnv() {
        YamlConfiguration config = new YamlConfiguration();
        try {
            Reader envReader = getInstance().getTextResource("env.yml");
            if (envReader == null) {
                throw new RuntimeException("env.yml not found in resources!");
            }
            config.load(envReader);
        } catch (IOException | InvalidConfigurationException e) {
            throw new RuntimeException("Failed to load env.yml configuration", e);
        }
        return config;
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();
        ultiTools = this;
        // Plugin classloader initialization
        URL serverJar = getServerJar();
        try {
            if (serverJar != null) {
                File serverFile = new File(serverJar.toURI());
                String name = serverFile.getName().split("\\.jar")[0];
                getLogger().info("Server Jar detected: " + name);
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEnable() {
        ultiToolsClassLoader = new URLClassLoader(getModuleUrls(), getClassLoader());
        this.eventBus = new EventBus();

        if (!initDependencies()) return;
        initLanguage();
        this.versionWrapper = new DefaultVersionWrapper();
        initDataStore();
        initPluginModules();
        initWebSocketManagers();
        new Metrics(this, 8652);

        boolean loginSuccess = attemptCloudLogin();
        if (loginSuccess) {
            // 显式开启云连接状态机。initWebsocket() 自己不再置位 —— 它被 reinitWebSocket
            // 复用，在那里置位会让一个正在途中的重连把 logout 关掉的状态机重新拉起来。
            PluginInitiationUtils.enableCloud();
            initWebSocket();
            CloudAuthManager.startTokenRefreshScheduler();
        }

        registerCommands();
        Bukkit.getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        scheduleStartupMessages(loginSuccess);
    }

    private boolean initDependencies() {
        try {
            dependenceManagers = new DependenceManagers(this, ultiToolsClassLoader);
            return true;
        } catch (Exception | NoClassDefFoundError error) {
            getLogger().log(Level.SEVERE, "Failed to initialize dependence managers", error);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private void initLanguage() {
        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getFileResource(lanPath);
        if (in == null) {
            getLogger().log(Level.WARNING, "Language file not found: " + lanPath + ", using default language");
            this.language = new Language("{}");
        } else {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String result = reader.lines().collect(Collectors.joining(""));
                this.language = new Language(result);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to read language file: " + lanPath, e);
                this.language = new Language("{}");
            }
        }
    }

    private void initDataStore() {
        configManager = new ConfigManager();
        boolean mysqlEnabled = getConfig().getBoolean("mysql.enable");
        boolean mysqlAvailable = false;
        if (mysqlEnabled) {
            MysqlDataStore mysqlDataStore = new MysqlDataStore();
            if (mysqlDataStore.getDataSource() != null) {
                DataStoreManager.register(mysqlDataStore);
                mysqlAvailable = true;
            }
        }
        DataStoreManager.register(new SQLiteDataStore());
        String storeType = getConfig().getString("datasource.type");
        //noinspection DataFlowIssue
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            dataStore = DataStoreManager.getDatastore("json");
        }
        // 配置要的后端和实际拿到的后端可能不是一回事，而这个降级过去是完全静默的。见 issue #183。
        DataStoreManager.reportBackendSelection(getLogger(), storeType, mysqlEnabled, mysqlAvailable,
                dataStore.getStoreType());
    }

    private void initPluginModules() {
        pluginManager = new PluginManager();
        File file = new File(getDataFolder() + File.separator + "plugins");
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
        try {
            pluginManager.init(ultiToolsClassLoader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void initWebSocketManagers() {
        serverMonitorManager = new ServerMonitorManager();
        commandExecutionManager = new CommandExecutionManager();
        fileOperationManager = new FileOperationManager();
        logStreamManager = LogStreamManager.getInstance();
        playerEventManager = new PlayerEventManager();
        serverPropertiesManager = new ServerPropertiesManager(new File(System.getProperty("user.dir")));
        errorReportCollector = new ErrorReportCollector();
        errorReportCollector.init();
        // Constructed unconditionally, gated by no Capability — D-32.
        remoteActionLog = new RemoteActionLog();
        remoteActionLog.init(getDataFolder());
    }

    private boolean attemptCloudLogin() {
        try {
            com.ultikits.ultitools.entities.TokenEntity savedToken = CloudAuthManager.loadSavedToken();
            if (savedToken != null) {
                getLogger().log(Level.INFO, "Found saved UltiCloud token, authenticating...");
                if (ApiRateLimiter.isAllowed("startup-login")) {
                    return PluginInitiationUtils.loginWithToken(savedToken);
                }
                getLogger().log(Level.INFO, "Skipping UltiCloud login (rate limited)");
            } else {
                getLogger().log(Level.FINE, "No saved UltiCloud token found. Use /ulticloud login to authenticate.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "UltiCloud login failed (server will continue without cloud features): " + e.getMessage());
        }
        return false;
    }

    private void initWebSocket() {
        getLogger().log(Level.INFO, i18n("正在初始化配置编辑Websocket服务..."));
        try {
            PluginInitiationUtils.initWebsocket();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, i18n("配置编辑Websocket服务初始化失败！") + e.getMessage());
        }
    }

    private void registerCommands() {
        Bukkit.getServicesManager().register(
                PluginManager.class,
                this.pluginManager,
                this,
                ServicePriority.Normal
        );

        CommandManager commandManager = getCommandManager();
        commandManager.registerCoreCommand(new UltiToolsCommands());
        commandManager.registerCoreCommand(new PluginInstallCommands());
        commandManager.registerCoreCommand(new CloudLoginCommand());
    }

    private void scheduleStartupMessages(boolean loginSuccess) {
        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
            if (loginSuccess) {
                getLogger().log(Level.INFO, i18n("UltiCloud: Connected!"));
                getLogger().log(Level.INFO, i18n("网页编辑器已启动！访问地址：https://panel.ultikits.com/manger"));
            } else {
                getLogger().log(Level.INFO, "UltiCloud: Not connected. Use /ulticloud login to authenticate.");
            }
            getLogger().log(Level.INFO, String.format(i18n("数据存储方式：%s"), dataStore.getStoreType()));
            getLogger().log(Level.INFO, String.format(i18n("UltiTools-API已启动，当前版本：%s"), getEnv().getString("version")));
            try {
                getLogger().log(Level.INFO, String.format(i18n("服务器UUID: %s"), getUltiToolsUUID()));
            } catch (IOException e) {
                getLogger().log(Level.WARNING, i18n("获取服务器UUID失败！") + e.getMessage());
            }

            // Start async update check
            updateManager = new UpdateManager(getLogger());
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    updateManager.checkUpdatesSync();
                }
            }.runTaskAsynchronously(UltiTools.this);

            // Register join listener for OP notifications
            Bukkit.getPluginManager().registerEvents(new UpdateJoinListener(updateManager), UltiTools.this);
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic

        if (eventBus != null) {
            eventBus.shutdown();
        }

        // 关闭错误报告收集器
        if (errorReportCollector != null) {
            errorReportCollector.shutdown();
        }

        // 关闭日志流管理器
        if (logStreamManager != null) {
            logStreamManager.shutdown();
        }

        CloudAuthManager.stopTokenRefreshScheduler();
        CloudAuthManager.stopPolling();
        if (dependenceManagers != null) {
            dependenceManagers.closeAdventure();
        }
        stopWebsocket();
        if (pluginManager != null) {
            pluginManager.close();
        }
        if (dependenceManagers != null) {
            dependenceManagers.closeContext();
        }
        getCommandManager().close();
        DataStoreManager.close();
        if (configManager != null) {
            configManager.saveAll();
        }
        Bukkit.getServicesManager().unregisterAll(this);
        if (ultiToolsClassLoader != null) {
            try {
                ultiToolsClassLoader.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to close module classloader", e);
            }
        }
    }

    /**
     * Reloads the UltiTools plugins by calling the reload method in the PluginManager.
     * <p>
     * 通过调用PluginManager中的reload方法重新加载UltiTools插件。
     *
     * @throws IOException if an I/O error occurs during the reloading process
     */
    public void reloadPlugins() throws IOException {
        // Refresh Bukkit config from disk so language changes are picked up
        reloadConfig();
        // Reinitialize framework language based on (possibly changed) config
        initLanguage();
        pluginManager.reload();
    }

    /**
     * Returns the supported language codes.
     * <p>
     * 返回支持的语言代码。
     *
     * @return a list of supported language codes <br> 支持的语言代码列表
     */
    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    /**
     * Internationalization method that translates the given string based on the current language.
     * If the string is not found in the dictionary, the original string is returned.
     * <p>
     * 根据当前语言翻译给定的字符串的国际化方法。
     * 如果在字典中找不到字符串，则返回原始字符串。
     *
     * @param str the string to be translated <br> 要翻译的字符串
     * @return the translated string or the original string if not found in the dictionary <br> 翻译后的字符串，如果在字典中找不到，则为原始字符串
     */
    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    /**
     * Retrieves the input stream for the specified file resource.
     * <p>
     * 获取指定文件资源的输入流。
     *
     * @param filename the name of the file resource
     * @return the input stream for the file resource, or null if an I/O error occurs
     */
    private InputStream getFileResource(String filename) {
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

    /**
     * Get the economy provider
     * <p>
     * 获取经济服务提供者
     *
     * @return the instance of the Economy provider <br> 经济服务提供者实例
     */
    public Economy getEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            throw new RuntimeException("Vault not found!");
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            throw new RuntimeException("Economy service not found!");
        }
        return registration.getProvider();
    }

    /**
     * Get server jar file URL.
     * <br>
     * 获取服务器Jar文件URL。
     *
     * @return Server jar file URL <br> 服务器Jar文件URL
     */
    public URL getServerJar() {
        ProtectionDomain protectionDomain = Bukkit.class.getProtectionDomain();
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        if (codeSource.getLocation().toString().startsWith("union:")) {
            String replace = codeSource.getLocation().toString().replace("union:", "file:").split("%")[0];
            try {
                return new java.net.URI(replace).toURL();
            } catch (MalformedURLException | URISyntaxException e) {
                getLogger().log(Level.WARNING, "Failed to parse server JAR URL: " + replace, e);
            }
        }
        return codeSource.getLocation();
    }

    /**
     * Get URLs for module plugin JARs in the UltiTools/plugins directory.
     * <br>
     * 获取 UltiTools/plugins 目录中模块插件JAR的URL。
     *
     * @return Array of URLs for module plugin JARs
     */
    private URL[] getModuleUrls() {
        List<URL> urls = new ArrayList<>();

        // Add server JAR
        URL serverJar = getServerJar();
        if (serverJar != null) {
            urls.add(serverJar);
        }

        // Add module plugin JARs from UltiTools/plugins/
        File pluginDir = new File(getDataFolder(), "plugins");
        urls.addAll(collectModuleJarUrls(pluginDir));

        return urls.toArray(new URL[0]);
    }

    /**
     * Scan a directory for module plugin JARs and collect the URLs of the ones that pass
     * {@link SecurityPolicy#isValidModuleJar(File)} — a JAR is validated <b>before</b> its URL is
     * added, never after. A failing JAR is skipped and named in a WARNING; the scan continues and
     * never throws (D-05: module-granularity skip, not a bootstrap abort).
     * <br>
     * 扫描目录下的模块插件 JAR，收集通过 {@link SecurityPolicy#isValidModuleJar(File)} 校验的
     * URL —— 校验发生在 URL 被添加<b>之前</b>，而不是之后。未通过校验的 JAR 会被跳过并在
     * WARNING 中命名；扫描继续进行，不会抛出异常（D-05：模块级别跳过，而非中止整个启动）。
     *
     * <p>Package-private and static so it can be exercised directly by a test against a
     * {@code @TempDir}, without standing up the whole plugin.</p>
     *
     * @param pluginDir directory to scan for module JARs <br> 待扫描的模块 JAR 目录
     * @return collected URLs of the JARs that passed validation, empty if {@code pluginDir} is
     *         {@code null} or does not exist <br> 通过校验的 JAR 的 URL 集合；
     *         若 {@code pluginDir} 为 {@code null} 或不存在则为空集合
     */
    static List<URL> collectModuleJarUrls(File pluginDir) {
        List<URL> urls = new ArrayList<>();
        if (pluginDir == null || !pluginDir.exists()) {
            return urls;
        }
        File[] pluginFiles = pluginDir.listFiles((f) -> f.getName().endsWith(".jar"));
        if (pluginFiles == null) {
            return urls;
        }
        for (File f : pluginFiles) {
            if (!SecurityPolicy.isValidModuleJar(f)) {
                MODULE_SCAN_LOGGER.log(Level.WARNING,
                        "[UltiTools-API] Skipped module JAR (failed security validation), not added "
                                + "to module classpath: " + f.getName());
                continue;
            }
            try {
                urls.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                MODULE_SCAN_LOGGER.log(Level.WARNING, "Failed to add module JAR to classpath: " + f.getName(), e);
            }
        }
        return urls;
    }

    /**
     * Get the JavaPlugin class loader.
     * This ensures all class loading operations use the correct parent class loader.
     * <br>
     * 获取JavaPlugin类加载器。
     * 这确保所有类加载操作都使用正确的父类加载器。
     *
     * @return JavaPlugin class loader <br> JavaPlugin类加载器
     */
    public static ClassLoader getJavaPluginClassLoader() {
        UltiTools instance = getInstance();
        if (instance != null) {
            if (instance.ultiToolsClassLoader != null) {
                return instance.ultiToolsClassLoader;
            }
            return instance.getClass().getClassLoader();
        }
        // Fallback for testing environments where plugin is not initialized
        return Thread.currentThread().getContextClassLoader();
    }
}
