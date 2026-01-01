package com.ultikits.ultitools;

import static com.ultikits.ultitools.utils.CommonUtils.getUltiToolsUUID;
import static com.ultikits.ultitools.utils.PluginInitiationUtils.loginAccount;
import static com.ultikits.ultitools.utils.PluginInitiationUtils.stopWebsocket;
import static com.ultikits.ultitools.utils.VersionUtils.getUltiToolsNewestVersion;

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
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

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
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.PlayerEventManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.utils.HttpDownloadUtils;
import com.ultikits.ultitools.utils.Metrics;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

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
    private boolean needLoadLib = false;
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Getter
    private DependenceManagers dependenceManagers;
    /**
     * @deprecated Use {@link com.ultikits.ultitools.utils.XVersionUtils} instead.
     */
    @Deprecated
    @Getter
    private VersionWrapper versionWrapper;
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
    private URLClassLoader ultiToolsClassLoader;
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
        if (versionString == null) {
            throw new RuntimeException("Version not found in env.yml!");
        }
        return Integer.parseInt(versionString.replace(".", ""));
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
        downloadRequiredDependencies();
    }

    @Override
    public void onEnable() {
        // Load all lib
        ultiToolsClassLoader = new URLClassLoader(getLibs(), getClassLoader());
        // External bukkit libraries initialization
        try {
            dependenceManagers = new DependenceManagers(this, ultiToolsClassLoader);
        } catch (Exception | NoClassDefFoundError error) {
            needLoadLib = true;
        }
        if (needLoadLib) {
            getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
                getLogger().log(Level.WARNING, "UltiTools初始化完成，但是还需重启加载依赖，请重启服务端！");
            }, 0, 20 * 30);
            return;
        }
        // Language initialization
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

        // Adopt server version (now using XSeries, no dynamic loading needed)
        this.versionWrapper = new DefaultVersionWrapper();

        // Config initialization & DataStore initialization
        configManager = new ConfigManager();
        if (getConfig().getBoolean("mysql.enable")) {
            MysqlDataStore mysqlDataStore = new MysqlDataStore();
            if (mysqlDataStore.getDataSource() != null) {
                DataStoreManager.register(mysqlDataStore);
            }
        }
        DataStoreManager.register(new SQLiteDataStore());
        String storeType = getConfig().getString("datasource.type");
        //noinspection DataFlowIssue
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            dataStore = DataStoreManager.getDatastore("json");
        }

        // initialize plugin modules
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
        
        // 初始化WebSocket管理器
        serverMonitorManager = new ServerMonitorManager();
        commandExecutionManager = new CommandExecutionManager();
        fileOperationManager = new FileOperationManager();
        logStreamManager = LogStreamManager.getInstance();
        playerEventManager = new PlayerEventManager();
        
        // Initialize metrics
        new Metrics(this, 8652);

        // Embed web server initialization & Account login
        String username = UltiTools.getInstance().getConfig().getString("account.username");
        String password = UltiTools.getInstance().getConfig().getString("account.password");
        boolean loginRequired = username != null && password != null && !username.isEmpty() && !password.isEmpty();
        boolean loginSuccess = false;
        try {
            if (loginRequired) {
                loginSuccess = loginAccount(username, password);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (loginSuccess && getConfig().getBoolean("web-editor.enable")) {
            getLogger().log(Level.INFO, i18n("正在初始化配置编辑Websocket服务..."));
            try {
                PluginInitiationUtils.initWebsocket();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, i18n("配置编辑Websocket服务初始化失败！") + e.getMessage());
            }
        }

        Bukkit.getServicesManager().register(
                PluginManager.class,
                this.pluginManager,
                this,
                ServicePriority.Normal
        );

        // Register core UltiTools commands using the dedicated method
        // 使用专用方法注册核心UltiTools命令
        CommandManager commandManager = getCommandManager();
        commandManager.registerCoreCommand(new UltiToolsCommands());
        commandManager.registerCoreCommand(new PluginInstallCommands());
        
        // Register log transmission test commands for development/testing
        // 注册日志传输测试命令（用于开发/测试）
        try {
            com.ultikits.ultitools.commands.LogTransmissionCommands logTestCommands = 
                new com.ultikits.ultitools.commands.LogTransmissionCommands();
            commandManager.registerCoreCommand(logTestCommands);
            getLogger().info("[UltiTools] 日志传输测试命令已注册: /logtest");
        } catch (Exception e) {
            getLogger().warning("[UltiTools] 注册日志传输测试命令失败: " + e.getMessage());
        }

        Bukkit.getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);

        boolean finalLoginSuccess = loginSuccess;
        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
            if (loginRequired) {
                if (finalLoginSuccess) {
                    getLogger().log(Level.INFO, String.format(i18n("UltiKits账户 %s 登录成功！"), username));
                } else {
                    getLogger().log(Level.WARNING, String.format(i18n("UltiKits账户 %s 登录失败！云端相关功能将无法使用！"), username));
                }
            }
            if (getConfig().getBoolean("web-editor.enable")) {
                getLogger().log(Level.INFO, i18n("网页编辑器已启动！访问地址：https://panel.ultikits.com/manger"));
            } else {
                getLogger().log(Level.INFO, i18n("网页编辑器未启用！"));
            }
            getLogger().log(Level.INFO, String.format(i18n("数据存储方式：%s"), dataStore.getStoreType()));
            String ultiToolsNewestVersion = getUltiToolsNewestVersion();
            String currentVersion = getEnv().getString("version");
            getLogger().log(Level.INFO, String.format(i18n("UltiTools-API已启动，当前版本：%s"), getEnv().getString("version")));
            getLogger().log(Level.INFO, String.format(i18n("服务器UUID: %s"), getUltiToolsUUID()));
            getLogger().log(Level.INFO, i18n("正在检查版本更新..."));
            if (dependenceManagers.getVersionComparator().compare(currentVersion, ultiToolsNewestVersion) < 0) {
                getLogger().log(Level.INFO, String.format(i18n("UltiTools-API有新版本 %s 可用，请及时更新！"), ultiToolsNewestVersion));
                getLogger().log(Level.INFO, String.format(i18n("下载地址：%s"), "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
                return;
            }
            getLogger().log(Level.INFO, i18n("UltiTools-API已是最新版本！"));
        });
    }

    @Override
    public void onDisable() {
        if (needLoadLib) {
            return;
        }
        // Plugin shutdown logic
        
        // 关闭日志流管理器
        if (logStreamManager != null) {
            logStreamManager.shutdown();
        }
        
        dependenceManagers.closeAdventure();
        stopWebsocket();
        pluginManager.close();
        dependenceManagers.closeContext();
        getCommandManager().close();
        DataStoreManager.close();
        getConfigManager().saveAll();
        Bukkit.getServicesManager().unregisterAll(this);
    }

    /**
     * Reloads the UltiTools plugins by calling the reload method in the PluginManager.
     * <p>
     * 通过调用PluginManager中的reload方法重新加载UltiTools插件。
     *
     * @throws IOException if an I/O error occurs during the reloading process
     */
    public void reloadPlugins() throws IOException {
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

    private URL[] getLibs() {
        File libDir = new File(getDataFolder(), "lib");
        if (!libDir.exists()) {
            libDir.mkdirs();
        }
        File[] libFiles = libDir.listFiles();
        if (libFiles == null) {
            return new URL[]{getServerJar()};
        }

        List<File> files = new ArrayList<>(Arrays.asList(libFiles));
        File pluginsFolder = getDataFolder().getParentFile();
        if (pluginsFolder != null) {
            File[] folderFiles = pluginsFolder.listFiles();
            if (folderFiles != null) {
                for (File file : folderFiles) {
                    if (file.getName().endsWith(".jar")) {
                        files.add(file);
                    }
                }
            }
        }

        File pluginDir = new File(getDataFolder(), "plugins");
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
        }
        File[] pluginFiles = pluginDir.listFiles();
        if (pluginFiles != null) {
            files.addAll(Arrays.asList(pluginFiles));
        }

        URL[] urls = new URL[files.size() + 1];
        for (int i = 0; i < files.size(); i++) {
            try {
                urls[i] = files.get(i).toURI().toURL();
            } catch (MalformedURLException e) {
                getLogger().log(Level.WARNING, "Failed to convert file to URL: " + files.get(i), e);
            }
        }
        urls[files.size()] = getServerJar();
        return urls;
    }

    /**
     * Download required dependencies.
     * <br>
     * 下载必要的依赖。
     */
    private void downloadRequiredDependencies() {
        String libFolder = new File(System.getProperty("user.dir") + File.separator + "plugins" + File.separator + ".paper-remapped").exists() ? 
        System.getProperty("user.dir") + File.separator + "plugins" + File.separator + ".paper-remapped" + File.separator + "UltiTools" + File.separator + "lib" 
        : UltiTools.getInstance().getDataFolder() + File.separator + "lib";
        
        File libDir = new File(libFolder);
        if (!libDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            libDir.mkdirs();
        }
        
        YamlConfiguration env = UltiTools.getEnv();
        List<String> libraries = env.getStringList("libraries");
        if (libraries.isEmpty()) {
            getLogger().log(Level.WARNING, "No libraries defined in env.yml");
            return;
        }
        
        List<String> missingLib = libraries
                .stream()
                .map(lib -> new File(libFolder, lib))
                .filter(file -> !file.exists()).map(File::getName)
                .collect(Collectors.toList());
                
        if (missingLib.isEmpty()) {
            return;
        }
        
        getLogger().log(Level.INFO, "Missing required libraries, trying to download...");
        getLogger().log(Level.INFO, "If have problems in downloading, you can download full version.");
        
        String ossUrl = env.getString("oss-url");
        String libPath = env.getString("lib-path");
        if (ossUrl == null || libPath == null) {
            getLogger().log(Level.SEVERE, "OSS URL or lib path not configured in env.yml");
            return;
        }
        
        for (int i = 0; i < missingLib.size(); i++) {
            String name = missingLib.get(i);
            String url = ossUrl + libPath + name;
            double progress = (double) i / missingLib.size();
            int percentage = (int) (progress * 100);
            printLoadingBar(percentage);
            
            try {
                HttpDownloadUtils.download(url, name, libFolder);
                needLoadLib = true;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to download library: " + name, e);
            }
        }
        printLoadingBar(100);
        getLogger().log(Level.INFO, "All required libraries have been downloaded.");
    }

    private void printLoadingBar(final int percentage) {
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
            return instance.getClass().getClassLoader();
        }
        // Fallback for testing environments where plugin is not initialized
        return Thread.currentThread().getContextClassLoader();
    }
}
